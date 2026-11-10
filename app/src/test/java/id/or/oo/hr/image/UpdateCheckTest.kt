package id.or.oo.hr.image

import id.or.oo.pr.session.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class UpdateCheckTest {

    private val v1 = ImageBuild("v1-pa-gateway", "v1")
    private val v2 = ImageBuild("v2-pb-gateway", "v2")
    private val installed = MutableStateFlow<InstalledImage?>(image(v1))
    private val store = MemoryStore()
    private var latest = v1
    private var failure: Exception? = null
    private var calls = 0
    private var now = 1_000_000_000L

    private val registry = ImageRegistry { tag ->
        calls++
        assertEquals("gateway", tag)
        failure?.let { throw it }
        latest
    }

    private class MemoryStore : UpdateCheck.Store {
        override var lastCheckedAt = 0L
        override var lastLatestTag: String? = null
        override var lastLatestVersion: String? = null
    }

    private fun image(build: ImageBuild) = InstalledImage("ref", build.tag, build.version, 1L, "hermes")

    private fun check() = UpdateCheck(
        registry,
        installed,
        store,
        CoroutineScope(Dispatchers.Unconfined),
        { now },
        Dispatchers.Unconfined,
    )

    private fun gated(gate: CountDownLatch, asked: AtomicInteger, answer: ImageBuild) = UpdateCheck(
        { asked.incrementAndGet(); gate.await(); answer },
        installed,
        store,
        CoroutineScope(Dispatchers.Unconfined),
        { now },
        Dispatchers.IO,
    )

    @Test
    fun aDifferentBuildIsAvailable() {
        latest = v2
        val check = check()
        check.check(force = false)
        assertEquals(UpdateState.Available(v1.tag, v2), check.state.value)
    }

    @Test
    fun theSameBuildIsUpToDate() {
        val check = check()
        check.check(force = false)
        assertEquals(UpdateState.UpToDate(v1.tag), check.state.value)
    }

    @Test
    fun aRegistryErrorIsReportedAndRetriedNextTime() {
        failure = RuntimeException("no network")
        val check = check()
        check.check(force = false)
        assertEquals(UpdateState.Failed("no network"), check.state.value)
        assertEquals(0L, store.lastCheckedAt)
        failure = null
        check.check(force = false)
        assertEquals(2, calls)
        assertEquals(UpdateState.UpToDate(v1.tag), check.state.value)
    }

    @Test
    fun anErrorWithoutAMessageNamesItsClass() {
        failure = IllegalStateException()
        val check = check()
        check.check(force = true)
        assertEquals(UpdateState.Failed("IllegalStateException"), check.state.value)
    }

    @Test
    fun anUnforcedCheckWithinADayDoesNotAskTheRegistry() {
        latest = v2
        val first = check()
        first.check(force = false)
        now += 23 * 3_600_000L
        val second = check()
        second.check(force = false)
        assertEquals(1, calls)
        assertEquals(UpdateState.Available(v1.tag, v2), second.state.value)
    }

    @Test
    fun anUnforcedCheckAfterADayAsksAgain() {
        val check = check()
        check.check(force = false)
        now += 24 * 3_600_000L
        check.check(force = false)
        assertEquals(2, calls)
    }

    @Test
    fun aForcedCheckAlwaysAsks() {
        val check = check()
        check.check(force = false)
        check.check(force = true)
        assertEquals(2, calls)
    }

    @Test
    fun nothingInstalledMeansNoCheck() {
        installed.value = null
        val check = check()
        check.check(force = true)
        assertEquals(0, calls)
        assertEquals(UpdateState.Unknown, check.state.value)
    }

    @Test
    fun anUpdatedImageIsUpToDate() {
        store.lastCheckedAt = now
        store.lastLatestTag = v2.tag
        store.lastLatestVersion = v2.version
        val check = check()
        assertEquals(UpdateState.Available(v1.tag, v2), check.state.value)
        installed.value = image(v2)
        assertEquals(UpdateState.UpToDate(v2.tag), check.state.value)
        assertEquals(0, calls)
    }

    @Test
    fun nothingStoredIsUnknown() {
        val check = check()
        assertEquals(UpdateState.Unknown, check.state.value)
        installed.value = image(v2)
        assertEquals(UpdateState.Unknown, check.state.value)
        installed.value = null
        assertEquals(UpdateState.Unknown, check.state.value)
    }

    @Test
    fun aCheckTimeInTheFutureIsStale() {
        store.lastCheckedAt = now + 3_600_000L
        val check = check()
        check.check(force = false)
        assertEquals(1, calls)
    }

    @Test
    fun aSecondCheckWhileCheckingIsIgnored() {
        val gate = CountDownLatch(1)
        val asked = AtomicInteger()
        val check = gated(gate, asked, v2)
        check.check(force = false)
        eventually { asked.get() == 1 }
        check.check(force = true)
        assertEquals(UpdateState.Checking, check.state.value)
        gate.countDown()
        eventually { check.state.value is UpdateState.Available }
        assertEquals(1, asked.get())
    }

    @Test
    fun anInstallDuringACheckIsComparedWithTheNewBuild() {
        val gate = CountDownLatch(1)
        val asked = AtomicInteger()
        val check = gated(gate, asked, v2)
        check.check(force = false)
        eventually { asked.get() == 1 }
        installed.value = image(v2)
        assertEquals(UpdateState.Checking, check.state.value)
        gate.countDown()
        eventually { check.state.value != UpdateState.Checking }
        assertEquals(UpdateState.UpToDate(v2.tag), check.state.value)
    }
}
