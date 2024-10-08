package com.beepsoft.hasuraconf

import com.beepsoft.hasuraconf.annotation.*
import com.google.common.net.HttpHeaders
import io.hasura.metadata.v3.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.pearx.kasechange.CaseFormat
import net.pearx.kasechange.toCamelCase
import net.pearx.kasechange.toCase
import org.apache.commons.text.CaseUtils
import org.hibernate.SessionFactory
import org.hibernate.internal.SessionFactoryImpl
import org.hibernate.metamodel.spi.MetamodelImplementor
import org.hibernate.persister.collection.BasicCollectionPersister
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.type.*
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import javax.persistence.EntityManagerFactory
import javax.persistence.OneToMany
import javax.persistence.OneToOne
import javax.persistence.metamodel.EntityType

/**
 * Creates JSON to initialize Hasura. The JSON:
 *
 *  * adds tracking to all Hibernate mapped tables
 *  * adds custom field and relationship names to match names used in the Java Entity classes (Hasura
 * would you by default the Postgresql column names)
 *
 *
 *
 * We only generate relationships that are actually defined in the entities. Hasura, normally, would create, eg.
 * inverse relationships as well, but we only generate relationships hat are actually defned in code.
 * This is the reason why you would see a number of "Untracked foreign-key relations" suggested by Hasura after init.json
 * has been applied to it.
 *
 *
 *
 * init.json will contains following kind of API calls:
 *
 * https://docs.hasura.io/1.0/graphql/manual/api-reference/schema-metadata-api/table-view.html#set-table-custom-fields
 *
 * Track table:
 *
 * <pre>
 * {
 * "type": "add_existing_table_or_view",
 * "args": {
 * "name": "operation",
 * "schema": "public"
 * }
 * }
 *  </pre>
 *
 *
 * Custom fields for a tracked table:
 *
 * <pre>
 * {
 * "type": "set_table_custom_fields",
 * "version": 2,
 * "args": {
 * "table": "operation",
 * "custom_root_fields": {
 * "select": "Operations",
 * "select_by_pk": "Operation",
 * "select_aggregate": "OperationAggregate",
 * "insert": "AddOperations",
 * "update": "UpdateOperations",
 * "delete": "DeleteOperations"
 * },
 * "custom_column_names": {
 * "id": "operationid"
 * }
 * }
 * }
</pre> *
 *
 * Object relationship with mapping done on the same table:
 *
 * <pre>
 * {
 * "type": "create_object_relationship",
 * "args": {
 * "name": "task",
 * "table": {
 * "name": "operation",
 * "schema": "public"
 * },
 * "using": {
 * "foreign_key_constraint_on": "task_id"
 * }
 * }
 * }
</pre> *
 *
 * Object relationship with other side doing the mapping (ie. with mappedBy ...):
 *
 * <pre>
</pre> *
 *
 *
 *
 * Array relationship:
 * <pre>
</pre> *
 */
class HasuraConfigurator(
    var entityManagerFactory: EntityManagerFactory,
    var schemaName: String,
    var hasuraSchemaEndpoint: String,
    var hasuraMetadataEndpoint: String,
    var hasuraAdminSecret: String?,
    var actionRoots: List<String>? = null,
    var sourceCustomization: SourceCustomization? = null,
    var rootFieldNameProvider: RootFieldNameProvider = DefaultRootFieldNameProvider(),

    var jsonSchemaVersion: String,
    var customJsonSchemaPropsFieldName: String,
    var ignoreJsonSchema: Boolean = false,
    var ignoreUnkonwFieldsInExportedMetadata: Boolean = false,

    var checkConstraintGenerator: HasuraCheckConstraintGenerator,
    var generateCheckConstraintsForJSRValidationAnnnotations: Boolean = true,
) {

    companion object {
        // @Suppress("JAVA_CLASS_ON_COMPANION")
        // @JvmStatic
        public val LOG = getLogger(this::class.java.enclosingClass)

        // Acrtual postgresql types for some SQL types. Hibernate uses the key fields when generating
        // tables, however Postgresql uses the "values" of postgresqlNames and so does Hasura when
        // generating graphql schema based on the DB schema.
        // https://hasura.io/docs/1.0/graphql/manual/api-reference/postgresql-types.html
        // TODO: should check these some more ...
        public val postgresqlNames = mapOf(
            "int2" to "Int",
            "int4" to "Int",
            "int8" to "bigint",
            "int" to "Int",
            "serial2" to "Int",
            "serial4" to "Int",
            "serial8" to "bigserial",
            "bool" to "Boolean",
            "date" to "Date",
            "float4" to "Float",
            "text" to "text",
            "varchar(\$l)" to "String",
            "time" to "time",
            "timetz" to "time",
            "timestamp" to "timestamp",
            "timestamptz" to "timestamp",
            "uuid" to "uuid",
            // not included in Hasura's description, but in our case java Float is translated to 'real' and here back
            // to graphql Float
            "real" to "Float",
        )

        fun graphqlTypeFor(metaModel: MetamodelImplementor, columnType: Type, classMetadata: AbstractEntityPersister): String {
            val sqlType = columnType.sqlTypes(metaModel.sessionFactory as SessionFactoryImpl)[0];
            val keyTypeName = classMetadata.factory.jdbcServices.dialect.getTypeName(sqlType)
            if (postgresqlNames[keyTypeName] == null) {
                throw Error("No postgresql alias found for type $keyTypeName. Graphql type cannot be calculated.")
            }
            return postgresqlNames[keyTypeName]!!
        }
    }


    private var sessionFactoryImpl: SessionFactory
    private var metaModel: MetamodelImplementor
    private var permissionAnnotationProcessor: PermissionAnnotationProcessor

    private lateinit var jsonSchemaGenerator: HasuraJsonSchemaGenerator
    private lateinit var actionGenerator: HasuraActionGenerator

    private lateinit var tableNames: MutableSet<String>
    private lateinit var entityClasses: MutableSet<Class<out Any>>
    private lateinit var manyToManyEntities: MutableMap<String, ManyToManyEntity>
    private lateinit var extraTableNames: MutableSet<String>
    private lateinit var classToTableName: MutableMap<Class<out Any>, String>

    private lateinit var metadataAndSql: HasuraConfiguration
    private lateinit var enumTableConfigs: MutableList<EnumTableConfig>
    private lateinit var computedFieldConfigs: MutableList<ComputedFieldConfig>
    private lateinit var cascadeDeleteFieldConfigs: MutableSet<CascadeDeleteFieldConfig>

    private lateinit var sourceCustomizationGenerator: HasuraSourceCustomizationGenerator

    // {
    //  "type": "bulk",
    //  "source": "default",
    //  "args": [
    //    {
    //      "type": "run_sql",
    //      "args": {
    //        "source": "default",
    //        "sql": "SET LOCAL statement_timeout = 10000;",
    //        "cascade": false,
    //        "read_only": false
    //      }
    //    },
    //    {
    //      "type": "run_sql",
    //      "args": {
    //        "source": "default",
    //        "sql": "CREATE OR REPLACE FUNCTION public.emailfromaccount2(users_row users)\n RETURNS text\n LANGUAGE sql\n STABLE\nAS $function$ SELECT email FROM auth.accounts WHERE auth.accounts.user_id = users_row.id $function$;",
    //        "cascade": false,
    //        "read_only": false
    //      }
    //    }
    //  ]
    //}
    private lateinit var runSqlDefs: MutableList<JsonObject>

    init {
        sessionFactoryImpl = entityManagerFactory.unwrap(SessionFactoryImpl::class.java)
        metaModel = sessionFactoryImpl.metamodel as MetamodelImplementor
        permissionAnnotationProcessor = PermissionAnnotationProcessor(entityManagerFactory)
        sourceCustomizationGenerator = HasuraSourceCustomizationGenerator()
    }


    /**
     * Creates hasura-conf.json containing table tracking and field/relationship name customizations
     * at bean creation time automatically.
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Throws(HasuraConfiguratorException::class)
    @JvmOverloads
    fun configure(
        sourceName: String = DEFAULT_SOURCE_NAME,
        sourceConfig: Configuration = Configuration(
            connectionInfo = SourceConnectionInfo(
                databaseURL = DatabaseURL.PGConnectionParametersClassValue(
                    PGConnectionParametersClass(fromEnv = "HASURA_GRAPHQL_DATABASE_URL")
                )
            )
        ),
        backendKind: BackendKind = BackendKind.Postgres
    ): HasuraConfiguration {
        jsonSchemaGenerator = HasuraJsonSchemaGenerator(jsonSchemaVersion, customJsonSchemaPropsFieldName)
        actionGenerator = HasuraActionGenerator(metaModel)
        cascadeDeleteFieldConfigs = mutableSetOf<CascadeDeleteFieldConfig>()
        manyToManyEntities = mutableMapOf()
        extraTableNames = mutableSetOf()
        tableNames = mutableSetOf()
        classToTableName = mutableMapOf()
        entityClasses = mutableSetOf<Class<out Any>>()
        runSqlDefs = mutableListOf()
        enumTableConfigs = mutableListOf()
        computedFieldConfigs = mutableListOf()

        // Get metaModel.entities sorted by name. We do this sorting to make result more predictable (eg. for testing)
        val entities = sortedSetOf(
            Comparator { o1, o2 -> o1.name.compareTo(o2.name) },
            *metaModel.entities.toTypedArray()
        )

        var actualSourceCustomization = sourceCustomization
        var annotBasedCustomization = sourceCustomizationGenerator.generateSourceCustomization(entities)
        if (annotBasedCustomization != null) {
            actualSourceCustomization = annotBasedCustomization
        }

        var checkConstraintRunSqls : MutableList<JsonObject>? = null

//        metadataAndSql = HasuraConfiguration(HasuraMetadataV3(), mutableListOf())
        val actionsAndTypes = if (actionRoots != null) actionGenerator.configureActions(actionRoots!!) else null
        val meta = HasuraMetadataV3(
            version = 3,
            sources = buildList {
                add(Source(
                    name = sourceName,
                    kind = backendKind,
                    configuration = sourceConfig,
                    tables = buildList {
                        for (entity in entities) {
                            // Ignore subclasses of classes with @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
                            // In this case all subclasse's fields will be stored in the parent's table and therefore subclasses
                            // cannot have root operations.
                            if (entity.parentHasSingleTableInheritance()) {
                                continue
                            }
                            // Add TableEntry for entity
                            add(configureTableEntry(entity, entities, sourceName))

                            // Generate CHECK constraints for fields
                            if (generateCheckConstraintsForJSRValidationAnnnotations) {
                                val runSqls = checkConstraintGenerator.generateCheckConstraints(metaModel, entity, sourceName)
                                if (runSqls != null && runSqls.isNotEmpty()) {
                                    if (checkConstraintRunSqls == null) {
                                        checkConstraintRunSqls = mutableListOf()
                                    }
                                    checkConstraintRunSqls!!.addAll(runSqls)
                                }
                            }
                        }
                        // Add config for many-to-many join tables, which have no Java class representation but still
                        // want to have access to them
                        for (m2m in manyToManyEntities.values) {
                            configureManyToManyEntity(m2m).let {
                                add(it)
                            }
                        }

                        // Add tracking to extra table names. For now these could be join tables of array relations
                        for (tableName in extraTableNames) {
                            // if tableName is also present in standard tableNames, it means it was either a Java
                            // mapped entity or an m2m join table which we handle previously
                            if (tableNames.contains(tableName)) {
                                continue
                            }

                            // It is really an extra table name, add it without any further config
                            add(TableEntry(actualQualifiedTable(schemaName, tableName)))
                        }
                    },
                    customization = actualSourceCustomization
                ))
            },
            actions = if(actionsAndTypes != null) actionsAndTypes!!.actions else null,
            customTypes = if(actionsAndTypes != null)  actionsAndTypes!!.customTypes else null,
            // TODO:
            // cronTriggers =
            // remoteSchemas =
            // restEndpoints =
            // allowlist =
            // inheritedRoles =
            // queryCollections =
        )

        return HasuraConfiguration(
            metadata = meta,
            enumTableConfigs = enumTableConfigs,
            computedFieldConfigs = computedFieldConfigs,
            cascadeDeleteFieldConfigs = cascadeDeleteFieldConfigs.toList(),
            jsonSchema = when {
                !ignoreJsonSchema -> jsonSchemaGenerator.generateSchema(*entityClasses.toTypedArray()).toString()
                    .reformatJson()
                else -> null
            },
            checkConstraintRunSqls = checkConstraintRunSqls
        )
    }

    private fun JsonElement.clone(): JsonElement =
        Json.decodeFromString(Json.encodeToString(this))

    private fun configureTableEntry(entity: EntityType<*>, entities: Set<EntityType<*>>, sourceName: String) : TableEntry
    {
        val relatedEntities = entity.relatedEntities(entities)
        val targetEntityClassMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val tableName = targetEntityClassMetadata.tableName
        val keyKolumnName = targetEntityClassMetadata.keyColumnNames[0]

        tableNames.add(tableName)
        classToTableName.put(entity.javaType, tableName)

        // Add ID field
        val f = Utils.findDeclaredFieldUsingReflection(entity.javaType, targetEntityClassMetadata.identifierPropertyName)
        jsonSchemaGenerator.addSpecValue(f!!,
            HasuraSpecPropValues(graphqlType = graphqlTypeFor(metaModel, targetEntityClassMetadata.identifierType, targetEntityClassMetadata)))

        entityClasses.add(entity.javaType)

        var entityName = entity.name

        // Get the HasuraRootFields and may reset entityName
        var rootFields = entity.javaType.getAnnotation(HasuraRootFields::class.java)
        if (rootFields != null && rootFields.baseName.isNotBlank()) {
            entityName = rootFields.baseName
        }

        // Remove inner $ from the name of inner classes
        entityName = entityName.replace("\\$".toRegex(), "")
        var entityNameLower = entityName.toString()
        entityNameLower = Character.toLowerCase(entityNameLower[0]).toString() + entityNameLower.substring(1)

        val rootFieldNames = generateRootFieldNames(rootFields, entityName, entityNameLower, tableName)

        jsonSchemaGenerator.addSpecValue(entity.javaType,
            HasuraSpecTypeValues(
                graphqlType=tableName,
                idProp=keyKolumnName,
                rootFieldNames = rootFieldNames))

        collectCascadeDeleteCandidates(entity)

        val permissions = permissionAnnotationProcessor.process(entity)
        val table = TableEntry(
            table = actualQualifiedTable(schemaName, tableName),
            configuration = TableConfig(
                customRootFields = configureCustomRootFields(rootFieldNames),
                //customColumnNames = configureCustomColumnNamesMap(relatedEntities),
                columnConfig = configureCustomColumnConfigs(relatedEntities)
            ),
            objectRelationships = configureObjectRelationships(relatedEntities),
            arrayRelationships = configureArrayRelationships(relatedEntities),
            computedFields = let {
                val cfs = configureComputedFields(entity, sourceName)
                if (cfs == null) {
                    null
                }
                else {
                    computedFieldConfigs.addAll(cfs)
                    cfs.map { it.computedField }
                }
            },
            insertPermissions = configurePermission(permissions, HasuraOperation.INSERT),
            selectPermissions = configurePermission(permissions, HasuraOperation.SELECT),
            updatePermissions = configurePermission(permissions, HasuraOperation.UPDATE),
            deletePermissions = configurePermission(permissions, HasuraOperation.DELETE),
            isEnum = let {
                // Check if table is enum, collect its config
                val c = configureEnumTable(entity, sourceName)
                if (c == null) {
                    null
                }
                else {
                    enumTableConfigs.add(c)
                    true
                }
            }
        )

        return table
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun configureEnumTable(entity: EntityType<*>, sourceName: String) : EnumTableConfig?
    {
        val targetEntityClassMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val tableName = targetEntityClassMetadata.tableName

        val entityClass = entity.javaType
        if (entityClass.isAnnotationPresent(HasuraEnum::class.java)) {
            return EnumTableConfig(
                tableName = tableName,
                schema = schemaName,
                // Generate SQL for enum values like ths:
                // INSERT INTO public.todo_item_status (value, description) VALUES ('STARTED', 'Started - todo item started') ON CONFLICT DO NOTHING;
                // enum insertions always come first, any other sql, like computed field function come after
                runSqls = buildList {
                    add(buildJsonObject {
                        put("hasuraconfComment", """Enum values for ${entityClass.simpleName}""")
                        put("type", "run_sql")
                        putJsonObject("args") {
                            val sql = buildString {
                                entityClass.declaredFields
                                    .filter { it.isEnumConstant }
                                    .forEach { enumField ->
                                        val enumVal = java.lang.Enum.valueOf(entityClass as Class<out Enum<*>?>, enumField.name)
                                        val descriptionField = entityClass.declaredFields.first { it.name == "description" }
                                        descriptionField.isAccessible = true
                                        append("INSERT INTO $schemaName.$tableName (value, description) VALUES ('${enumField.name}', '${descriptionField.get(enumVal)}') ON CONFLICT DO NOTHING;\n")
                                    }
                                entityClass.declaredFields
                                    .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)  && it.isAnnotationPresent(HasuraEnumValue::class.java) }
                                    .forEach { staticField ->
                                        staticField.isAccessible = true
                                        append("INSERT INTO $schemaName.$tableName (value, description) VALUES ('${staticField.name}', '${staticField.get(entityClass)}') ON CONFLICT DO NOTHING;\n")

                                    }
                            }
                            put("source", sourceName)
                            put("sql", sql)
                            put("cascade", false)
                            put("read_only", false)
                        }
                    })
                }
            )
        }
        return null
    }

    private fun configureComputedFields(entity: EntityType<*>, sourceName: String): List<ComputedFieldConfig>?
    {
        val fields = entity.javaType.declaredFields
            .filter {
                it.isAnnotationPresent(HasuraComputedField::class.java)
            }
            .map { field ->
                val annot = field.getAnnotation(HasuraComputedField::class.java)

                ComputedFieldConfig(
                    computedField = ComputedField(
                        name = field.name,
                        comment = if(annot.comment.isNotEmpty()) annot.comment else null,
                        definition = ComputedFieldDefinition(
                            function = if (annot.functionSchema.isNotEmpty())
                                FunctionName.QualifiedFunctionValue(QualifiedFunction(annot.functionName, annot.functionSchema))
                            else
                                FunctionName.QualifiedFunctionValue(QualifiedFunction(annot.functionName, schemaName))
                        )
                    ),
                    runSql = if (annot.functionDefinition.isNotEmpty())
                                buildJsonObject {
                                    put("hasuraconfComment", """Computed field ${field.name} SQL function""")
                                    put("type", "run_sql")
                                    putJsonObject("args") {
                                        put("source", sourceName)
                                        put("sql", annot.functionDefinition)
                                        put("cascade", false)
                                        put("read_only", false)
                                    }
                                }
                            else null
                )

            }
        return if (fields.isNotEmpty()) fields else null
    }


    private inline fun <reified T> configurePermission(permissions: List<PermissionData>, op:HasuraOperation) : List<T>?
    {
        val perms = permissions.filter { it.operation==op }.map { permissionData ->
            permissionData.toJsonObject()
        }
        if (perms.isNotEmpty()) {
            return Json{ignoreUnknownKeys = true}.decodeFromJsonElement<List<T>>(JsonArray(perms))
        }
        return null
    }

    private fun configurePermissions(permissions: List<PermissionData>, table: TableEntry)
    {
        let {
            val inserts = permissions.filter { it.operation==HasuraOperation.INSERT }.map { permissionData ->
                permissionData.toJsonObject()
            }
            if (!inserts.isEmpty()) {
                table.insertPermissions = Json.decodeFromJsonElement<List<InsertPermissionEntry>>(JsonArray(inserts))
            }

            val selects = permissions.filter { it.operation==HasuraOperation.SELECT }.map { permissionData ->
                permissionData.toJsonObject()
            }
            if (!selects.isEmpty()) {
                table.selectPermissions = Json.decodeFromJsonElement<List<SelectPermissionEntry>>(JsonArray(selects))
            }

            val updates = permissions.filter { it.operation==HasuraOperation.UPDATE }.map { permissionData ->
                permissionData.toJsonObject()
            }
            if (!updates.isEmpty()) {
                table.updatePermissions = Json.decodeFromJsonElement<List<UpdatePermissionEntry>>(JsonArray(updates))
            }

            val deletes = permissions.filter { it.operation==HasuraOperation.DELETE }.map { permissionData ->
                permissionData.toJsonObject()
            }
            if (!deletes.isEmpty()) {
                table.deletePermissions = Json.decodeFromJsonElement<List<DeletePermissionEntry>>(JsonArray(deletes))
            }
        }
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun configureCascadeDeleteTriggers(cascadeDeleteFields: MutableSet<CascadeDeleteFieldConfig>)
    {
        for (cdf in cascadeDeleteFields) {
            val template =
                """
                    DROP TRIGGER IF EXISTS ${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete_trigger ON ${cdf.tableSchema}.${cdf.table};;
                    DROP FUNCTION  IF EXISTS ${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete();
                    CREATE FUNCTION ${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete() RETURNS trigger AS
                    ${'$'}body${'$'}
                    BEGIN
                        IF TG_WHEN <> 'AFTER' OR TG_OP <> 'DELETE' THEN
                            RAISE EXCEPTION '${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete may only run as a AFTER DELETE trigger';
                        END IF;
                    
                        DELETE FROM ${cdf.joinedTable}.${cdf.joinedTable} where id=OLD.${cdf.field};
                        RETURN OLD;
                    END;
                    ${'$'}body${'$'}
                    LANGUAGE plpgsql;;
                    CREATE TRIGGER ${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete_trigger AFTER DELETE ON ${cdf.tableSchema}.${cdf.table}
                        FOR EACH ROW EXECUTE PROCEDURE ${cdf.tableSchema}_${cdf.table}_${cdf.field}_cascade_delete();;                       
                """.trimIndent()
            val trigger = template.replace("\n", " ")

            cdf.runSql = buildJsonObject {
                put("type", "run_sql")
                putJsonObject("args") {
                    put("sql", trigger)
                }
            }
        }
    }

    private fun configureCustomRootFields(rootFieldNames: RootFieldNames) : CustomRootFields
    {
        return CustomRootFields(
            select = "${rootFieldNames.select}",
            selectByPk = "${rootFieldNames.selectByPk}",
            selectAggregate = "${rootFieldNames.selectAggregate}",
            insert =  "${rootFieldNames.insert}",
            insertOne = "${rootFieldNames.insertOne}",
            update = "${rootFieldNames.update}",
            updateByPk = "${rootFieldNames.updateByPk}",
            delete = "${rootFieldNames.delete}",
            deleteByPk = "${rootFieldNames.deleteByPk}",
        )
    }


    /**
     * Calculates all kinds of ManyToManyEntity related values
     */
    internal fun ManyToManyEntity.toM2MData(field: Field, join: BasicCollectionPersister) : M2MData
    {
        val tableName = join.tableName
        var entityName = tableName.toCase(CaseFormat.CAPITALIZED_CAMEL);
        var keyColumn = join.keyColumnNames[0]
        var keyColumnType = join.keyType
        var keyColumnAlias = keyColumn.toCamelCase();
        var relatedColumnName = join.elementColumnNames[0]
        var relatedColumnType = join.elementType
        var relatedColumnNameAlias = relatedColumnName.toCamelCase()

        tableNames.add(tableName)

        // Get the HasuraAlias and may reset entityName
        var alias = field.getAnnotation(HasuraAlias::class.java)
        var rootFields = if(alias != null) alias.rootFieldAliases else null
        if (rootFields != null) {
            if (rootFields.baseName.isNotBlank()) {
                entityName = rootFields.baseName
            }
        }
        if (alias != null) {
            if (alias.keyColumnAlias.isNotBlank()) {
                keyColumnAlias = alias.keyColumnAlias
            }
            else if (alias.joinColumnAlias.isNotBlank()) {
                keyColumnAlias = alias.joinColumnAlias
            }

            if (alias.relatedColumnAlias.isNotBlank()) {
                relatedColumnNameAlias = alias.relatedColumnAlias
            }
            else if (alias.inverseJoinColumnAlias.isNotBlank()) {
                relatedColumnNameAlias = alias.inverseJoinColumnAlias
            }
        }

        // Remove inner $ from the name of inner classes
        entityName = entityName.replace("\\$".toRegex(), "")
        // Copy
        var entityNameLower = entityName.toString()
        entityNameLower = Character.toLowerCase(entityNameLower[0]).toString() + entityNameLower.substring(1)

        // arrayRel only allows accessing the join table ID fields. Now add navigation to the
        // related entity
        val relatedTableName = (join.elementType as ManyToOneType).getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?).tableName;
        var joinFieldName = relatedTableName.toCamelCase();
        if (alias != null && alias.joinFieldAlias.isNotBlank()) {
            joinFieldName = alias.joinFieldAlias;
        }

        val classMetadata = metaModel.entityPersister(this.entity.javaType.typeName) as AbstractEntityPersister
        val keyTableName = classMetadata.tableName
        val keyFieldName = keyTableName.toCamelCase()

        return M2MData(
            join,
            field,
            tableName,
            entityName,
            entityNameLower,
            keyColumn,
            keyColumnAlias,
            keyColumnType,
            relatedColumnName,
            relatedColumnNameAlias,
            relatedColumnType,
            joinFieldName,
            relatedTableName,
            keyFieldName,
            rootFields
        )
    }

    /**
     * Configures a ManyToManyEntity. Such entities have no Java representation but nevertheless we
     * generate configuration for these join tables. These tables have two side and usually accessed from both
     * ends. The difficulty to manage them is that we need to generate the object relationship for both sides
     * and also generate JSON Schema for both sides while we need to generate the common parts once. The way
     * we do it is that for the common part (custom_root_fields, custom_column_names) we generate M2MDate for
     * ManyToManyEntity.field1 and ManyToManyEntity.join1. The @HasuraAlias on such join entities should be
     * declared the same on both side (except for the joinFieldAlias), so it doesn't really matter for the
     * common definitions which join side is field1/join1 or field2/join2. Now, once the common parts are
     * defined we need to generate the object relationship for the two sides. For side1 we reuse the
     * M2MData created for the common parts, and for field2/join2 we generate a new M2MData.
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun configureManyToManyEntity(m2m: ManyToManyEntity) : TableEntry
    {
        // Generate a object_relationships element and also add necessary HasuraSpecPropValues
        // and JoinType to jsonSchemaGenerator using this m2mData
        fun addObjRel(m2mData: M2MData, list: MutableList<ObjectRelationship>) {
            with(m2mData) {
                with(list) {
                    add(
                        ObjectRelationship(
                            name = joinFieldName,
                            using = ObjRelUsing(
                                foreignKeyConstraintOn = relatedColumnName
                            )
                        )
                    )
                }

                val rootFieldNames = generateRootFieldNames(rootFields, entityName, entityNameLower, tableName)
                val classMetadata = metaModel.entityPersister(m2m.entity.javaType.typeName) as AbstractEntityPersister

                jsonSchemaGenerator.addSpecValue(field,
                    HasuraSpecPropValues(
                        relation = "many-to-many",
                        type = tableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                        reference = relatedColumnNameAlias,
                        parentReference = keyColumnAlias,
                        item = joinFieldName)
                )

                jsonSchemaGenerator.addJoinType(JoinType(
                    name = tableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                    tableName = tableName,
                    fromIdName = keyColumnAlias,
                    fromIdType = jsonSchemaTypeFor(join.keyType, classMetadata),
                    fromIdGraphqlType = graphqlTypeFor(metaModel, join.keyType, classMetadata),
                    fromAccessor = keyFieldName,
                    fromAccessorType = m2m.entity.name,
                    toIdName = relatedColumnNameAlias,
                    toIdType = jsonSchemaTypeFor(relatedColumnType, classMetadata),
                    toIdGraphqlType = graphqlTypeFor(metaModel, relatedColumnType, classMetadata),
                    toAccessor = joinFieldName,
                    toAccessorType = relatedTableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                    orderField = join.indexColumnNames?.let {
                        it[0].toCamelCase()
                    },
                    orderFieldType = join.indexColumnNames?.let {
                        jsonSchemaTypeFor(join.indexType, classMetadata)
                    },
                    orderFieldGraphqlType = join.indexColumnNames?.let {
                        graphqlTypeFor(metaModel, join.indexType, classMetadata)
                    },
                    rootFieldNames = rootFieldNames
                ))
            }
        }

        // Get M2MData for the common definition parts
        var m2mData = m2m.toM2MData(m2m.field1, m2m.join1)

        val permissions = permissionAnnotationProcessor.process(m2mData)
        val table = TableEntry(
            table = actualQualifiedTable(schemaName, m2mData.tableName),
            configuration = let {
                with(m2mData) {
                    val rootFieldNames = generateRootFieldNames(rootFields, entityName, entityNameLower, tableName)

                    TableConfig(
                        customRootFields = configureCustomRootFields(rootFieldNames),
//                        customColumnNames = buildMap {
//                            put(keyColumn, keyColumnAlias)
//                            put(relatedColumnName, relatedColumnNameAlias)
//                            // Add index columns names, ie. @OrderColumns
//                            m2m.join1.indexColumnNames?.forEach {
//                                put(it, it.toCamelCase())
//                            }
//                            if (m2m.join2 != null) {
//                                m2m.join2!!.indexColumnNames?.forEach {
//                                    put(it, it.toCamelCase())
//                                }
//                            }
//                        },
                        columnConfig = buildMap {
                            put(keyColumn, ColumnConfigValue(keyColumnAlias))
                            put(relatedColumnName, ColumnConfigValue(relatedColumnNameAlias))
                            // Add index columns names, ie. @OrderColumns
                            m2m.join1.indexColumnNames?.forEach {
                                put(it, ColumnConfigValue(it.toCamelCase()))
                            }
                            if (m2m.join2 != null) {
                                m2m.join2!!.indexColumnNames?.forEach {
                                    put(it, ColumnConfigValue(it.toCamelCase()))
                                }
                            }
                        }


                    )
                }
            },
            objectRelationships = buildList {
                addObjRel(m2mData, this)
                val joinFieldName1 = m2mData.joinFieldName
                // Add for field2
                if (m2m.field2 != null) {
                    val revM2mData = m2m.toM2MData(m2m.field2!!, m2m.join2!!)
                    if (revM2mData.joinFieldName == joinFieldName1) {
                        LOG.warn("Many-to-many table '${revM2mData.tableName}' recursively joins the same table (${revM2mData.relatedTableName}). Consider using @HasuraAlias(..., joinFieldAlias=\"\") on both ends of the relationship on fields: '${m2m.field1}' and '${m2m.field2}'" )
                        revM2mData.joinFieldName += "2"
                    }
                    addObjRel(revM2mData, this)
                }
            },
            insertPermissions = configurePermission(permissions, HasuraOperation.INSERT),
            selectPermissions = configurePermission(permissions, HasuraOperation.SELECT),
            updatePermissions = configurePermission(permissions, HasuraOperation.UPDATE),
            deletePermissions = configurePermission(permissions, HasuraOperation.DELETE),
        )
        return table
    }

    private fun processProperties(relatedEntities: List<EntityType<*>>, processor: (params: ProcessorParams) -> Unit)
    {
        relatedEntities.forEach { anEntity ->
            val classMetadata = metaModel.entityPersister(anEntity.javaType.typeName) as AbstractEntityPersister
            val propNames = classMetadata.propertyNames
            for (propName in propNames) {
                // Handle @HasuraIgnoreRelationship annotation on field
                val f = Utils.findDeclaredFieldUsingReflection(anEntity.javaType, propName)
                if (f!!.isAnnotationPresent(HasuraIgnoreRelationship::class.java)) {
                    continue
                }

                var finalPropName = propName

                // Only look for simple (non component types)
                val type = classMetadata.toType(propName)
                if (type is ComponentType) {
                    val componentType = type
                    val tup = type.componentTuplizer
                    val columnNames = classMetadata.getPropertyColumnNames(propName)
                    columnNames.forEachIndexed { index, columnName ->
                        val field = tup.getGetter(index).getMember()
                        if (field is Field) {
                            // Reset propName to the embedded field name
                            finalPropName = field.name
                            // Now we may alias field name according to @HasuraAlias annotation
                            var hasuraAlias = f.getAnnotation(HasuraAlias::class.java)
                            if (hasuraAlias != null && hasuraAlias.fieldAlias.isNotBlank()) {
                                finalPropName = hasuraAlias.fieldAlias
                            }

                            val columnType = componentType.subtypes[index]
                            //println("$columnName --> ${field.name}")
                            processor(ProcessorParams(anEntity, classMetadata, field, columnName, columnType, finalPropName))
                        }
                        else {
                            throw HasuraConfiguratorException("Unable to handle embeded class ${anEntity} and getter ${field} ")
                        }
                    }
                }
                else {
                    // Now we may alias field name according to @HasuraAlias annotation
                    var hasuraAlias = f.getAnnotation(com.beepsoft.hasuraconf.annotation.HasuraAlias::class.java)
                    if (hasuraAlias != null && hasuraAlias.fieldAlias.isNotBlank()) {
                        finalPropName = hasuraAlias.fieldAlias
                    }

                    val columnName = classMetadata.getPropertyColumnNames(propName)[0]
                    val columnType = classMetadata.getPropertyType(propName)
                    processor(ProcessorParams(anEntity, classMetadata, f, columnName, columnType, finalPropName))
                }
            }
        }

    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun configureCustomColumnNamesMap(relatedEntities: List<EntityType<*>>): Map<String, String>
    {
        val customColumnNames = buildMap<String, String> {
            processProperties(relatedEntities) { params ->
                if (!params.columnType.isAssociationType && params.propName != params.columnName) {
                    put(params.columnName, params.propName)
                }
                // Special case: for many-to-one or one-to-one, generate alias for the ID fields as well
                if (params.columnType.isAssociationType && !params.columnType.isCollectionType) {
                    val assocType = params.columnType as AssociationType
                    val fkDir = assocType.foreignKeyDirection
                    if (fkDir === ForeignKeyDirection.FROM_PARENT) {
                        // Also add customization for the ID field name
                        val camelCasedIdName = CaseUtils.toCamelCase(params.columnName, false, '_')
                        put(params.columnName, camelCasedIdName)
                    }
                }

            }
        }
        return customColumnNames
    }

    private fun configureCustomColumnConfigs(relatedEntities: List<EntityType<*>>): Map<String, ColumnConfigValue>
    {
        val customColumnConfigs = buildMap<String, ColumnConfigValue> {
            processProperties(relatedEntities) { params ->
                if (!params.columnType.isAssociationType && params.propName != params.columnName) {
                    put(params.columnName, ColumnConfigValue(params.propName))
                }
                // Special case: for many-to-one or one-to-one, generate alias for the ID fields as well
                if (params.columnType.isAssociationType && !params.columnType.isCollectionType) {
                    val assocType = params.columnType as AssociationType
                    val fkDir = assocType.foreignKeyDirection
                    if (fkDir === ForeignKeyDirection.FROM_PARENT) {
                        // Also add customization for the ID field name
                        val camelCasedIdName = CaseUtils.toCamelCase(params.columnName, false, '_')
                        put(params.columnName, ColumnConfigValue(camelCasedIdName))
                    }
                }

            }
        }
        return customColumnConfigs
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun configureObjectRelationships(relatedEntities: List<EntityType<*>>): List<ObjectRelationship>?
    {
        val objectRelationships = buildList<ObjectRelationship> {
            val added = mutableSetOf<String>()
            processProperties(relatedEntities) { params ->
                if (added.contains(params.propName)) {
                    return@processProperties
                }
                added.add(params.propName)
                if (params.columnType.isAssociationType && !params.columnType.isCollectionType) {
                    val assocType = params.columnType as AssociationType
                    val fkDir = assocType.foreignKeyDirection
                    if (fkDir === ForeignKeyDirection.FROM_PARENT) {
                        // may need join.tableName?
                        val join = assocType.getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?)
                        add(
                            ObjectRelationship(
                                name = params.propName,
                                using = ObjRelUsing(
                                    foreignKeyConstraintOn = params.columnName
                                )
                            )
                        )
                        val camelCasedIdName = CaseUtils.toCamelCase(params.columnName, false, '_')
                        jsonSchemaGenerator.addSpecValue(params.entity.javaType,
                            HasuraSpecTypeValues(
                                referenceProp = HasuraReferenceProp(name=camelCasedIdName, type=jsonSchemaTypeFor(params.columnType, params.classMetadata)),
                                rootFieldNames = EmptyRootFieldNames
                            ))

                        if (assocType is ManyToOneType && !assocType.isLogicalOneToOne) {
                            jsonSchemaGenerator.addSpecValue(params.field,
                                HasuraSpecPropValues(relation = "many-to-one",
                                    reference = camelCasedIdName,
                                    referenceType = jsonSchemaTypeFor(params.columnType, params.classMetadata)
                                )
                            )
                        }
                        else {
                            jsonSchemaGenerator.addSpecValue(params.field,
                                HasuraSpecPropValues(relation = "one-to-one",
                                    reference = camelCasedIdName,
                                    referenceType = jsonSchemaTypeFor(params.columnType, params.classMetadata)
                                )
                            )
                        }
                    }
                    else { // TO_PARENT, ie. assocition is mapped by the other side
                        val join = assocType.getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?)
                        val keyColumn = join.keyColumnNames[0]
                        val mappedBy = assocType.rhsUniqueKeyPropertyName
                        // In reality mappedBy should always be set. It is either the implicit name or a specific
                        // mappedBy=<foreign property name> and so it cannot be null. I'm not sure about it so I
                        // leave here a fallback of the default Hiberante tableName_keyColumn foreign key.
                        val mappedId = if (mappedBy != null) (join as AbstractEntityPersister).getPropertyColumnNames(mappedBy)[0]
                        else "${params.classMetadata.tableName}_${keyColumn}"
                        //val qualName = actualQualifiedTable(schemaName, join.tableName)
                        add(
                            ObjectRelationship(
                                name = params.propName,
                                using = ObjRelUsing(
                                    manualConfiguration = ObjRelUsingManualMapping(
                                        remoteTable = TableName.QualifiedTableValue(actualQualifiedTable(schemaName, join.tableName)),
                                        columnMapping = buildMap {
                                            put(keyColumn, mappedId)
                                        }
                                    )
                                )
                            )
                        )
                        val oneToOne = params.field.getAnnotation(OneToOne::class.java)
                        oneToOne?.let {
                            jsonSchemaGenerator.addSpecValue(params.field,
                                HasuraSpecPropValues(relation="one-to-one", mappedBy=oneToOne.mappedBy))

                        }

                    }
                }
            }
        }
        return if (objectRelationships.isNotEmpty()) objectRelationships else null
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun configureArrayRelationships(relatedEntities: List<EntityType<*>>): List<ArrayRelationship>?
    {
        val arrayRelationships = buildList<ArrayRelationship> {
            val added = mutableSetOf<String>()
            processProperties(relatedEntities) { params ->
                if (added.contains(params.propName)) {
                    return@processProperties
                }
                added.add(params.propName)
                if (params.columnType.isCollectionType) {
                    val collType = params.columnType as CollectionType
                    val join = collType.getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?)
                    val keyColumn = join.keyColumnNames[0]
                    extraTableNames.add(join.tableName)

                    //val qualName = actualQualifiedTable(schemaName, join.tableName)
                    add(
                        ArrayRelationship(
                            name = params.propName,
                            using = ArrRelUsing(
                                foreignKeyConstraintOn = ArrRelUsingFKeyOn(
                                    column = keyColumn,
                                    table = TableName.QualifiedTableValue(actualQualifiedTable(schemaName, join.tableName))
                                )
                            )
                        )
                    )

                    // BasicCollectionPersister - despite the name - is for many-to-many associations
                    if (join is BasicCollectionPersister) {
                        if (join.isManyToMany) {
                            // Collect many-to-many join tables and process them later
                            // If there's already a ManyToManyEntity for this table, then we just found the
                            // other end of the join, add this as join2
                            val existing = manyToManyEntities.get(join.tableName)
                            if (existing != null) {
                                existing.join2 = join
                                existing.field2 = params.field
                            }
                            else {
                                manyToManyEntities.put(join.tableName, ManyToManyEntity(params.entity, join, null, params.field, null))

                            }
                        }
                    }
                    if (params.field.isAnnotationPresent(OneToMany::class.java)) {
                        val oneToMany = params.field.getAnnotation(OneToMany::class.java)
                        val parentRef = CaseUtils.toCamelCase(keyColumn, false, '_')
                        jsonSchemaGenerator.addSpecValue(params.field,
                            HasuraSpecPropValues(
                                relation="one-to-many",
                                mappedBy=oneToMany.mappedBy,
                                parentReference=parentRef))

                    }
                }
            }
        }
        return if (arrayRelationships.isNotEmpty()) arrayRelationships else null
    }

    private fun collectCascadeDeleteCandidates(entity: EntityType<*>) {
        val entityClass = entity.javaType
        val classMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val propertyNames = classMetadata.propertyNames
        for (propertyName in propertyNames) {
            val f = Utils.findDeclaredFieldUsingReflection(entityClass, propertyName)
            if (f!!.isAnnotationPresent(HasuraGenerateCascadeDeleteTrigger::class.java)) {
                val fieldMetadata = metaModel.entityPersister(f.type.typeName) as AbstractEntityPersister
                val cdf = CascadeDeleteFieldConfig(
                    classMetadata.tableName,
                    schemaName,
                    classMetadata.getPropertyColumnNames(propertyName)[0],
                    fieldMetadata.tableName,
                    schemaName,
                    buildJsonObject { /* dummy */})
                cascadeDeleteFieldConfigs.add(cdf)
            }
        }
        configureCascadeDeleteTriggers(cascadeDeleteFieldConfigs)
    }

    private fun jsonSchemaTypeFor(columnType: Type, classMetadata: AbstractEntityPersister): String {

        fun toJsonSchmeType(actualType: Type) : String
        {
            if (actualType is LongType || actualType is IntegerType || actualType is ShortType
                || actualType is BigDecimalType || actualType is BigIntegerType) {
                return "integer"
            }
            else if (actualType is StringType) {
                return "string";
            }
            return "<UNKNOWN TYPE>";
        }

        if (columnType is ManyToOneType) {
            val refType = columnType.getIdentifierOrUniqueKeyType(classMetadata.factory)
            return toJsonSchmeType(refType)
        }
        // TODO:
        else {
            return toJsonSchmeType(columnType)
        }
    }

    private fun generateRootFieldNames(
        rootFields: HasuraRootFields?,
        entityName: String,
        entityNameLower: String,
        tableName: String) : RootFieldNames
    {
        val rootFieldNames = RootFieldNames(
            select = "${if (rootFields != null && rootFields.select.isNotBlank()) rootFields.select else rootFieldNameProvider.rootFieldFor("select", entityName, entityNameLower, tableName)}",
            selectByPk = "${if (rootFields != null && rootFields.selectByPk.isNotBlank()) rootFields.selectByPk else rootFieldNameProvider.rootFieldFor("selectByPk", entityName, entityNameLower, tableName)}",
            selectAggregate = "${if (rootFields != null && rootFields.selectAggregate.isNotBlank()) rootFields.selectAggregate else rootFieldNameProvider.rootFieldFor("selectAggregate", entityName, entityNameLower, tableName)}",
            insert = "${if (rootFields != null && rootFields.insert.isNotBlank()) rootFields.insert else rootFieldNameProvider.rootFieldFor("insert", entityName, entityNameLower, tableName)}",
            insertOne =  "${if (rootFields != null && rootFields.insertOne.isNotBlank()) rootFields.insertOne else rootFieldNameProvider.rootFieldFor("insertOne", entityName, entityNameLower, tableName)}",
            update = "${if (rootFields != null && rootFields.update.isNotBlank()) rootFields.update else rootFieldNameProvider.rootFieldFor("update", entityName, entityNameLower, tableName)}",
            updateByPk = "${if (rootFields != null && rootFields.updateByPk.isNotBlank()) rootFields.updateByPk else rootFieldNameProvider.rootFieldFor("updateByPk", entityName, entityNameLower, tableName)}",
            delete = "${if (rootFields != null && rootFields.delete.isNotBlank()) rootFields.delete else rootFieldNameProvider.rootFieldFor("delete", entityName, entityNameLower, tableName)}",
            deleteByPk = "${if (rootFields != null && rootFields.deleteByPk.isNotBlank()) rootFields.deleteByPk else rootFieldNameProvider.rootFieldFor("deleteByPk", entityName, entityNameLower, tableName)}"
        )

        // If the original field name was plural then we may have name clashes.
        // Eg. for a class named BookSeries we would have bookSeries for both select and selectByPk.
        // We fix that by adding an "es" to the plural operations.
        if (rootFieldNames.select == rootFieldNames.selectByPk) {
            LOG.warn("Generated plural name for select and selectByPk names '${rootFieldNames.select}' clash, "+
                    "adding '-es' postfix to 'select'. " +
                    "Consider using @HasuraRootFields on class ${entityName}")
            rootFieldNames.select += "es"
        }
        if (rootFieldNames.insert == rootFieldNames.insertOne) {
            LOG.warn("Generated plural name for insert and insertOne names '${rootFieldNames.insert}' clash, "+
                    "adding '-es' postfix to 'insert'. " +
                    "Consider using @HasuraRootFields on class ${entityName}")
            rootFieldNames.insert += "es"
        }
        if (rootFieldNames.update == rootFieldNames.updateByPk) {
            LOG.warn("Generated plural name for update and updateByPk names '${rootFieldNames.update}' clash, "+
                    "adding '-es' postfix to 'update'. " +
                    "Consider using @HasuraRootFields on class ${entityName}")
            rootFieldNames.update += "es"
        }
        if (rootFieldNames.delete == rootFieldNames.deleteByPk) {
            LOG.warn("Generated plural name for delete and deleteByPk names '${rootFieldNames.delete}' clash, "+
                    "adding '-es' postfix to 'delete'. " +
                    "Consider using @HasuraRootFields on class ${entityName}")
            rootFieldNames.delete += "es"
        }

        return rootFieldNames;
    }

    fun  replaceMetadata(conf: HasuraConfiguration) : String {
        return executeMetadataApi(conf.toReplaceMetadata().toString())
    }

    fun  replaceMetadata(metadata: HasuraMetadataV3)  : String {
        return executeMetadataApi(metadata.toReplaceMetadata().toString())
    }

    fun  clearMetadata()  : String {
        return executeMetadataApi(buildJsonObject {
            put("type", "clear_metadata")
            putJsonObject("args") {}
        })
    }

    fun  replaceMetadata(metadataJson: String, allowInconsistent: Boolean = false) : String {
        return executeHasuraApi(buildJsonObject {
            put("type", "replace_metadata")
            put("version", 2)
            putJsonObject("args") {
                put("allow_inconsistent_metadata", allowInconsistent)
                put("metadata", Json.decodeFromString(metadataJson))
            }
        }.toString(), hasuraMetadataEndpoint)
    }

    fun  loadBulkRunSqls(conf: HasuraConfiguration) : String {
        return executeHasuraApi(conf.toBulkRunSql().toString(), hasuraSchemaEndpoint)
    }

    fun  loadBulkRunSqls(json: String) : String {
        return executeSchemaApi(json)
    }

    fun exportMetadata() : HasuraMetadataV3 {
        // TODO: not a nice soltuion, but https://github.com/hasura/graphql-engine/blob/a89cc61039aa611c6ab04e1bc231c2dfa22ca656/contrib/metadata-types/generated/HasuraMetadataV3.json
        // is actually not complete and does not contain the scope field. So we need to ignoreUnknownKeys otheriwse we get:
        // Caused by: kotlinx.serialization.json.internal.JsonDecodingException: Unexpected JSON token at offset 38246: Encountered an unknown key 'scope' at path: $.allowlist[0].collection
        //Use 'ignoreUnknownKeys = true' in 'Json {}' builder to ignore unknown keys.
        //JSON input: .....ollection":"allowed-queries","scope":{"global":true}}],"acti.....
        val json = Json {
            if (ignoreUnkonwFieldsInExportedMetadata) {
                ignoreUnknownKeys = true // This will ignore unknown fields in the JSON
            }
        }
        return json.decodeFromString<HasuraMetadataV3>(exportMetadataJson())
    }

     fun exportMetadataJson() : String {
        return executeMetadataApi("""{"type": "export_metadata", "args": {}}""")
    }

    fun replaceConfiguration(conf: HasuraConfiguration) : List<String> {
        return buildList {
            conf.toBulkRunSql()?.let {
                add(loadBulkRunSqls(conf.toBulkRunSql().toString()))
            }
            add(replaceMetadata(conf.metadata))
        }
    }

    fun executeMetadataApi(operation: JsonObject) : String {
        return executeHasuraApi(operation, hasuraMetadataEndpoint)
    }

    fun executeMetadataApi(operation: String) : String {
        return executeHasuraApi(operation, hasuraMetadataEndpoint)
    }

    @JvmOverloads
    fun executeSchemaApi(operation: JsonObject, safely: Boolean = false) : String {
        if (safely) {
            return executeSchemaApiSafely(operation.toString(), hasuraSchemaEndpoint)
        }
        return executeHasuraApi(operation, hasuraSchemaEndpoint)
    }

    @JvmOverloads
    fun executeSchemaApi(operation: String, safely: Boolean = false) : String {
        if (safely) {
            return executeSchemaApiSafely(operation, hasuraSchemaEndpoint)
        }
        return executeHasuraApi(operation, hasuraSchemaEndpoint)
    }

    private fun executeHasuraApi(json: JsonObject, endpoint: String) : String {
        return executeHasuraApi(json.toString(), endpoint)
    }

    private fun executeHasuraApi(json: String, endpoint: String) : String {
        val client = WebClient
            .builder()
            .baseUrl(endpoint)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-Hasura-Admin-Secret", hasuraAdminSecret)
            .build()
        val request = client.post()
            .body<String, Mono<String>>(Mono.just(json), String::class.java)
            .retrieve()
            .bodyToMono(String::class.java)
        // Make it synchronous for now
        try {
            val result = request.block()
            LOG.debug("executeHasuraApi done {}", result)
            return result!!
        } catch (ex: WebClientResponseException) {
            LOG.error("executeHasuraApi failed", ex)
            LOG.error("executeHasuraApi response text: {}", ex.responseBodyAsString)
            throw ex
        }
    }

    //
    // Safe SQL / schema api execution with possible error handling
    //

    private fun executeSchemaApiSafely(staticConf: String, endpoint: String): String {
        val confJson = Json.parseToJsonElement(staticConf) as JsonObject
        var loadSeparately = false
        if ((confJson["type"] as JsonPrimitive).content == "bulk" &&
            confJson.containsKey("hasuraconfLoadSeparately") && (confJson["hasuraconfLoadSeparately"] as JsonPrimitive).boolean == true) {
            loadSeparately = true
        }

        // If loading separately then args must be an array of operations that we execute separately
        if (loadSeparately) {
            val args = confJson.get("args") as JsonArray
            var res = ""
            for (o in args) {
                res += "\n"+doExecuteSchemaApiSafely(o as JsonObject, endpoint)
            }
            return res
        } else {
            return doExecuteSchemaApiSafely(confJson, endpoint)
        }
        return executeHasuraApi(confJson, endpoint)
    }

    private fun doExecuteSchemaApiSafely(confJson: JsonObject, endpoint: String): String {
        LOG.info("Executing static Hasura initialization JSON:")
        LOG.info(confJson.toString())
        val client = WebClient
            .builder()
            .baseUrl(endpoint)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-Hasura-Admin-Secret", hasuraAdminSecret)
            .build()
        // Make it synchronous for now
        var result: String = ""
        try {
            result = client.post().body(Mono.just<String>(Json.encodeToString(confJson)), String::class.java)
                .retrieve().bodyToMono(String::class.java).block()!!
            LOG.info("Static Hasura initialization done {}", result)
            return result
        } catch (ex: WebClientResponseException) {

            // Check if we received an "expected" error, ie. one that matched the error defined in the
            // confJson's diwasIgnoreError field. If we have a match, we ignore this error. It must be an error
            // which is eg. about some value already set. In this case we can ignore this error safely.
            if (canIgnoreError(confJson, Json.parseToJsonElement(ex.responseBodyAsString) as JsonObject)) {
                LOG.info("Static Hasura configuration had error, but can be ignored: {}", ex.responseBodyAsString)
                return result
            }
            LOG.error("Hasura configuration failed", ex)
            LOG.error("Response text: {}", ex.responseBodyAsString)
            throw ex
        } finally {
        }
    }

    private fun canIgnoreError(confJson: JsonObject, errorJson: JsonObject): Boolean {
        // Set to true by default. If a value is set for any of these we will evaluate those only
        var statusCodeMatches = true
        var messageMatches = true
        var descriptionMatches = true
        var attemptToIgnoreError = false
        if (confJson.containsKey("hasuraconfIgnoreError")) {
            attemptToIgnoreError = true
            val obj = confJson.get("hasuraconfIgnoreError")
            if (obj is JsonObject) {
                return canIgnoreErrorWithHasuraconfIgnoreError(obj as JsonObject, errorJson)
            } else {
                for (o in obj as JsonArray) {
                    if (canIgnoreErrorWithHasuraconfIgnoreError(o as JsonObject, errorJson)) {
                        return true
                    }
                }
            }
            return false
        }
        if (confJson.containsKey("hasuraconfIgnoreErrorStatusCode")) {
            attemptToIgnoreError = true
            statusCodeMatches = isErrorFieldMatching("statusCode", (confJson.get("hasuraconfIgnoreErrorStatusCode") as JsonPrimitive).content, errorJson)
        }
        if (confJson.containsKey("hasuraconfIgnoreErrorMessage")) {
            attemptToIgnoreError = true
            messageMatches = isErrorFieldMatching("message", (confJson.get("hasuraconfIgnoreErrorMessage") as JsonPrimitive).content, errorJson)
        }
        if (confJson.containsKey("hasuraconfIgnoreErrorDescription")) {
            attemptToIgnoreError = true
            descriptionMatches = isErrorFieldMatching("description", (confJson.get("hasuraconfIgnoreErrorDescription") as JsonPrimitive).content, errorJson)
        }
        return statusCodeMatches && messageMatches && descriptionMatches && attemptToIgnoreError
    }

    private fun canIgnoreErrorWithHasuraconfIgnoreError(ignoreErrorJson: JsonObject, errorJson: JsonObject): Boolean {
        var statusCodeMatches = true
        val messageMatches = true
        val descriptionMatches = true
        var attemptToIgnoreError = false
        if (ignoreErrorJson.containsKey("statusCode")) {
            attemptToIgnoreError = true
            statusCodeMatches = isErrorFieldMatching("statusCode", (ignoreErrorJson.get("statusCode") as JsonPrimitive).content, errorJson)
        }
        if (ignoreErrorJson.containsKey("message")) {
            attemptToIgnoreError = true
            statusCodeMatches = isErrorFieldMatching("message", (ignoreErrorJson.get("message") as JsonPrimitive).content, errorJson)
        }
        if (ignoreErrorJson.containsKey("description")) {
            attemptToIgnoreError = true
            statusCodeMatches = isErrorFieldMatching("description", (ignoreErrorJson.get("description")  as JsonPrimitive).content, errorJson)
        }
        return statusCodeMatches && messageMatches && descriptionMatches && attemptToIgnoreError
    }

    private fun isErrorFieldMatching(field: String, value: String, errorJson: JsonObject): Boolean {
        if (errorJson.containsKey("internal") && (errorJson.get("internal") as JsonObject).containsKey("error")) {
            val errorObj = (errorJson.get("internal") as JsonObject).get("error") as JsonObject
            if (field == "description" && (errorObj.get("description") as JsonPrimitive).content == value) {
                return true
            }
            if (field == "statusCode" && (errorObj.get("status_code") as JsonPrimitive).content == value) {
                return true
            }
            if (field == "message" && (errorObj.get("message") as JsonPrimitive).content == value) {
                return true
            }
        } else if (errorJson.containsKey("error")) {
            if (field == "description") {
                return (errorJson.get("error") as JsonPrimitive).content == value
            }
        }
        return false
    }    
}




