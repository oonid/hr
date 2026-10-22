package id.or.oo.hr.gateway

import id.or.oo.hr.HrFiles
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.session.FakePty
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.Pty
import id.or.oo.pr.session.SessionManager
import id.or.oo.pr.session.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayControllerTest {

    private class GatewayPty(val fake: FakePty = FakePty(), private val sigtermExit: Int? = 1) : Pty by fake {
        override fun signalTracees(): Set<Int> {
            fake.signalTracees()
            sigtermExit?.let(fake::exit)
            return setOf(GATEWAY_PID)
        }
    }

    private class FakeProbe : GatewayProbe {
        @Volatile var busy = false
        @Volatile var health: Health? = null
        val healthChecks = AtomicInteger()

        override fun portInUse(): Boolean = busy

        override fun health(): Health? {
            healthChecks.incrementAndGet()
            return health
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val files by lazy { HrFiles(tmp.root) }
    private val profile by lazy { ProfileStore(files) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ended = CopyOnWriteArrayList<Pair<String, Int?>>()
    private val sessions = SessionManager(
        { _, _ -> error("the terminal is not used here") },
        object : SessionManager.ForegroundController {
            override fun start() {}
        },
    ) { alias, code -> ended += alias to code }
    private val log = LogBuffer()
    private val probe = FakeProbe()
    private val ptys = CopyOnWriteArrayList<GatewayPty>()
    private val prepared = AtomicInteger()
    private var nextPty: () -> GatewayPty = { GatewayPty() }
    private var prepare: () -> SessionManager.SessionFactory = {
        SessionManager.SessionFactory { alias, onEnded ->
            val pty = nextPty().also { ptys += it }
            ManagedSession(
                alias = alias,
                pty = pty,
                emulator = null,
                output = log::append,
                onEnded = onEnded,
                endGraceMs = 200,
                signalWaitMs = 500,
                exitCodeWaitMs = 100,
            )
        }
    }
    private var now = 1_000L

    private val controller by lazy {
        GatewayController(
            sessions,
            { prepared.incrementAndGet(); prepare() },
            probe,
            profile,
            log,
            scope,
            clock = { now },
            pollMs = 10,
        )
    }

    @After
    fun tearDown() {
        ptys.forEach { it.fake.exit(0) }
        scope.cancel()
    }

    @Test
    fun startOpensTheGatewayAndItIsStartingUntilHealthAnswers() {
        assertTrue(controller.start())

        eventually { sessions.running.value == listOf("hermes-gateway") }
        eventually { probe.healthChecks.get() >= 3 }
        assertEquals(GatewayState.Starting(1_000), controller.state.value)

        now = 28_000
        probe.health = Health("v0.22.3")

        eventually { controller.state.value == GatewayState.Running(1_000, "v0.22.3") }
    }

    @Test
    fun theGatewaysOutputReachesTheLogAfterAStartMark() {
        controller.start()
        eventually { ptys.size == 1 }

        ptys.single().fake.emit("\u001b[32mINFO\u001b[0m gateway ready\r\n")

        eventually { log.snapshot() == listOf("Starting the gateway", "INFO gateway ready") }
    }

    @Test
    fun stopSendsEndAndExit1AfterItIsACleanStop() {
        probe.health = Health("v0.22.3")
        controller.start()
        eventually { controller.state.value is GatewayState.Running }

        assertTrue(controller.stop())

        assertEquals(GatewayState.Stopping, controller.state.value)
        eventually { controller.state.value == GatewayState.Stopped(1) }
        assertEquals(listOf("signal", "close"), ptys.single().fake.calls)
        assertTrue(sessions.running.value.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test
    fun aGatewayThatIgnoresSigtermIsKilledAndSaysSo() {
        nextPty = { GatewayPty(FakePty().apply { ignoresEof = true }, sigtermExit = null) }
        controller.start()
        eventually { ptys.size == 1 }

        controller.stop()

        eventually(timeoutMs = 5_000) { controller.state.value == GatewayState.Stopped(137) }
        assertEquals(listOf("signal", "eof", "kill", "close"), ptys.single().fake.calls)
    }

    @Test
    fun aGatewayThatExitsOnItsOwnStoppedUnexpectedlyWithItsLastLines() {
        controller.start()
        eventually { ptys.size == 1 }
        val pty = ptys.single().fake
        pty.emit("Configuration error: no profile\n")
        eventually { log.snapshot().size == 2 }

        pty.exit(78)

        eventually { controller.state.value is GatewayState.StoppedUnexpectedly }
        assertEquals(
            GatewayState.StoppedUnexpectedly(78, listOf("Starting the gateway", "Configuration error: no profile")),
            controller.state.value,
        )
        assertEquals(listOf("hermes-gateway" to 78), ended)
    }

    @Test
    fun somethingOnThePortStopsTheStartBeforeAnythingRuns() {
        probe.busy = true

        controller.start()

        eventually { controller.state.value is GatewayState.Failed }
        assertEquals(GatewayState.Failed(GatewayController.PORT_IN_USE), controller.state.value)
        assertEquals(0, prepared.get())
        assertTrue(ptys.isEmpty())
    }

    @Test
    fun aPrepareFailureIsShownWithItsMessage() {
        prepare = { error("Install the Hermes image first.") }

        controller.start()

        eventually { controller.state.value == GatewayState.Failed("Install the Hermes image first.") }
        assertTrue(sessions.running.value.isEmpty())
    }

    @Test
    fun aSessionThatCannotOpenIsShownWithItsReason() {
        prepare = { SessionManager.SessionFactory { alias, _ -> error("could not start a command session for $alias") } }

        controller.start()

        eventually { controller.state.value == GatewayState.Failed("could not start a command session for hermes-gateway") }
    }

    @Test
    fun aStartedGatewayIsNotStartedTwice() {
        controller.start()

        assertFalse(controller.start())
        eventually { ptys.size == 1 }
        assertEquals(1, prepared.get())
    }

    @Test
    fun stopWhileStartingWinsOverALaterHealthAnswer() {
        controller.start()
        eventually { ptys.size == 1 }

        controller.stop()
        probe.health = Health("v0.22.3")

        eventually { controller.state.value == GatewayState.Stopped(1) }
    }

    @Test
    fun stopBeforeTheSessionOpensOpensNothing() {
        val release = CountDownLatch(1)
        val inPrepare = CountDownLatch(1)
        val factory = prepare
        prepare = {
            inPrepare.countDown()
            release.await(5, TimeUnit.SECONDS)
            factory()
        }
        controller.start()
        assertTrue(inPrepare.await(5, TimeUnit.SECONDS))

        assertTrue(controller.stop())
        release.countDown()

        eventually { controller.state.value == GatewayState.Stopped() }
        assertTrue(ptys.isEmpty())
        assertTrue(sessions.running.value.isEmpty())
    }

    @Test
    fun aStoppedGatewayStartsAgainInANewSession() {
        controller.start()
        eventually { ptys.size == 1 }
        ptys.single().fake.exit(1)
        eventually { controller.state.value is GatewayState.StoppedUnexpectedly }

        assertTrue(controller.start())

        eventually { ptys.size == 2 && sessions.running.value == listOf("hermes-gateway") }
    }

    @Test
    fun everyStartCreatesTheProfileDirectoryBeforeTheSessionOpens() {
        prepare = {
            SessionManager.SessionFactory { alias, onEnded ->
                check(files.profileDir.isDirectory) { "the profile directory is missing" }
                ManagedSession(
                    alias = alias,
                    pty = GatewayPty().also { ptys += it },
                    emulator = null,
                    output = log::append,
                    onEnded = onEnded,
                    endGraceMs = 200,
                    signalWaitMs = 500,
                    exitCodeWaitMs = 100,
                )
            }
        }
        files.profileDir.deleteRecursively()

        controller.start()

        eventually { sessions.running.value == listOf("hermes-gateway") }
        assertTrue(files.profileDir.isDirectory)
        ptys.single().fake.exit(1)
        eventually { controller.state.value is GatewayState.StoppedUnexpectedly }

        files.profileDir.deleteRecursively()
        assertTrue(controller.start())

        eventually { ptys.size == 2 && sessions.running.value == listOf("hermes-gateway") }
        assertTrue(files.profileDir.isDirectory)
    }

    @Test
    fun aProfileThatCannotBeCreatedIsShownWithItsReason() {
        files.hrDir.parentFile.mkdirs()
        files.hrDir.writeText("not a directory")

        controller.start()

        eventually { (controller.state.value as? GatewayState.Failed)?.reason?.contains("could not create the profile directory") == true }
        assertEquals(0, prepared.get())
    }

    @Test
    fun stopDoesNothingWhenNothingRuns() {
        assertFalse(controller.stop())
        assertEquals(GatewayState.Stopped(), controller.state.value)
    }

    private companion object {
        const val GATEWAY_PID = 1810
    }
}
