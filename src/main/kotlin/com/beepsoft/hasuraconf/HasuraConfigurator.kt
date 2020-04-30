package com.beepsoft.hasuraconf

import com.beepsoft.hasuraconf.annotation.*
import com.google.common.net.HttpHeaders
import net.pearx.kasechange.CaseFormat
import net.pearx.kasechange.toCamelCase
import net.pearx.kasechange.toCase
import org.apache.commons.text.CaseUtils
import org.atteo.evo.inflector.English
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
import java.io.PrintWriter
import java.lang.reflect.Field
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
        var confFile: String?,
        var schemaName: String,
        var loadConf: Boolean,
        var hasuraEndpoint: String,
        var hasuraAdminSecret: String?,
        var schemaFile: String?,
        var schemaVersion: String,
        var customPropsFieldName: String
) {

    companion object {
        // @Suppress("JAVA_CLASS_ON_COMPANION")
        // @JvmStatic
        private val LOG = getLogger(this::class.java.enclosingClass)
    }

    inner class CascadeDeleteFields(var table: String, var field: String, var joinedTable: String)

    var confJson: String? = null
        private set // the setter is private and has the default implementation

    var jsonSchema: String? = null
        private set // the setter is private and has the default implementation

    private var sessionFactoryImpl: SessionFactory
    private var metaModel: MetamodelImplementor
    private var permissionAnnotationProcessor: PermissionAnnotationProcessor

    private lateinit var tableNames: MutableSet<String>
    private lateinit var entityClasses: MutableSet<Class<out Any>>
    private lateinit var enumTables: MutableSet<String>
    private lateinit var cascadeDeleteFields: MutableSet<CascadeDeleteFields>
    private lateinit var jsonSchemaGenerator: HasuraJsonSchemaGenerator

    init {
        sessionFactoryImpl = entityManagerFactory.unwrap(SessionFactory::class.java) as SessionFactoryImpl
        metaModel = sessionFactoryImpl.metamodel as MetamodelImplementor
        permissionAnnotationProcessor = PermissionAnnotationProcessor(entityManagerFactory)
    }
//    @Autowired
//    fun setEntityManagerFactory(entityManagerFactory: EntityManagerFactory) {
//        this.entityManagerFactory = entityManagerFactory
//        sessionFactoryImpl = entityManagerFactory.unwrap(SessionFactory::class.java) as SessionFactoryImpl
//        metaModel = sessionFactoryImpl.metamodel as MetamodelImplementor
//        permissionAnnotationProcessor = PermissionAnnotationProcessor(entityManagerFactory)
//    }

    /**
     * Creates hasura-conf.json containing table tracking and field/relationship name customizations
     * at bean creation time automatically.
     */
    @Throws(HasuraConfiguratorException::class)
    fun configure() {
        confJson = null
        jsonSchema = null
        tableNames = mutableSetOf<String>()
        entityClasses = mutableSetOf<Class<out Any>>()
        enumTables = mutableSetOf<String>()
        cascadeDeleteFields = mutableSetOf<CascadeDeleteFields>()
        jsonSchemaGenerator = HasuraJsonSchemaGenerator(schemaVersion, customPropsFieldName)

        val entities = metaModel.entities
        // Add custom field and relationship names for each table
        val customColumNamesRelationships = StringBuilder()
        val permissions = StringBuilder()
        var added = false
        for (entity in entities) {
            val customFieldNameJSONBuilder = StringBuilder()
            if (added) {
                customColumNamesRelationships.append(",\n")
            }
            customColumNamesRelationships.append(generateEntityCustomization(entity))
            customColumNamesRelationships.append(customFieldNameJSONBuilder)
            added = true
            // If it is an enum class that is mapped with a ManyToOne, then we consider it a Hasura enum table case
            // and will generate set_table_is_enum
            val entityClass = entity.javaType
            if (Enum::class.java.isAssignableFrom(entityClass) && entityClass.isAnnotationPresent(HasuraEnum::class.java)) {
                val classMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
                val tableName = classMetadata.tableName
                enumTables.add(tableName)
            }
            collectCascadeDeleteCandidates(entity)
            val perms = generatePermissions(entity)
            if (perms.length != 0 && permissions.length != 0) {
                permissions.append(",")
            }
            permissions.append(perms)
        }
        // Add tracking for all tables collected, and make these the first calls in the bulk JSON
        // Note: we have to do this in the end since we had to collect all entities and join tables
        val bulk = StringBuilder()
        for (tableName in tableNames) {
            if (bulk.length == 0) {
                bulk.append(
                        """
                        {
                            "type": "bulk",
                            "args": [
                            {"type":"clear_metadata","args":{}},                            
                        """
                )
            } else {
                bulk.append(",\n")
            }
            val trackTableJSON =
                    """
						{
                    		"type": "track_table",
                    		"args": {
                    			"schema": "${schemaName}",
                    			"name": "${tableName}"
                    		}
                    	}                        
                    """
            bulk.append(trackTableJSON)
            // Handle enum table
            if (enumTables.contains(tableName)) {
                val tabelIsEnumJSON =
                        """
							,
							{
                        		"type": "set_table_is_enum",
                        		"args": {
                        			"table": {
                        				"schema": "${schemaName}",
                        				"name": "${tableName}"
                         			},
                        			"is_enum": true
                        		}
                        	}                            
                        """
                bulk.append(tabelIsEnumJSON)
            }
        }
        if (customColumNamesRelationships.length != 0) {
            bulk.append(",\n")
            bulk.append(customColumNamesRelationships)
        }
        if (permissions.length != 0) {
            bulk.append(",\n")
            bulk.append(permissions)
        }
        val triggers = generateCascadeDeleteTriggers()
        if (triggers.length != 0) {
            bulk.append(",\n")
            bulk.append(triggers)
        }
        bulk.append("\n\t]\n")
        bulk.append("}\n")

        jsonSchema = jsonSchemaGenerator.generateSchema(*entityClasses.toTypedArray()).toString().reformatJson()
        schemaFile?.let {
            PrintWriter(it).use { out -> out.println(jsonSchema) }
        }

        confJson = bulk.toString().reformatJson()
        confFile?.let {
            PrintWriter(it).use { out -> out.println(confJson) }
            if (loadConf) {
                loadConfScriptIntoHasura()
            }

        }

    }

    private fun collectCascadeDeleteCandidates(entity: EntityType<*>) {
        val entityClass = entity.javaType
        val classMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val propertyNames = classMetadata.propertyNames
        for (propertyName in propertyNames) {
            val f = Utils.findDeclaredFieldUsingReflection(entityClass, propertyName)
            if (f!!.isAnnotationPresent(HasuraGenerateCascadeDeleteTrigger::class.java)) {
                val fieldMetadata = metaModel.entityPersister(f.type.typeName) as AbstractEntityPersister
                val cdf = CascadeDeleteFields(classMetadata.tableName, classMetadata.getPropertyColumnNames(propertyName)[0], fieldMetadata.tableName)
                cascadeDeleteFields.add(cdf)
            }
        }
    }

    private fun generateCascadeDeleteTriggers(): String {
        val sb = StringBuilder()
        for (cdf in cascadeDeleteFields) {
            val template =
                    """
                        DROP TRIGGER IF EXISTS ${cdf.table}_${cdf.field}_cascade_delete_trigger ON ${cdf.table};;
                        DROP FUNCTION  IF EXISTS ${cdf.table}_${cdf.field}_cascade_delete();
                        CREATE FUNCTION ${cdf.table}_${cdf.field}_cascade_delete() RETURNS trigger AS
                        ${'$'}body${'$'}
                        BEGIN
                            IF TG_WHEN <> 'AFTER' OR TG_OP <> 'DELETE' THEN
                                RAISE EXCEPTION '${cdf.table}_${cdf.field}_cascade_delete may only run as a AFTER DELETE trigger';
                            END IF;
                        
                            DELETE FROM ${cdf.joinedTable} where id=OLD.${cdf.field};
                            RETURN OLD;
                        END;
                        ${'$'}body${'$'}
                        LANGUAGE plpgsql;;
                        CREATE TRIGGER ${cdf.table}_${cdf.field}_cascade_delete_trigger AFTER DELETE ON ${cdf.table}
                            FOR EACH ROW EXECUTE PROCEDURE ${cdf.table}_${cdf.field}_cascade_delete();;                       
                    """.trimIndent()
            val trigger = template.replace("\n", " ")
            if (sb.length != 0) {
                sb.append(",\n")
            }
            sb.append(
                """
                    {
                        "type": "run_sql",
                        "args": {
                            "sql": "${trigger}"
                        }
                    } 
                """
            )
        }
        return sb.toString()
    }

    private fun generatePermissions(entity: EntityType<*>): String
    {
        val permissions = permissionAnnotationProcessor.process(entity)

        val permissionJSONBuilder = StringBuilder()
        permissions.forEachIndexed { index, permissionData ->
            if (index > 0) {
                permissionJSONBuilder.append(",")
            }
            permissionJSONBuilder.append(permissionData.toHasuraJson(schemaName))
        }
        return permissionJSONBuilder.toString()
    }

    /**
     * Generates customization for a given `entity`.
     * @param entity
     * @return JSON to initialize the entity in Hasura
     */
    private fun generateEntityCustomization(entity: EntityType<*>): String {
        val classMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val tableName = classMetadata.tableName
        val keyKolumnName = classMetadata.keyColumnNames[0]
        tableNames.add(tableName)
        entityClasses.add(entity.javaType)

        jsonSchemaGenerator.addSpecValue(entity.javaType,
                HasuraSpecTypeValues(
                        typeName=tableName,
                        idProp=keyKolumnName))

        var entityName = entity.name

        // Get the HasuraRootFields and may reset entityName
        var rootFields = entity.javaType.getAnnotation(HasuraRootFields::class.java)
        if (rootFields != null && rootFields.baseName.isNotBlank()) {
            entityName = rootFields.baseName
        }

        val customRootFieldColumnNameJSONBuilder = StringBuilder()
        // Remove inner $ from the name of inner classes
        entityName = entityName.replace("\\$".toRegex(), "")
        // Copy
        var entityNameLower = entityName.toString()
        entityNameLower = Character.toLowerCase(entityNameLower[0]).toString() + entityNameLower.substring(1)

        customRootFieldColumnNameJSONBuilder.append(
                """
                    ${generateSetTableCustomFields(rootFields, tableName, entityName, entityNameLower)}
                    "custom_column_names": {                 
                """
        )
        val customRelationshipNameJSONBuilder = StringBuilder()
        val propNames = classMetadata.propertyNames
        var propAdded = false
        for (propName in propNames) {
            val added = addCustomFieldNameOrRef(entity, propAdded, classMetadata, tableName, propName, customRootFieldColumnNameJSONBuilder, customRelationshipNameJSONBuilder)
            if (propAdded != true && added == true) {
                propAdded = true
            }
        }
        customRootFieldColumnNameJSONBuilder.append(
                "\n\t\t\t}\n"
                        + "\t\t}\n"
                        + "\t}"
        )
        // Add optional relationship renames
        if (customRelationshipNameJSONBuilder.length != 0) {
            customRootFieldColumnNameJSONBuilder.append(",\n")
            customRootFieldColumnNameJSONBuilder.append(customRelationshipNameJSONBuilder)
        }
        return customRootFieldColumnNameJSONBuilder.toString()
    }

    /**
     * Adds a custom field name to `customFieldNameJSONBuilder` or a relationship with a custom name
     * to `customRelationshipNameJSONBuilder`.
     * @param classMetadata
     * @param tableName
     * @param propName
     * @param customFieldNameJSONBuilder
     * @param customRelationshipNameJSONBuilder
     * @return true if a custom field name has been added to `customFieldNameJSONBuilder` or false otherwise
     * (custom field name is not added, or relationship has been added)
     */
    private fun addCustomFieldNameOrRef(
            entity: EntityType<*>,
            propAdded: Boolean,
            classMetadata: AbstractEntityPersister,
            tableName: String,
            propNameIn: String,
            customFieldNameJSONBuilder: StringBuilder,
            customRelationshipNameJSONBuilder: StringBuilder): Boolean
    {
        var propName = propNameIn;

        // Handle @HasuraIgnoreRelationship annotation on field
        val f = Utils.findDeclaredFieldUsingReflection(entity.javaType, propName)
        if (f!!.isAnnotationPresent(HasuraIgnoreRelationship::class.java)) {
            return false
        }

        val columnName = classMetadata.getPropertyColumnNames(propName)[0]
        val columnType = classMetadata.getPropertyType(propName)
        val propType = classMetadata.getPropertyType(propName)

        // Now we may alias field name according to @HasuraAlias annotation
        var hasuraAlias = f.getAnnotation(HasuraAlias::class.java)
        if (hasuraAlias != null && hasuraAlias.fieldAlias.isNotBlank()) {
            propName = hasuraAlias.fieldAlias
        }

        //
        // If it is an association type, add an array or object relationship
        //
        if (propType.isAssociationType) {
            if (customRelationshipNameJSONBuilder.isNotEmpty()) {
                customRelationshipNameJSONBuilder.append(",\n")
            }
            if (propType.isCollectionType) {
                val collType = propType as CollectionType
                val join = collType.getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?)
                val keyColumn = join.keyColumnNames[0]
                tableNames.add(join.tableName)
                val arrayRel =
                        """
                            {
                                "type": "create_array_relationship",
                                "args": {
                                    "name": "${propName}",
                                    "table": {
                                        "name": "${tableName}",
                                        "schema": "${schemaName}"
                                    },
                                    "using": {
                                        "foreign_key_constraint_on": {
                                            "table": {
                                                "name": "${join.tableName}",
                                                "schema": "${schemaName}"
                                            },
                                            "column": "${keyColumn}"
                                        }
                                    }
                                }
                            }                            
                        """
                customRelationshipNameJSONBuilder.append(arrayRel)

                // BasicCollectionPersister - despite the name - is for many-to-many associations
                if (join is BasicCollectionPersister) {
                    if (join.isManyToMany) {
                        var res = handleManyToManyJoinTable(entity, join, f);
                        if (res != null) {
                            customRelationshipNameJSONBuilder.append(
                                    """
                                        ${res}
                                    """
                            )
                        }
                    }
                }

                if (f.isAnnotationPresent(OneToMany::class.java)) {
                    val oneToMany = f.getAnnotation(OneToMany::class.java)
                    jsonSchemaGenerator.addSpecValue(f, entity.javaType,
                            HasuraSpecPropValues(relation="one-to-many", mappedBy=oneToMany.mappedBy))

                }
            } else {
                val assocType = propType as AssociationType
                val fkDir = assocType.foreignKeyDirection
                if (fkDir === ForeignKeyDirection.FROM_PARENT) {
                    val objectRel =
                            """
                                {
                                    "type": "create_object_relationship",
                                    "args": {
                                        "name": "${propName}",
                                        "table": {
                                            "name": "${tableName}",
                                            "schema": "${schemaName}"
                                        },
                                        "using": {
                                            "foreign_key_constraint_on": "${columnName}"
                                        }
                                    }
                                }                                
                            """
                    customRelationshipNameJSONBuilder.append(objectRel)
                    // Also add customization for the ID field name
                    val camelCasedIdName = CaseUtils.toCamelCase(columnName, false, '_')
                    if (propAdded) {
                        customFieldNameJSONBuilder.append(",\n")
                    }
                    customFieldNameJSONBuilder.append("\t\t\t\t\"$columnName\": \"$camelCasedIdName\"")
                    jsonSchemaGenerator.addSpecValue(entity.javaType,
                            HasuraSpecTypeValues(referenceProp =
                            HasuraReferenceProp(name=camelCasedIdName, type=jsonSchemaTypeForReference(columnType, classMetadata))))

                    if (assocType is ManyToOneType && !assocType.isLogicalOneToOne) {
                        jsonSchemaGenerator.addSpecValue(f, entity.javaType,
                                HasuraSpecPropValues(relation = "many-to-one",
                                        reference = camelCasedIdName,
                                        referenceType = jsonSchemaTypeForReference(columnType, classMetadata)
                                )
                        )
                    }
                    else {
                        jsonSchemaGenerator.addSpecValue(f, entity.javaType,
                                HasuraSpecPropValues(relation = "one-to-one",
                                        reference = camelCasedIdName,
                                        referenceType = jsonSchemaTypeForReference(columnType, classMetadata)
                                )
                        )
                    }
                    return true
                } else { // TO_PARENT, ie. assocition is mapped by the other side
                    val join = assocType.getAssociatedJoinable(sessionFactoryImpl as SessionFactoryImpl?)
//                    val keyColumn = join.keyColumnNames[0]
                    val objectRel =
                            """
                                {
                                    "type": "create_object_relationship",
                                    "args": {
                                        "name": "${propName}",
                                        "table": {
                                            "name": "${tableName}",
                                            "schema": "${schemaName}"
                                        },
                                        "using": {
                                            "manual_configuration": {
                                                "remote_table": {
                                                    "name": "${join.tableName}",
                                                    "schema": "${schemaName}"
                                                },
                                                "column_mapping": {
                                                    "id": "${tableName}_id"
                                                }
                                            }
                                        }
                                    }
                                }                                
                            """
                    customRelationshipNameJSONBuilder.append(objectRel)

                    val oneToOne = f.getAnnotation(OneToOne::class.java)
                    oneToOne?.let {
                        jsonSchemaGenerator.addSpecValue(f, entity.javaType,
                                HasuraSpecPropValues(relation="one-to-one", mappedBy=oneToOne.mappedBy))

                    }
                }

            }
        } else if (columnName != propName) {
            if (propAdded) {
                customFieldNameJSONBuilder.append(",\n")
            }
            customFieldNameJSONBuilder.append("\t\t\t\t\"$columnName\": \"$propName\"")
            return true
        }
        return false
    }

    private fun jsonSchemaTypeForReference(columnType: Type, classMetadata: AbstractEntityPersister): String {
        if (columnType is ManyToOneType) {
            val refType = columnType.getIdentifierOrUniqueKeyType(classMetadata.factory)
            if (refType is LongType || refType is IntegerType || refType is ShortType
                    || refType is BigDecimalType || refType is BigIntegerType) {
                return "integer"
            }
            else if (refType is StringType) {
                return "string";
            }
        }
        return "<UNKNOWN TYPE>";
    }

    /**
     * Many-to-many relationships are represented with a joint table, however this table has no Java
     * representation therefore no hasura configuration coul dbe generated for them the usual reflection
     * driven way. Instead we need to generate these based on the
     */
    private fun handleManyToManyJoinTable(entity: EntityType<*>, join: BasicCollectionPersister, field: Field): String?
    {
        val tableName = join.tableName
        var entityName = tableName.toCase(CaseFormat.CAPITALIZED_CAMEL);
        var keyColumn = join.keyColumnNames[0]
        var keyColumnType = join.keyType
        var keyColumnAlias = keyColumn.toCamelCase();
        var relatedColumnName = join.elementColumnNames[0]
        var relatedColumnType = join.elementType
        var relatedColumnNameAlias = relatedColumnName.toCamelCase()

        // Get the HasuraAlias and may reset entityName
        var alias = field.getAnnotation(HasuraAlias::class.java);
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

        val customRootFieldColumnNameJSONBuilder = StringBuilder()
        tableNames.add(tableName)
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

        val classMetadata = metaModel.entityPersister(entity.javaType.typeName) as AbstractEntityPersister
        val keyTableName = classMetadata.tableName
        val keyFieldName = keyTableName.toCamelCase()


        customRootFieldColumnNameJSONBuilder.append(
                """
                    ,
                    {
                        "type": "create_object_relationship",
                        "args": {
                            "name": "${joinFieldName}",
                            "table": {
                                "name": "${join.tableName}",
                                "schema": "${schemaName}"
                            },
                            "using": {
                                "foreign_key_constraint_on": "${relatedColumnName}"
                            }
                        }
                    }
                    ,                    
                    ${generateSetTableCustomFields(rootFields, tableName, entityName, entityNameLower)}
                    "custom_column_names": {
                         "${keyColumn}": "${keyColumnAlias}",
                         "${relatedColumnName}": "${relatedColumnNameAlias}"
                """
        )
        // Add index columns names, ie. @OrderColumns
        join.indexColumnNames?.forEach { customRootFieldColumnNameJSONBuilder.append(
                """
                   ,"${it}": "${it.toCamelCase()}" 
                """
        ) }

        // Add ending curly brackets
        customRootFieldColumnNameJSONBuilder.append(
                """
                            }
                         }
                    }                    
                """
        )

        jsonSchemaGenerator.addSpecValue(field, entity.javaType,
                HasuraSpecPropValues(
                        relation="many-to-many",
                        type=tableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                        reference=relatedColumnNameAlias,
                        parentReference=keyColumnAlias,
                        item=joinFieldName)
                )

        jsonSchemaGenerator.addJoinType(JoinType(
                name = tableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                tableName = tableName,
                fromIdName = keyColumnAlias,
                fromIdType = "integer",
                fromAccessor = keyFieldName,
                fromAccessorType= entity.name,
                toIdName = relatedColumnNameAlias,
                toIdType = "integer",
                toAccessor = joinFieldName,
                toAccessorType = relatedTableName.toCase(CaseFormat.CAPITALIZED_CAMEL),
                orderField = join.indexColumnNames?.let {
                    it[0].toCamelCase()
                },
                orderFieldType = join.indexColumnNames?.let{"orderFieldType"}
        ))

//        println(customFieldNameJSONBuilder)
        return customRootFieldColumnNameJSONBuilder.toString()
    }

    private fun generateSetTableCustomFields(
            rootFields: HasuraRootFields?,
            tableName: String,
            entityName: String,
            entityNameLower: String) : String
    {
        return  """
                    {
                        "type": "set_table_custom_fields",
                        "version": 2,
                        "args": {
                            "table": "${tableName}",
                            "schema": "${schemaName}",
                            "custom_root_fields": {
                                "select": "${if (rootFields != null && rootFields.select.isNotBlank()) rootFields.select else English.plural(entityNameLower)}",
                                "select_by_pk": "${if (rootFields != null && rootFields.selectByPk.isNotBlank()) rootFields.selectByPk else entityNameLower}",
                                "select_aggregate": "${if (rootFields != null && rootFields.selectAggregate.isNotBlank()) rootFields.selectAggregate else entityNameLower+"Aggregate"}",
                                "insert": "${if (rootFields != null && rootFields.insert.isNotBlank()) rootFields.insert else "create"+English.plural(entityName)}",
                                "insert_one": "${if (rootFields != null && rootFields.insertOne.isNotBlank()) rootFields.insertOne else "create"+entityName}",
                                "update": "${if (rootFields != null && rootFields.update.isNotBlank()) rootFields.update else "update"+English.plural(entityName)}",
                                "update_by_pk": "${if (rootFields != null && rootFields.updateByPk.isNotBlank()) rootFields.updateByPk else "update"+entityName}", 
                                "delete": "${if (rootFields != null && rootFields.delete.isNotBlank()) rootFields.delete else "delete"+English.plural(entityName)}",
                                "delete_by_pk": "${if (rootFields != null && rootFields.deleteByPk.isNotBlank()) rootFields.deleteByPk else "delete"+entityName}"
                            },
                """
    }

    /**
     *
     */
    private fun loadConfScriptIntoHasura() {
        LOG.info("Executing Hasura initialization JSON from {}. This may take a while ...", confFile)
        val client = WebClient
                .builder()
                .baseUrl(hasuraEndpoint)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Hasura-Admin-Secret", hasuraAdminSecret)
                .build()
        val request = client.post()
                .body<String, Mono<String>>(Mono.just(confJson!!), String::class.java)
                .retrieve()
                .bodyToMono(String::class.java)
        // Make it synchronous for now
        try {
            val result = request.block()
            LOG.info("Hasura initialization done {}", result)
        } catch (ex: WebClientResponseException) {
            LOG.error("Hasura initialization failed", ex)
            LOG.error("Response text: {}", ex.responseBodyAsString)
            throw ex
        }
        //        result.subscribe(
//                value -> System.out.println(value),
//                error -> error.printStackTrace(),
//                () -> System.out.println("completed without a value")
//        );
    }

}
