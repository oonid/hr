package id.or.oo.hr.profile

import id.or.oo.hr.HrFiles
import id.or.oo.pr.session.Bind
import org.junit.Assert.assertEquals
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
}
