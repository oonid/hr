package id.or.oo.hr.profile

import id.or.oo.hr.HrFiles
import id.or.oo.pr.session.Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { HrFiles(tmp.root) }
    private val store by lazy { ProfileStore(files) }
    private val rootfs by lazy { files.rootfs("hermes").apply { mkdirs() } }

    private fun permissions(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun ensureProfileCreatesTheProfileDirectoryOnce() {
        val first = store.ensureProfile()
        val second = store.ensureProfile()

        assertEquals(File(tmp.root, "hr/profile"), first)
        assertEquals(first, second)
        assertTrue(first.isDirectory)
    }

    @Test
    fun theProfileIsBoundOverTheImagesAndroidProfile() {
        assertEquals(
            Bind(File(tmp.root, "hr/profile").absolutePath, "/root/.hermes/profiles/android"),
            store.bind(),
        )
    }

    @Test
    fun theKeyIsGeneratedOnceKeptPrivateAndReadBack() {
        val key = store.apiServerKey()

        assertTrue(Regex("[0-9a-f]{64}").matches(key))
        assertEquals(key, store.apiServerKey())
        assertEquals(key, ProfileStore(files).apiServerKey())
        assertEquals("rw-------", permissions(files.keyFile))
    }

    @Test
    fun aDamagedKeyFileIsReplaced() {
        files.keyFile.parentFile!!.mkdirs()
        files.keyFile.writeText("short")

        val key = store.apiServerKey()

        assertTrue(Regex("[0-9a-f]{64}").matches(key))
        assertEquals(key, files.keyFile.readText())
    }

    @Test
    fun twoStoresGenerateDifferentKeys() {
        val other = ProfileStore(HrFiles(tmp.newFolder("other")))

        assertNotEquals(store.apiServerKey(), other.apiServerKey())
    }

    @Test
    fun theRootEnvCarriesTheListenerSettingsAndIsPrivate() {
        store.writeRootEnv(rootfs)

        val env = File(rootfs, "root/.hermes/.env")
        assertEquals(
            "API_SERVER_ENABLED=true\nAPI_SERVER_KEY=${store.apiServerKey()}\nAPI_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT=8642\n",
            env.readText(),
        )
        assertEquals("rw-------", permissions(env))
    }

    @Test
    fun rewritingTheRootEnvKeepsOtherSettings() {
        val env = File(rootfs, "root/.hermes/.env").apply {
            parentFile!!.mkdirs()
            writeText("GATEWAY_ALLOW_ALL_USERS=false\nAPI_SERVER_KEY=old\n")
        }

        store.writeRootEnv(rootfs)
        store.writeRootEnv(rootfs)

        assertEquals(
            "GATEWAY_ALLOW_ALL_USERS=false\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${store.apiServerKey()}\n" +
                "API_SERVER_HOST=127.0.0.1\nAPI_SERVER_PORT=8642\n",
            env.readText(),
        )
    }

    @Test
    fun aMissingContainerIsReportedAndNothingIsWritten() {
        val missing = files.rootfs("absent")

        val e = assertThrows(IllegalStateException::class.java) { store.writeRootEnv(missing) }

        assertEquals("the Hermes container has no root at $missing", e.message)
        assertTrue(!missing.exists())
    }

    @Test
    fun theProfileEnvGetsTheKeyAndKeepsOtherLines() {
        store.ensureProfile()
        val env = File(files.profileDir, ".env")
        env.writeText("TELEGRAM_BOT_TOKEN=x\nFOO=bar\n")

        store.writeProfileEnv(mapOf("OPENROUTER_API_KEY" to "k"))

        val lines = env.readLines()
        assertTrue(lines.contains("FOO=bar"))
        assertTrue(lines.contains("TELEGRAM_BOT_TOKEN=x"))
        assertTrue(lines.contains("OPENROUTER_API_KEY=k"))
        assertTrue(lines.contains("API_SERVER_KEY=${store.apiServerKey()}"))
        assertTrue(Regex("[0-9a-f]{64}").matches(store.apiServerKey()))
        assertEquals("rw-------", permissions(env))
    }

    @Test
    fun secretsIncludeTheApiKeyAndEveryKeyOrTokenValue() {
        store.writeProfileEnv(
            mapOf("TELEGRAM_BOT_TOKEN" to "123:abc", "OPENROUTER_API_KEY" to "sk-or", "FOO" to "bar", "EMPTY_KEY" to "",
                "MATRIX_PASSWORD" to "pw-1", "SLACK_SIGNING_SECRET" to "sec-1",
            ),
        )

        val secrets = store.secrets()

        assertTrue(secrets.contains(store.apiServerKey()))
        assertTrue(secrets.contains("123:abc"))
        assertTrue(secrets.contains("sk-or"))
        assertTrue(secrets.contains("pw-1"))
        assertTrue(secrets.contains("sec-1"))
        assertFalse(secrets.contains("bar"))
        assertFalse(secrets.contains(""))
    }

    @Test
    fun secretsWithoutAnEnvFileAreTheApiKey() {
        assertEquals(listOf(store.apiServerKey()), store.secrets())
    }

    private fun authFile(): File = File(files.profileDir, "auth.json").also { files.profileDir.mkdirs() }

    @Test
    fun loginTokensAreSecrets() {
        authFile().writeText(
            """{"providers":{"openai-codex":{"tokens":{"access_token":"access-dummy-1","expires_at":1893456000,"id":"id-dummy-1"}}},
            "credential_pool":{"openai-codex":[{"id":"id-dummy-2","refresh_token":"refresh-dummy-1","agent_key":"agent-dummy-1","api_key":"apikey-dummy-1","label":"main"}]}}""",
        )

        val secrets = store.secrets()

        assertTrue(secrets.containsAll(listOf("access-dummy-1", "refresh-dummy-1", "agent-dummy-1", "apikey-dummy-1")))
        assertFalse(secrets.contains("id-dummy-1"))
        assertFalse(secrets.contains("id-dummy-2"))
        assertFalse(secrets.contains("main"))
        assertEquals(secrets.distinct(), secrets)
    }

    @Test
    fun aRewrittenAuthFileIsPickedUp() {
        authFile().writeText("""{"access_token":"first-token-1"}""")
        assertTrue(store.secrets().contains("first-token-1"))

        authFile().writeText("""{"access_token":"second-token-longer"}""")

        val secrets = store.secrets()
        assertTrue(secrets.contains("second-token-longer"))
        assertFalse(secrets.contains("first-token-1"))
    }

    @Test
    fun aRemovedAuthFileStopsContributing() {
        authFile().writeText("""{"access_token":"first-token-1"}""")
        assertTrue(store.secrets().contains("first-token-1"))

        authFile().delete()

        assertEquals(listOf(store.apiServerKey()), store.secrets())
    }

    @Test
    fun aCorruptAuthFileAddsNothing() {
        authFile().writeText("{not json")

        assertEquals(listOf(store.apiServerKey()), store.secrets())
    }

    @Test
    fun blankAndNonStringTokensAreIgnored() {
        authFile().writeText("""{"access_token":"  ","refresh_token":12345678,"api_key":null,"agent_key":""}""")

        assertEquals(listOf(store.apiServerKey()), store.secrets())
    }

    @Test
    fun secretsPickUpARewriteOfTheEnvFile() {
        store.writeProfileEnv(mapOf("TELEGRAM_BOT_TOKEN" to "first"))
        assertTrue(store.secrets().contains("first"))

        store.writeProfileEnv(mapOf("TELEGRAM_BOT_TOKEN" to "second-longer"))

        val secrets = store.secrets()
        assertTrue(secrets.contains("second-longer"))
        assertFalse(secrets.contains("first"))
    }

    @Test
    fun removingTelegramKeepsTheRest() {
        store.ensureProfile()
        val env = File(files.profileDir, ".env")
        env.writeText(
            "OPENROUTER_API_KEY=k\nTELEGRAM_BOT_TOKEN=123:abc\nexport TELEGRAM_ALLOWED_USERS = 1,2\n" +
                "API_SERVER_KEY=${store.apiServerKey()}\nFOO=bar\n",
        )

        store.removeFromProfileEnv(setOf("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS"))

        assertEquals("OPENROUTER_API_KEY=k\nAPI_SERVER_KEY=${store.apiServerKey()}\nFOO=bar\n", env.readText())
        assertEquals("rw-------", permissions(env))
    }

    @Test
    fun removingFromAMissingEnvDoesNothing() {
        store.removeFromProfileEnv(setOf("TELEGRAM_BOT_TOKEN"))

        assertFalse(File(files.profileDir, ".env").exists())
    }

    @Test
    fun removingEveryLineLeavesAnEmptyEnv() {
        store.ensureProfile()
        val env = File(files.profileDir, ".env")
        env.writeText("TELEGRAM_BOT_TOKEN=x\n")

        store.removeFromProfileEnv(setOf("TELEGRAM_BOT_TOKEN"))

        assertEquals("", env.readText())
    }

    private fun configWith(text: String): ProfileStore {
        store.ensureProfile()
        File(files.profileDir, "config.yaml").writeText(text)
        return store
    }

    @Test
    fun noConfigHasNoProvider() {
        assertFalse(store.hasProvider())
    }

    @Test
    fun aProviderUnderModelIsAProvider() {
        assertTrue(configWith("model:\n  default: gpt-5.6-luna\n  provider: openai-codex\n").hasProvider())
        assertTrue(configWith("agent:\n  x: 1\nmodel:   # main\n\n  provider: \"openrouter\"\n").hasProvider())
        assertTrue(configWith("model:\n    provider: anthropic\nother: 1\n").hasProvider())
    }

    @Test
    fun aProviderElsewhereOrEmptyIsNotAProvider() {
        val configs = listOf(
            "",
            "provider: openrouter\n",
            "auxiliary:\n  provider: openrouter\n",
            "model:\n  provider:\n",
            "model:\n  provider: ''\n",
            "model:\n  provider: \"\"  # none\n",
            "model:\n  fallback:\n    provider: openrouter\n",
            "model:\n  default: m\nauxiliary:\n  provider: openrouter\n",
            "# model:\n#   provider: openrouter\n",
            "model: gpt\n",
            "models:\n  provider: openrouter\n",
        )
        for (config in configs) {
            assertFalse(config, configWith(config).hasProvider())
        }
    }
}
