package dev.jiaming.ai_interview.supabase

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties
import javax.sql.DataSource
import kotlin.system.exitProcess

object SupabaseMigrationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val settings = try {
            Settings.load()
        } catch (error: Exception) {
            System.err.println("Supabase migration failed: ${error.message ?: error.javaClass.simpleName}")
            exitProcess(1)
        }
        try {
            when (args.singleOrNull() ?: "migrate") {
                "migrate" -> SupabaseMigrationRunner(settings.dataSource()).migrate()
                "bootstrap-runtime" -> {
                    val password = settings.required("DATABASE_PASSWORD")
                    val runner = SupabaseMigrationRunner(settings.dataSource())
                    runner.migrate()
                    runner.bootstrapRuntime(password)
                }
                else -> error("Expected no argument or 'bootstrap-runtime'")
            }
            println("Supabase database operation completed")
        } catch (error: Exception) {
            System.err.println("Supabase migration failed: ${settings.redact(error.message ?: error.javaClass.simpleName)}")
            exitProcess(1)
        }
    }
}

class SupabaseMigrationRunner(private val dataSource: DataSource) {
    fun migrate() {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas("public")
            .defaultSchema("public")
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .baselineDescription("Supabase provider objects")
            .initSql("SET search_path TO public, extensions")
            .ignoreMigrationPatterns("*:pending")
            .cleanDisabled(true)
            .load()

        val plan = dataSource.connection.use { connection ->
            if (historyExists(connection)) {
                validateOwnedHistory(connection)
                flyway.validate()
                false
            } else {
                check(!hasApplicationObjects(connection)) {
                    "Refusing initial migration: application objects already exist without Flyway history"
                }
                hasProviderObjects(connection)
            }
        }

        if (plan) {
            flyway.baseline()
            dataSource.connection.use(::validateOwnedHistory)
            flyway.validate()
        }
        flyway.migrate()
        dataSource.connection.use(::validateOwnedHistory)
    }

    fun bootstrapRuntime(password: String) {
        require(password.isNotBlank()) { "DATABASE_PASSWORD is required for runtime bootstrap" }
        dataSource.connection.use { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                if (roleExists(connection, RUNTIME_ROLE)) {
                    check(!roleHasPrivilegedAttributes(connection, RUNTIME_ROLE)) {
                        "$RUNTIME_ROLE has privileged attributes; use a restricted runtime role"
                    }
                    check(!roleOwnsApplicationObjects(connection, RUNTIME_ROLE)) {
                        "$RUNTIME_ROLE owns application objects; transfer ownership before granting runtime access"
                    }
                    check(!roleHasMembership(connection, RUNTIME_ROLE)) {
                        "$RUNTIME_ROLE belongs to another role; remove the membership before granting runtime access"
                    }
                } else {
                    connection.createStatement().use { it.execute("CREATE ROLE $RUNTIME_ROLE LOGIN") }
                }
                connection.createStatement().use {
                    it.execute("ALTER ROLE $RUNTIME_ROLE WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT")
                }
                val passwordSql = connection.prepareStatement("SELECT format('ALTER ROLE %I WITH PASSWORD %L', ?, ?)").use { statement ->
                    statement.setString(1, RUNTIME_ROLE)
                    statement.setString(2, password)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "Unable to quote runtime role password" }
                        result.getString(1)
                    }
                }
                connection.createStatement().use { it.execute(passwordSql) }
                connection.createStatement().use { statement ->
                    statement.execute("REVOKE ALL ON SCHEMA public, ai_interview_app, ai_interview_api FROM $RUNTIME_ROLE")
                    statement.execute("GRANT USAGE ON SCHEMA public, ai_interview_app, ai_interview_api TO $RUNTIME_ROLE")
                    statement.execute("REVOKE ALL ON ALL TABLES IN SCHEMA ai_interview_app FROM $RUNTIME_ROLE")
                    statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ai_interview_app TO $RUNTIME_ROLE")
                    statement.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_interview_app FROM $RUNTIME_ROLE")
                    statement.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ai_interview_app TO $RUNTIME_ROLE")
                    statement.execute("REVOKE ALL ON public.vector_store FROM $RUNTIME_ROLE")
                    statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.vector_store TO $RUNTIME_ROLE")
                    statement.execute("REVOKE CREATE ON SCHEMA public, ai_interview_app, ai_interview_api FROM $RUNTIME_ROLE")
                }
                if (schemaExists(connection, "extensions")) {
                    connection.createStatement().use { statement ->
                        statement.execute("GRANT USAGE ON SCHEMA extensions TO $RUNTIME_ROLE")
                        statement.execute("REVOKE CREATE ON SCHEMA extensions FROM $RUNTIME_ROLE")
                    }
                }
                grantExtensionAccess(connection)
                connection.commit()
            } catch (error: Exception) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                connection.autoCommit = autoCommit
            }
        }
    }

    private fun grantExtensionAccess(connection: Connection) {
        connection.prepareStatement(
            """SELECT format('%I.%I(%s)', n.nspname, p.proname, pg_get_function_identity_arguments(p.oid))
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               JOIN pg_depend d ON d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
               JOIN pg_extension e ON e.oid = d.refobjid
               WHERE e.extname IN ('vector', 'pgcrypto', 'hstore', 'uuid-ossp') AND p.prokind IN ('f', 'w')"""
        ).use { query ->
            query.executeQuery().use { functions ->
                while (functions.next()) connection.createStatement().use {
                    it.execute("GRANT EXECUTE ON FUNCTION ${functions.getString(1)} TO $RUNTIME_ROLE")
                }
            }
        }
        connection.prepareStatement(
            """SELECT format('%I.%I', n.nspname, t.typname)
               FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
               JOIN pg_depend d ON d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
               JOIN pg_extension e ON e.oid = d.refobjid
               WHERE e.extname IN ('vector', 'pgcrypto', 'hstore', 'uuid-ossp') AND t.typelem = 0"""
        ).use { query ->
            query.executeQuery().use { types ->
                while (types.next()) connection.createStatement().use {
                    it.execute("GRANT USAGE ON TYPE ${types.getString(1)} TO $RUNTIME_ROLE")
                }
            }
        }
    }

    private fun historyExists(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history')"
    ).use { it.executeQuery().use { result -> result.next(); result.getBoolean(1) } }

    private fun validateOwnedHistory(connection: Connection) {
        val rows = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version, type, success FROM public.flyway_schema_history ORDER BY installed_rank").use { result ->
                buildList {
                    while (result.next()) add(HistoryRow(result.getString(1), result.getString(2), result.getBoolean(3)))
                }
            }
        }
        val first = rows.firstOrNull { it.success }
            ?: error("Existing Flyway history has no successful baseline or V1 migration")
        val baselines = rows.filter { it.type == "BASELINE" }
        check(baselines.all { it.version == "0" } && (baselines.isEmpty() || first == baselines.first())) {
            "Existing Flyway history must use baseline version 0"
        }
        if (baselines.isEmpty()) {
            check(first.type == "SQL" && first.version == "1") {
                "Existing Flyway history must start at V1 when it has no baseline"
            }
        }
    }

    private fun hasApplicationObjects(connection: Connection): Boolean {
        val knownPublicTables = listOf(
            "app_users", "resumes", "job_descriptions", "resume_assessments", "interview_sessions",
            "interview_questions", "interview_answers", "resume_chunks", "job_description_chunks",
            "question_embeddings", "answer_embeddings", "vector_store", "background_jobs"
        ).joinToString(",") { "'${it}'" }
        return connection.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT EXISTS (
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname IN ($knownPublicTables)
                      AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                    UNION ALL
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname IN ('ai_interview_app', 'ai_interview_api')
                    UNION ALL
                    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname IN ('ai_interview_app', 'ai_interview_api')
                    UNION ALL
                    SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname IN ('ai_interview_app', 'ai_interview_api')
                )""".trimIndent()
            ).use { result -> result.next(); result.getBoolean(1) }
        }
    }

    private fun hasProviderObjects(connection: Connection): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery(
            """SELECT EXISTS (
                SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                UNION ALL
                SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public'
                UNION ALL
                SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = 'public'
            )""".trimIndent()
        ).use { result -> result.next(); result.getBoolean(1) }
    }

    private fun roleExists(connection: Connection, role: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)"
    ).use { statement ->
        statement.setString(1, role)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun roleOwnsApplicationObjects(connection: Connection, role: String): Boolean = connection.prepareStatement(
        """SELECT EXISTS (
            SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
            WHERE r.rolname = ? AND n.nspname IN ('public', 'ai_interview_app', 'ai_interview_api')
            UNION ALL
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_roles r ON r.oid = c.relowner
            WHERE r.rolname = ? AND (
                n.nspname IN ('ai_interview_app', 'ai_interview_api') OR
                (n.nspname = 'public' AND c.relname IN (
                    'app_users', 'resumes', 'job_descriptions', 'resume_assessments', 'interview_sessions',
                    'interview_questions', 'interview_answers', 'resume_chunks', 'job_description_chunks',
                    'question_embeddings', 'answer_embeddings', 'vector_store', 'background_jobs'
                ))
            )
        )"""
    ).use { statement ->
        statement.setString(1, role)
        statement.setString(2, role)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun schemaExists(connection: Connection, schema: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)"
    ).use { statement ->
        statement.setString(1, schema)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun roleHasPrivilegedAttributes(connection: Connection, role: String): Boolean = connection.prepareStatement(
        "SELECT rolsuper OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = ?"
    ).use { statement ->
        statement.setString(1, role)
        statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
    }

    private fun roleHasMembership(connection: Connection, role: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = ?)"
    ).use { statement ->
        statement.setString(1, role)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private data class HistoryRow(val version: String?, val type: String, val success: Boolean)

    private companion object {
        const val RUNTIME_ROLE = "ai_interview_runtime"
    }
}

private data class Settings(
    private val values: Map<String, String>,
    private val url: String,
    private val username: String,
    private val password: String,
    private val rootCert: Path
) {
    fun dataSource(): DriverManagerDataSource = DriverManagerDataSource(url, username, password).apply {
        setDriverClassName("org.postgresql.Driver")
        setConnectionProperties(Properties().apply {
            setProperty("sslmode", "verify-full")
            setProperty("sslrootcert", rootCert.toString())
            setProperty("currentSchema", "public,extensions")
        })
    }

    fun required(name: String): String = values[name]?.takeIf(String::isNotBlank)
        ?: error("Missing required setting: $name")

    fun redact(message: String): String = values.values
        .filter(String::isNotBlank)
        .fold(message) { text, secret -> text.replace(secret, "[redacted]") }

    companion object {
        private const val PROJECT_REF = "mjzycnjhtwyqcblwbjvy"

        fun load(): Settings {
            val file = Path.of(System.getenv("SUPABASE_ENV_FILE") ?: ".env.supabase")
            val properties = Properties()
            if (Files.isRegularFile(file)) Files.newInputStream(file).use(properties::load)
            val keys = properties.stringPropertyNames() + setOf(
                "SUPABASE_MIGRATION_URL", "SUPABASE_MIGRATION_USERNAME", "SUPABASE_MIGRATION_PASSWORD",
                "SUPABASE_SSL_ROOT_CERT", "DATABASE_PASSWORD"
            )
            val values = keys.associateWith { name ->
                System.getenv(name) ?: properties.getProperty(name).orEmpty()
            }
            val url = values["SUPABASE_MIGRATION_URL"]?.trim()?.takeIf(String::isNotBlank)
                ?: error("Missing required setting: SUPABASE_MIGRATION_URL")
            val username = values["SUPABASE_MIGRATION_USERNAME"]?.trim()?.takeIf(String::isNotBlank)
                ?: error("Missing required setting: SUPABASE_MIGRATION_USERNAME")
            val password = values["SUPABASE_MIGRATION_PASSWORD"]?.takeIf(String::isNotBlank)
                ?: error("Missing required setting: SUPABASE_MIGRATION_PASSWORD")
            val cert = values["SUPABASE_SSL_ROOT_CERT"]?.trim()?.takeIf(String::isNotBlank)
                ?: error("Missing required setting: SUPABASE_SSL_ROOT_CERT")
            validateSessionUrl(url, username)
            val rootCert = Path.of(cert).toAbsolutePath().normalize()
            require(Files.isRegularFile(rootCert) && Files.isReadable(rootCert)) { "SUPABASE_SSL_ROOT_CERT must name a readable certificate file" }
            return Settings(values + ("SUPABASE_MIGRATION_URL" to url) + ("SUPABASE_MIGRATION_USERNAME" to username) + ("SUPABASE_MIGRATION_PASSWORD" to password), url, username, password, rootCert)
        }

        private fun validateSessionUrl(url: String, username: String) {
            val uri = runCatching { URI(url.removePrefix("jdbc:")) }.getOrElse {
                error("SUPABASE_MIGRATION_URL must be a PostgreSQL JDBC URL")
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                "SUPABASE_MIGRATION_URL must contain a host and must not embed credentials"
            }
            require(url.startsWith("jdbc:postgresql://") && uri.host.endsWith(".pooler.supabase.com") && uri.port == 5432 && uri.path == "/postgres") {
                "SUPABASE_MIGRATION_URL must use the Supabase session pooler on port 5432 and database postgres"
            }
            val pairs = try {
                uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { part ->
                    val entry = part.split('=', limit = 2)
                    URLDecoder.decode(entry[0], UTF_8) to URLDecoder.decode(entry.getOrElse(1) { "" }, UTF_8)
                }
            } catch (_: Exception) {
                error("SUPABASE_MIGRATION_URL parameters are invalid")
            }
            require(pairs.map { it.first.lowercase() }.distinct().size == pairs.size) {
                "Duplicate JDBC URL parameters are not allowed"
            }
            val query = pairs.associate { it.first.lowercase() to it.second }
            require(query["sslmode"] == "verify-full" && query["currentschema"] == "public,extensions") {
                "SUPABASE_MIGRATION_URL must set sslmode=verify-full and currentSchema=public,extensions"
            }
            require(pairs.none { it.first.lowercase() in setOf(
                "user", "password", "sslfactory", "sslhostnameverifier", "sslrootcert", "ssl", "options"
            ) }) {
                "SUPABASE_MIGRATION_URL must not override credentials, certificate verification, or session settings"
            }
            require(username.endsWith(".$PROJECT_REF") && username != "ai_interview_runtime.$PROJECT_REF") {
                "SUPABASE_MIGRATION_USERNAME must use the project migration role for $PROJECT_REF"
            }
        }
    }
}
