package id.or.oo.hr.setup

import id.or.oo.hr.HrFiles
import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import id.or.oo.hr.image.HttpResponse
import id.or.oo.hr.image.UrlHttp
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.Bind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket

class TestRequestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var reply = response(200, """{"choices": [{"message": {"role": "assistant", "content": " ok\n"}}]}""")
    private val server = TestHttpServer { _, _ -> reply }
    private val url get() = "http://127.0.0.1:${server.port}/p/android/v1/chat/completions"
    private val files by lazy { HrFiles(tmp.root) }
    private val store by lazy { ProfileStore(files).also { it.ensureProfile() } }
    private var telegramInstalled = true
    private val scripts = mutableListOf<String>()
    private val settings by lazy {
        ProfileSettings(
            HermesCommand(
                { args, onLine ->
                    scripts += args.last()
                    if (args.last() == TELEGRAM_CHECK_SCRIPT && telegramInstalled) {
                        onLine(TELEGRAM_PRESENT)
                        0
                    } else if (args.last() == TELEGRAM_CHECK_SCRIPT) {
                        onLine("ModuleNotFoundError: No module named 'telegram'")
                        1
                    } else {
                        0
                    }
                },
                Bind("/host/profile", "/root/.hermes/profiles/android"),
            ),
            store,
        )
    }
    private val env get() = File(files.profileDir, ".env")

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun anOkAnswerIsAnswered() {
        assertEquals(TestResult.Answered("ok"), TestRequest(UrlHttp(), { "k" }, url).send())
    }

    @Test
    fun theKeyIsSentAsBearerWithTheExactBody() {
        TestRequest(UrlHttp(), { "k" }, url).send()

        assertEquals("Bearer k", server.seenHeaders.single()["authorization"])
        assertEquals(listOf("POST"), server.methods)
        assertEquals(listOf("/p/android/v1/chat/completions"), server.requests)
        val body = JSONObject(server.bodies.single())
        assertEquals(setOf("model", "messages"), body.keys().asSequence().toSet())
        assertEquals("hermes-agent", body.getString("model"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals(1, body.getJSONArray("messages").length())
        assertEquals("user", message.getString("role"))
        assertEquals("Reply with exactly one word: ok", message.getString("content"))
    }

    @Test
    fun a401IsRejected() {
        reply = response(401, "x".repeat(600))

        val result = TestRequest(UrlHttp(), { "k" }, url).send()

        assertEquals(TestResult.Rejected(401, "x".repeat(500)), result)
    }

    @Test
    fun everySecretInARejectedBodyIsRedacted() {
        reply = response(401, "invalid api key: sk-one-key and tok-two-key, sk-one-key again")

        val result = TestRequest(UrlHttp(), { "sk-one-key" }, url, { listOf("sk-one-key", "", "tok-two-key") }).send()

        assertEquals(TestResult.Rejected(401, "invalid api key: [redacted] and [redacted], [redacted] again"), result)
    }

    @Test
    fun a500IsRejected() {
        reply = response(500, "boom")

        assertEquals(TestResult.Rejected(500, "boom"), TestRequest(UrlHttp(), { "k" }, url).send())
    }

    @Test
    fun noServerIsNoGateway() {
        val port = ServerSocket(0).use { it.localPort }

        val result = TestRequest(UrlHttp(), { "k" }, "http://127.0.0.1:$port/x").send()

        assertEquals(TestResult.NoGateway, result)
    }

    @Test
    fun anAnswerIsRedacted() {
        reply = response(200, """{"choices": [{"message": {"role": "assistant", "content": " ok sk-one-key, tok-two-k… \n"}}]}""")

        val result = TestRequest(UrlHttp(), { "sk-one-key" }, url, { listOf("sk-one-key", "tok-two-key") }).send()

        assertEquals(TestResult.Answered("ok [redacted], [redacted]…"), result)
    }

    @Test
    fun anAbbreviatedKeyInARejectionOrAnErrorIsRedacted() {
        reply = response(401, "invalid api key: sk-one-k…")

        val rejected = TestRequest(UrlHttp(), { "sk-one-key" }, url).send()

        assertEquals(TestResult.Rejected(401, "invalid api key: [redacted]…"), rejected)
        val http = object : id.or.oo.hr.image.Http {
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
                throw IOException("stream broke for sk-one-k…")
        }
        assertEquals(TestResult.Failed("stream broke for [redacted]…"), TestRequest(http, { "sk-one-key" }, url).send())
    }

    @Test
    fun aTimeoutIsFailed() {
        val http = object : id.or.oo.hr.image.Http {
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
                throw IOException("Read timed out")
        }

        assertEquals(TestResult.Failed("Read timed out"), TestRequest(http, { "k" }, url).send())
    }

    @Test
    fun aTimeoutSaysWhatToCheck() {
        val http = object : id.or.oo.hr.image.Http {
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
                throw java.net.SocketTimeoutException("Read timed out")
        }

        val expected = "the gateway did not answer in time; the model endpoint may be unreachable" +
            " — check the provider's base URL and key"
        assertEquals(TestResult.Failed(expected), TestRequest(http, { "k" }, url).send())
    }

    @Test
    fun anIOExceptionMessageIsRedacted() {
        val http = object : id.or.oo.hr.image.Http {
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
                throw IOException("stream broke for sk-one-key and tok-two-key")
        }

        val result = TestRequest(http, { "sk-one-key" }, url, { listOf("sk-one-key", "tok-two-key") }).send()

        assertEquals(TestResult.Failed("stream broke for [redacted] and [redacted]"), result)
    }

    @Test
    fun anIoErrorWithoutAMessageIsNamedByItsClass() {
        val http = object : id.or.oo.hr.image.Http {
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
                throw IOException()
        }

        assertEquals(TestResult.Failed("IOException"), TestRequest(http, { "k" }, url).send())
    }

    @Test
    fun anUnreadableAnswerIsFailed() {
        val unreadable = listOf("not json", "{}", """{"choices": []}""", """{"choices": [{"message": {}}]}""",
            """{"choices": [{"message": {"content": null}}]}""",
            """{"choices": [{"message": {"content": 5}}]}""",
        )
        for (body in unreadable) {
            reply = response(200, body)

            assertEquals(
                body,
                TestResult.Failed("the gateway answered something hr cannot read"),
                TestRequest(UrlHttp(), { "k" }, url).send(),
            )
        }
    }

    @Test
    fun theKeyNeverAppearsInAResult() {
        reply = response(401, "denied")

        val result = TestRequest(UrlHttp(), { "secret-key" }, url).send()

        assertFalse(result.toString().contains("secret-key"))
    }

    @Test
    fun telegramUsersMustBeDigits() = runBlocking {
        for (bad in listOf("alice", "1,,2", "1,x", ",1", "1,")) {
            val result = settings.useTelegram("123:abc", bad)

            assertTrue(bad, result.isFailure)
        }
        assertFalse(env.exists())
    }

    @Test
    fun telegramWritesTheTokenAndUsers() = runBlocking {
        val result = settings.useTelegram(" 123:abc ", "1, 22 ,333")

        assertTrue(result.isSuccess)
        val lines = env.readText().lines()
        assertTrue(lines.contains("TELEGRAM_BOT_TOKEN=123:abc"))
        assertTrue(lines.contains("TELEGRAM_ALLOWED_USERS=1,22,333"))
    }

    @Test
    fun telegramWithoutUsersKeepsTheExistingList() = runBlocking {
        store.writeProfileEnv(mapOf("TELEGRAM_ALLOWED_USERS" to "7,8"))

        val result = settings.useTelegram("123:abc", "  ")

        assertTrue(result.isSuccess)
        val lines = env.readText().lines()
        assertTrue(lines.contains("TELEGRAM_BOT_TOKEN=123:abc"))
        assertTrue(lines.contains("TELEGRAM_ALLOWED_USERS=7,8"))
    }

    @Test
    fun telegramWithoutItsLibraryInTheImageWritesNothing() = runBlocking {
        telegramInstalled = false

        val result = settings.useTelegram("123:abc", "1")

        assertEquals(
            "this Hermes image has no Telegram support; install a newer image",
            result.exceptionOrNull()?.message,
        )
        assertEquals(listOf(TELEGRAM_CHECK_SCRIPT), scripts)
        assertFalse(env.exists())
    }

    @Test
    fun aBadTokenIsRefusedBeforeTheImageIsChecked() = runBlocking {
        val result = settings.useTelegram("a b", "1")

        assertTrue(result.isFailure)
        assertTrue(scripts.isEmpty())
    }

    @Test
    fun aBadTokenWritesNothing() = runBlocking {
        for (bad in listOf("", "  ", "a b", "a=b", "a\"b", "a\\b", "a\nb")) {
            val result = settings.useTelegram(bad, "1")

            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()!!.message!!.contains("a=b"))
        }
        assertFalse(env.exists())
    }

    @Test
    fun removingTelegramDropsBothLinesAndKeepsOtherKeys() = runBlocking {
        store.writeProfileEnv(
            mapOf("TELEGRAM_BOT_TOKEN" to "1:a", "TELEGRAM_ALLOWED_USERS" to "5", "OPENROUTER_API_KEY" to "k"),
        )

        val result = settings.removeTelegram()

        assertTrue(result.isSuccess)
        val lines = env.readText().lines()
        assertTrue(lines.contains("OPENROUTER_API_KEY=k"))
        assertTrue(lines.none { it.startsWith("TELEGRAM_") })
    }

    @Test
    fun removingTelegramWithoutAnEnvFileSucceeds() = runBlocking {
        assertTrue(settings.removeTelegram().isSuccess)
    }
}
