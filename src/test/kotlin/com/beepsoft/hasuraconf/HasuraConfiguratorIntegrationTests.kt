package com.beepsoft.hasuraconf

import org.apache.commons.lang3.SystemUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.fail
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono

/**
 * Tests HasuraConfigurator with Postgresql + Hasura
 */
@SpringBootTest(
		// More config in the Initializer
		properties = [
			"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQL94Dialect",
			"spring.datasource.initialization-mode=always",
			"spring.datasource.data=classpath:/sql/postgresql/data_import_values.sql",
			"spring.jpa.hibernate.ddl-auto=update"
			//	"logging.level.org.hibernate=DEBUG"
		],
		classes = [TestApp::class]
)
@ContextConfiguration(initializers = [HasuraConfiguratorIntegrationTests.Companion.Initializer::class])
@Testcontainers
@ExtendWith(SpringExtension::class)
class HasuraConfiguratorIntegrationTests {


	// https://stackoverflow.com/questions/53854572/how-to-override-spring-application-properties-in-test-classes-spring-s-context
	//https://www.baeldung.com/spring-boot-testcontainers-integration-test
	// https://www.baeldung.com/spring-boot-testcontainers-integration-test
	companion object {

		private val LOG = getLogger(this::class.java.enclosingClass)

		var postgresqlContainer: PostgreSQLContainer<*>
		var hasuraContainer: GenericContainer<*>

		init {
			val host = if (SystemUtils.IS_OS_MAC_OSX || SystemUtils.IS_OS_WINDOWS) "host.docker.internal" else "172.17.0.1"

			println("Hasura connecting to host $host")
			val logConsumer = Slf4jLogConsumer(LOG)
			postgresqlContainer = PostgreSQLContainer<Nothing>("postgres:11.5-alpine").
				apply {
					withUsername("hasuraconf")
					withPassword("hasuraconf")
					withDatabaseName("hasuraconf")
				}
			postgresqlContainer.start()
			postgresqlContainer.followOutput(logConsumer)

			hasuraContainer = GenericContainer<Nothing>("hasura/graphql-engine:v1.3.3")
				.apply {
					//dependsOn(postgresqlContainer)
					val postgresUrl = "postgres://hasuraconf:hasuraconf@${host}:${postgresqlContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/hasuraconf"
					val jdbc = postgresqlContainer.getJdbcUrl()
					println("jdbc $jdbc")
					println("postgresUrl $postgresUrl")
					withExposedPorts(8080)
					withEnv(mapOf(
							"HASURA_GRAPHQL_DATABASE_URL" to postgresUrl,
							"HASURA_GRAPHQL_ACCESS_KEY" to "hasuraconf",
							"HASURA_GRAPHQL_STRINGIFY_NUMERIC_TYPES" to "true"
					))
				}
			hasuraContainer.start()
			hasuraContainer.followOutput(logConsumer)
		}

		// This would be the default use of the container, however it doesn't account for dependencies among them
//		@Container
//		@JvmField
//		val postgresqlContainer: PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:11.5-alpine").
//				apply {
//					withUsername("hasuraconf")
//					withPassword("hasuraconf")
//					withDatabaseName("hasuraconf")
//					withExposedPorts(5432)
//				}
//
//		// https://github.com/testcontainers/testcontainers-java/issues/318
//		@Container
//		@JvmField
//		val hasuraContainer: GenericContainer<*> = GenericContainer<Nothing>("hasura/graphql-engine:v1.0.0")
//				.apply{
//					dependsOn(postgresqlContainer)
//					withExposedPorts(8080)
//					withEnv(mapOf(
//							"HASURA_GRAPHQL_DATABASE_URL" to postgresqlContainer.getJdbcUrl().replace("jdbc:", ""),
//							"HASURA_GRAPHQL_ACCESS_KEY" to "hasuraconf",
//							"HASURA_GRAPHQL_STRINGIFY_NUMERIC_TYPES" to "true"
//					))
//				}


		// Dynamic initialization of properties. This is necessary because we only know the
		// spring.datasource.url once the postgresqlContainer is up and running.
		class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
			override fun initialize(configurableApplicationContext: ConfigurableApplicationContext) {
				TestPropertyValues.of(
						"spring.datasource.url=" + postgresqlContainer.getJdbcUrl(),
						"spring.datasource.username=" + postgresqlContainer.getUsername(),
						"spring.datasource.password=" + postgresqlContainer.getPassword()
				).applyTo(configurableApplicationContext.environment)
			}
		}
	}

	@Autowired lateinit var conf: HasuraConfigurator
	@Autowired lateinit var staticConf: HasuraStaticConfigurator

	@DisplayName("Test generated hasura conf JSON validity with snapshot")
	@Test
	fun testJsonWithSnapshot() {
		conf.loadConf = false
		conf.loadMetadata = false
		conf.loadCascadeDelete = false
		conf.configure()

		println("Hasura conf generated:\n${conf.confJson}")
		var snapshot = readFileUsingGetResource("/hasura_config_snapshot1.json")
		JSONAssert.assertEquals(snapshot, conf.confJson, false)
		JSONAssert.assertEquals(conf.confJson, snapshot, false)

		snapshot = readFileUsingGetResource("/metadata_snapshot1.json")
		println("Metadata JSON generated:\n${conf.metadataJson}")
		// Check in both directions
		JSONAssert.assertEquals(snapshot, conf.metadataJson, false)
		JSONAssert.assertEquals(conf.metadataJson, snapshot, false)

		snapshot = readFileUsingGetResource("/cascade_delete_snapshot1.json")
		println("Cascade delete JSON generated:\n${conf.cascadeDeleteJson}")
		// Check in both directions
		JSONAssert.assertEquals(snapshot, conf.cascadeDeleteJson, false)
		JSONAssert.assertEquals(conf.cascadeDeleteJson, snapshot, false)

		println("JSON schema generated:\n${conf.jsonSchema}")
		snapshot = readFileUsingGetResource("/json_schema_snapshot1.json")
		JSONAssert.assertEquals(conf.jsonSchema, snapshot, false)
		JSONAssert.assertEquals(snapshot, conf.jsonSchema, false)

	}

	@DisplayName("Test generated hasura conf JSON by loading into Hasura")
	@Test
	fun testLoadingConfJsonIntoHasura() {
		conf.loadConf = true
		conf.loadMetadata = false
		conf.loadCascadeDelete = false
		conf.hasuraEndpoint = "http://localhost:${hasuraContainer.getMappedPort(8080)}/v1/query"
		conf.hasuraAdminSecret = "hasuraconf"
		conf.configure()

		// Load metadata again and compare with what we had with the previous algorithm
		val meta = exportMetadata(conf.hasuraEndpoint, conf.hasuraAdminSecret!!)
		println("**** export_meta result: $meta")
		var snapshot = readFileUsingGetResource("/metadata_snapshot1.json")
		JSONAssert.assertEquals(snapshot, conf.metadataJson, false)
		JSONAssert.assertEquals(conf.metadataJson, snapshot, false)
	}

	@DisplayName("Test generated metadata JSON by loading into Hasura")
	@Test
	fun testLoadingMetadataJsonIntoHasura() {
		conf.loadConf = false
		conf.loadMetadata = true
		conf.loadCascadeDelete = true
		conf.hasuraEndpoint = "http://localhost:${hasuraContainer.getMappedPort(8080)}/v1/query"
		conf.hasuraAdminSecret = "hasuraconf"
		conf.configure()

		// Load metadata again and compare with what we had with the previous algorithm
		val meta = exportMetadata(conf.hasuraEndpoint, conf.hasuraAdminSecret!!)
		println("**** export_meta result: $meta")
		var snapshot = readFileUsingGetResource("/metadata_snapshot1.json")
		JSONAssert.assertEquals(snapshot, conf.metadataJson, false)
		JSONAssert.assertEquals(conf.metadataJson, snapshot, false)
	}

	// 		// curl -d'{"type": "export_metadata", "args": {}}' http://localhost:8870/v1/query -o hasura_metadata.json -H 'X-Hasura-Admin-Secret: hasuraconf'
	private fun exportMetadata(hasuraEndpoint: String, hasuraAdminSecret: String) : String {
		val client = WebClient
				.builder()
				.baseUrl(hasuraEndpoint)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader("X-Hasura-Admin-Secret", hasuraAdminSecret)
				.build()
		val request = client.post()
				.body<String, Mono<String>>(Mono.just("""{"type": "export_metadata", "args": {}}"""), String::class.java)
				.retrieve()
				.bodyToMono(String::class.java)
		// Make it synchronous for now
		try {
			val result = request.block()
			return result!!
		} catch (ex: WebClientResponseException) {
			throw ex
		}
	}

	@DisplayName("Test HasuraStaticConfigurator")
	@Test
	fun testHasuraStaticConfigurator() {
		staticConf.hasuraEndpoint = "http://localhost:${hasuraContainer.getMappedPort(8080)}/v1/query"
		staticConf.hasuraAdminSecret = "hasuraconf"

		val conf1 = """
			{
			  "type": "bulk",
			  "args": [
			    {
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    }
			  ]
			}
		""".trimIndent()

		// First it should not fail
		staticConf.loadStaticConf(conf1)
		// Should fail
		try {
			staticConf.loadStaticConf(conf1)
			fail { "Should have got an error" }
		}
		catch (ex: Exception) {
			println("Received exception $ex")
		}

		val conf2 = """
			{
			  "hasuraconfLoadSeparately": true,
			  "type": "bulk",
			  "args": [
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"custom_user_data\" of relation \"calendar_user\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    }
			  ]
			}
		""".trimIndent()
		// Should not fail
		staticConf.loadStaticConf(conf2)

		val conf3 = """
			{
			  "hasuraconfLoadSeparately": true,
			  "type": "bulk",
			  "args": [
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"custom_user_data2\" of relation \"calendar_user\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data2\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    }
			  ]
			}
		""".trimIndent()
		// Should not fail no mattter how many times we execute it
		staticConf.loadStaticConf(conf3)
		staticConf.loadStaticConf(conf3)
		staticConf.loadStaticConf(conf3)

		val conf4 = """
			{
			  "hasuraconfLoadSeparately": true,
			  "type": "bulk",
			  "args": [
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"any_field\" of relation \"bad_table\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"bad_table\" ADD COLUMN \"any_field\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    }
			  ]
			}
		""".trimIndent()
		// Should fail even for the first execution, because bad_table doesn't exist and so we won't get the
		// expected "column \"any_field\" of relation \"bad_table\" already exists" message, but
		// "relation \"public.bad_table\" does not exist","status_code":"42P01"
		try {
			staticConf.loadStaticConf(conf4)
			fail { "Should have got an error" }
		}
		catch (ex: Exception) {
			println("Received exception $ex")
		}


		val conf5 = """
			{
			  "hasuraconfLoadSeparately": true,
			  "type": "bulk",
			  "args": [
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"custom_user_data3\" of relation \"calendar_user\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data3\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    },
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"custom_user_data4\" of relation \"calendar_user\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data4\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    },
			    {
			      "hasuraconfIgnoreError": {
			        "message":"column \"custom_user_data5\" of relation \"calendar_user\" already exists",
			        "status_code":"42701"
			      },
			      "type": "run_sql",
			      "args": {
			        "sql": "ALTER TABLE \"public\".\"calendar_user\" ADD COLUMN \"custom_user_data5\" text NULL;",
			        "cascade": false,
			        "read_only": false
			      }
			    }			
			  ]
			}
		""".trimIndent()
		// Multiple operatioons, should not fail no matter how many times we execute it
		staticConf.loadStaticConf(conf5)
		staticConf.loadStaticConf(conf5)
		staticConf.loadStaticConf(conf5)
	}
}
