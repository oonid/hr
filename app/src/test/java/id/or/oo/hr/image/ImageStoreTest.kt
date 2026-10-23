package id.or.oo.hr.image

import id.or.oo.hr.HrFiles
import id.or.oo.pr.session.DistroCommand
import id.or.oo.pr.session.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files by lazy { HrFiles(tmp.root) }
    private val build = ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3")
    private val calls = CopyOnWriteArrayList<List<String>>()
    private var exitCode = 0
    private var inUse = false
    private var gate: CompletableDeferred<Unit>? = null
    private var registry = ImageRegistry { build }

    private val command = DistroCommand { args, onLine ->
        calls += args
        gate?.await()
        when (args.first()) {
            "install" -> {
                onLine("Downloading OCI layer sha256:edab979e...")
                if (exitCode == 0) files.rootfs(args[3]).mkdirs()
            }
            "remove" -> if (exitCode == 0) files.containerDir(args[1]).deleteRecursively()
        }
        exitCode
    }

    private fun store() = ImageStore(command, { registry.resolve(it) }, files, scope, { inUse }, clock = { 1_700_000_000_000 })

    private fun ImageStore.awaitIdle() = eventually { operation.value?.running == false }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun installResolvesThePullTagAndInstallsTheBuildTagUnderTheAlias() {
        val store = store()

        assertTrue(store.install())
        store.awaitIdle()

        assertEquals(
            listOf(listOf("install", "ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway", "--override-alias", "hermes")),
            calls,
        )
        val installed = InstalledImage("ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway", build.tag, "v0.22.3", 1_700_000_000_000)
        assertEquals(installed, store.installed.value)
        assertEquals(installed, store().installed.value)
        assertEquals(
            listOf(
                "Resolving ghcr.io/oonid/hermes-agent:gateway…",
                "Installing ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway as hermes…",
                "Downloading OCI layer sha256:edab979e...",
                "Installed v0.22.3-pab570a0b-gateway.",
            ),
            store.operation.value!!.lines,
        )
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun aRegistryFailureStopsBeforePrCli() {
        registry = ImageRegistry { throw IllegalStateException("ghcr.io/oonid/hermes-agent:gateway: token: HTTP 503") }
        val store = store()

        store.install()
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals("ERROR: ghcr.io/oonid/hermes-agent:gateway: token: HTTP 503", store.operation.value!!.lines.last())
        assertFalse(store.operation.value!!.succeeded)
        assertNull(store.installed.value)
    }

    @Test
    fun aPrCliFailureIsShownAndNothingIsRecorded() {
        exitCode = 1
        val store = store()

        store.install()
        store.awaitIdle()

        assertEquals("pr-cli exited with 1.", store.operation.value!!.lines.last())
        assertFalse(store.operation.value!!.succeeded)
        assertNull(store.installed.value)
        assertFalse(files.imageRecord.exists())
    }

    @Test
    fun oneOperationRunsAtATime() {
        gate = CompletableDeferred()
        val store = store()

        assertTrue(store.install())
        eventually { calls.size == 1 }
        assertFalse(store.install())
        assertFalse(store.remove())
        gate!!.complete(Unit)
        store.awaitIdle()

        assertEquals(1, calls.size)
    }

    @Test
    fun anInstalledImageIsNotInstalledAgain() {
        val store = store()
        store.install()
        store.awaitIdle()

        assertFalse(store.install())
        assertEquals(1, calls.size)
    }

    @Test
    fun removeDeletesTheContainerAndTheRecordAndKeepsTheProfile() {
        val profileFile = File(files.profileDir, "config.yaml").apply {
            parentFile!!.mkdirs()
            writeText("model: x\n")
        }
        val store = store()
        store.install()
        store.awaitIdle()

        assertTrue(store.remove())
        store.awaitIdle()

        assertEquals(listOf("remove", "hermes"), calls.last())
        assertNull(store.installed.value)
        assertFalse(files.imageRecord.exists())
        assertFalse(store.containerExists)
        assertEquals("model: x\n", profileFile.readText())
        assertEquals("Removed. The profile in hr's files is kept.", store.operation.value!!.lines.last())
    }

    @Test
    fun removeIsRefusedWhileASessionRuns() {
        val store = store()
        store.install()
        store.awaitIdle()
        inUse = true

        assertFalse(store.remove())

        assertEquals(listOf("Stop the gateway and end the terminal before removing the image."), store.operation.value!!.lines)
        assertEquals(1, calls.size)
    }

    @Test
    fun busyIsTrueOnlyWhileAnOperationRuns() {
        val store = store()
        assertFalse(store.busy)
        gate = CompletableDeferred()

        store.install()
        assertTrue(store.busy)
        gate!!.complete(Unit)
        store.awaitIdle()

        assertFalse(store.busy)
    }

    @Test
    fun containerPresentFollowsInstallRemoveAndRefresh() {
        val store = store()
        assertFalse(store.containerPresent.value)

        store.install()
        store.awaitIdle()
        assertTrue(store.containerPresent.value)

        files.containerDir("hermes").deleteRecursively()
        store.refresh()
        assertFalse(store.containerPresent.value)

        files.rootfs("hermes").mkdirs()
        store.refresh()
        assertTrue(store.containerPresent.value)
    }

    @Test
    fun aRecordWithoutItsContainerReadsAsNotInstalled() {
        val store = store()
        store.install()
        store.awaitIdle()
        files.containerDir("hermes").deleteRecursively()

        store.refresh()

        assertNull(store.installed.value)
        assertNull(store().installed.value)
    }

    @Test
    fun aFinishedOperationCanBeDismissedButNotARunningOne() {
        gate = CompletableDeferred()
        val store = store()
        store.install()

        assertFalse(store.dismiss())
        gate!!.complete(Unit)
        store.awaitIdle()
        assertTrue(store.dismiss())

        assertNull(store.operation.value)
    }

    @Test
    fun aCommandThatThrowsEndsTheOperationWithItsMessage() {
        val store = ImageStore(
            { _, _ -> throw IllegalStateException("no pr-cli") },
            { build },
            files,
            scope,
            { false },
        )

        store.install()
        store.awaitIdle()

        assertEquals("ERROR: no pr-cli", store.operation.value!!.lines.last())
        assertFalse(store.operation.value!!.succeeded)
    }
}
