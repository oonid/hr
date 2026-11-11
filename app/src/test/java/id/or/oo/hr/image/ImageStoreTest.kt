package id.or.oo.hr.image

import id.or.oo.hr.HrFiles
import id.or.oo.hr.setup.GatewayRestart
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
import java.util.concurrent.atomic.AtomicInteger

class ImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files by lazy { HrFiles(tmp.root) }
    private val build = ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3")
    private val calls = CopyOnWriteArrayList<List<String>>()
    private var exitCode = 0
    private var cacheExitCode = 0
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
            "remove" -> if (exitCode == 0) {
                files.containerDir(args[1]).deleteRecursively()
                marker(args[1]).delete()
            }
        }
        if (args.first() == "clear-cache") cacheExitCode else exitCode
    }

    private val restartResults = ArrayDeque<String?>()
    private val events = CopyOnWriteArrayList<String>()
    private val restarts = AtomicInteger()
    private val stops = AtomicInteger()
    private var onRestart: () -> Unit = {}
    private var gatewayActive = true
    private var sessionsBlocking = false
    private var freeBytes = 10L * 1024 * 1024 * 1024

    private val restart = GatewayRestart {
        restarts.incrementAndGet()
        events += "restart"
        onRestart()
        synchronized(restartResults) { restartResults.removeFirstOrNull() }
    }

    private fun store(
        command: DistroCommand = this.command,
        registry: ImageRegistry = ImageRegistry { this.registry.resolve(it) },
        inUse: () -> Boolean = { this.inUse },
    ) = ImageStore(
        command,
        registry,
        files,
        scope,
        inUse,
        restart,
        gatewayActive = { gatewayActive },
        stopGateway = {
            stops.incrementAndGet()
            events += "stop"
        },
        sessionsBlocking = { sessionsBlocking },
        freeBytes = { freeBytes },
        clock = { 1_700_000_000_000 },
    )

    private fun marker(alias: String) = File(files.containerDir(alias).parentFile, ".$alias.pr-cli-installing")

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
            listOf(listOf("install", "ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway", "--override-alias", "hermes"), listOf("clear-cache")),
            calls,
        )
        val installed = InstalledImage("ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway", build.tag, "v0.22.3", 1_700_000_000_000, "hermes")
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

    private fun writeRecord(vararg extra: String) {
        files.imageRecord.parentFile.mkdirs()
        files.imageRecord.writeText(
            (listOf("reference=ghcr.io/oonid/hermes-agent:v1", "build_tag=v1", "version=v0.22.3", "installed_at=1") + extra)
                .joinToString("\n"),
        )
    }

    @Test
    fun aRecordWithoutAnAliasIsTheHermesContainer() {
        writeRecord()
        files.rootfs("hermes").mkdirs()

        assertEquals("hermes", store().installed.value?.alias)
        assertEquals("hermes", store().currentAlias())
    }

    @Test
    fun aRecordedAliasIsUsed() {
        writeRecord("alias=hermes-v1")
        files.rootfs("hermes-v1").mkdirs()

        val store = store()
        assertEquals("hermes-v1", store.installed.value?.alias)
        assertEquals("hermes-v1", store.currentAlias())
        assertTrue(store.containerExists)
    }

    @Test
    fun aRecordWhoseContainerIsMissingIsNotInstalled() {
        writeRecord("alias=hermes-v1")
        files.rootfs("hermes").mkdirs()

        val store = store()
        assertNull(store.installed.value)
        assertNull(store.currentAlias())
        assertFalse(store.containerExists)
    }

    @Test
    fun installRecordsTheHermesAlias() {
        val store = store()
        store.install()
        store.awaitIdle()

        assertTrue(files.imageRecord.readText().contains("alias=hermes"))
    }

    @Test
    fun removeRemovesTheRecordedAlias() {
        writeRecord("alias=hermes-v1")
        files.rootfs("hermes-v1").mkdirs()
        val store = store()

        store.remove()
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes-v1"), listOf("clear-cache")), calls)
        assertNull(store.installed.value)
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

        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
    }

    @Test
    fun anInstalledImageIsNotInstalledAgain() {
        val store = store()
        store.install()
        store.awaitIdle()

        assertFalse(store.install())
        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
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

        assertEquals(listOf(listOf("remove", "hermes"), listOf("clear-cache")), calls.takeLast(2))
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
        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
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
    fun aStartThatLandsDuringTheInUseCheckSeesTheRemoveAsBusy() {
        val seen = CompletableDeferred<Boolean>()
        val startRefused = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var store: ImageStore
        var first = true
        store = store(inUse = {
            if (first) {
                first = false
                Thread {
                    startRefused.set(store.busy)
                    seen.complete(true)
                }.start()
                Thread.sleep(300)
            }
            false
        })
        store.install()
        store.awaitIdle()
        gate = CompletableDeferred()

        val removeProceeded = store.remove()
        kotlinx.coroutines.runBlocking { seen.await() }

        assertTrue(removeProceeded)
        assertTrue(startRefused.get())
        gate!!.complete(Unit)
        store.awaitIdle()
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
        val store = store(command = { _, _ -> throw IllegalStateException("no pr-cli") })

        store.install()
        store.awaitIdle()

        assertEquals("ERROR: no pr-cli", store.operation.value!!.lines.last())
        assertFalse(store.operation.value!!.succeeded)
    }

    private val v2 = ImageBuild("v2", "v0.23.0")
    private val v3 = ImageBuild("v3", "v0.24.0")

    private fun installedV1(vararg extra: String): ImageStore {
        writeRecord(*extra)
        files.rootfs("hermes").mkdirs()
        return store()
    }

    private fun previousRecord(alias: String, tag: String) = arrayOf(
        "previous.reference=ghcr.io/oonid/hermes-agent:$tag",
        "previous.build_tag=$tag",
        "previous.version=v0.22.0",
        "previous.installed_at=2",
        "previous.alias=$alias",
    )

    private fun ImageStore.lastLine() = operation.value!!.lines.last()

    private fun resetCounts() {
        calls.clear()
        events.clear()
        restarts.set(0)
        stops.set(0)
    }

    @Test
    fun anUpdateInstallsBesideAndSwitches() {
        val store = installedV1()

        assertTrue(store.update(v2))
        store.awaitIdle()

        assertEquals(listOf(listOf("install", "ghcr.io/oonid/hermes-agent:v2", "--override-alias", "hermes-v2"), listOf("clear-cache")), calls)
        val updated = InstalledImage("ghcr.io/oonid/hermes-agent:v2", "v2", "v0.23.0", 1_700_000_000_000, "hermes-v2")
        val old = InstalledImage("ghcr.io/oonid/hermes-agent:v1", "v1", "v0.22.3", 1, "hermes")
        assertEquals(updated, store.installed.value)
        assertEquals(old, store.previous.value)
        assertNull(store.interrupted.value)
        assertEquals(updated, store().installed.value)
        assertEquals(old, store().previous.value)
        assertFalse(files.imageRecord.readText().contains("pending."))
        assertEquals(1, restarts.get())
        assertEquals(0, stops.get())
        assertTrue(files.rootfs("hermes").isDirectory)
        assertEquals(ImageStore.Kind.Update, store.operation.value!!.kind)
        assertEquals("Updated to v2. The previous image (v1) is kept until you remove it.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun aFailedStartSwitchesBack() {
        restartResults += listOf("port 8642 in use", null)
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals("hermes", store.installed.value?.alias)
        assertEquals("hermes-v2", store.previous.value?.alias)
        assertEquals("hermes", store().installed.value?.alias)
        assertEquals("hermes-v2", store().previous.value?.alias)
        assertNull(store.interrupted.value)
        assertEquals(2, restarts.get())
        assertEquals(0, stops.get())
        assertEquals("The new image did not start (port 8642 in use). hr is back on v1.", store.lastLine())
        assertFalse(store.operation.value!!.succeeded)
    }

    @Test
    fun whenBothImagesFailToStartBothReasonsAreShown() {
        restartResults += listOf("port 8642 in use", "the gateway stopped with exit code 1")
        gatewayActive = false
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals("hermes", store.installed.value?.alias)
        assertEquals(listOf("restart", "restart", "stop"), events)
        assertEquals(
            "The new image did not start (port 8642 in use). hr is back on v1. " +
                "The previous image did not start either (the gateway stopped with exit code 1).",
            store.lastLine(),
        )
    }

    @Test
    fun aStoppedGatewayIsStoppedAgainAfterSwitchingBack() {
        restartResults += listOf("port 8642 in use", null)
        gatewayActive = false
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals(listOf("restart", "restart", "stop"), events)
    }

    @Test
    fun retryingAFailedUpdateReusesItsContainer() {
        restartResults += listOf("port 8642 in use", null)
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        resetCounts()

        assertTrue(store.update(v2))
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertEquals("hermes", store.previous.value?.alias)
        assertEquals(1, restarts.get())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun aFailedInstallSwitchesNothing() {
        exitCode = 1
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals("hermes", store.installed.value?.alias)
        assertNull(store.previous.value)
        assertEquals(v2, store.interrupted.value)
        assertEquals("v2", store().interrupted.value?.tag)
        assertEquals("hermes", store().installed.value?.alias)
        assertEquals(0, restarts.get())
        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertFalse(store.operation.value!!.succeeded)
    }

    @Test
    fun anInstallThatThrowsLeavesTheUpdateInterrupted() {
        writeRecord()
        files.rootfs("hermes").mkdirs()
        val store = store(command = { _, _ -> throw IllegalStateException("no pr-cli") })

        store.update(v2)
        store.awaitIdle()

        assertEquals("ERROR: no pr-cli", store.lastLine())
        assertEquals("hermes", store.installed.value?.alias)
        assertEquals(v2, store.interrupted.value)
    }

    @Test
    fun anInterruptedUpdateIsNotReportedWhileItsRetryRuns() {
        gate = CompletableDeferred()
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        assertEquals(v2, store.interrupted.value)

        store.update(v2)
        eventually { calls.size == 1 }

        assertNull(store.interrupted.value)
        gate!!.complete(Unit)
        store.awaitIdle()
        assertNull(store.interrupted.value)
        assertEquals("hermes-v2", store.installed.value?.alias)
    }

    @Test
    fun lowSpaceRefusesTheUpdate() {
        freeBytes = 1L * 1024 * 1024 * 1024
        val store = installedV1()
        val before = files.imageRecord.readText()

        store.update(v2)
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals(before, files.imageRecord.readText())
        assertEquals(listOf("Updating needs 2 GB free; 1024 MB are free."), store.operation.value!!.lines)
        assertFalse(store.operation.value!!.succeeded)
        assertEquals(0, restarts.get())
    }

    @Test
    fun anOpenTerminalRefusesTheUpdate() {
        sessionsBlocking = true
        val store = installedV1()
        val before = files.imageRecord.readText()

        store.update(v2)
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals(before, files.imageRecord.readText())
        assertEquals(listOf("End the terminal and wait for Setup and the login to finish before updating."), store.operation.value!!.lines)
        assertFalse(store.operation.value!!.succeeded)
        assertEquals(0, restarts.get())
    }

    @Test
    fun anUpdateNeedsAnInstalledImageAndANewBuild() {
        assertFalse(store().update(v2))
        val store = installedV1()

        assertFalse(store.update(ImageBuild("v1", "v0.22.3")))
        assertNull(store.operation.value)
    }

    @Test
    fun aStoppedGatewayIsStoppedAgainAfterTheUpdate() {
        gatewayActive = false
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals(1, stops.get())
        assertEquals(listOf("restart", "stop"), events)
    }

    @Test
    fun anInterruptedInstallIsReported() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        marker("hermes-v2").apply { parentFile!!.mkdirs() }.writeText("")

        assertEquals("v2", store.interrupted.value?.tag)
        assertEquals(v2, store.interrupted.value)
        assertTrue(store.removeInterrupted())
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes-v2")), calls)
        assertNull(store.interrupted.value)
        assertNull(store().interrupted.value)
        assertFalse(files.imageRecord.readText().contains("pending."))
        assertEquals("hermes", store.installed.value?.alias)
        assertEquals("Removed v2.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun anInterruptedInstallWithNothingOnDiskIsClearedWithoutPrCli() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")

        assertTrue(store.removeInterrupted())
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertNull(store.interrupted.value)
        assertEquals(listOf("Removed v2."), store.operation.value!!.lines)
        assertFalse(store.removeInterrupted())
    }

    @Test
    fun aFailedRemovalKeepsTheInterruptedInstall() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        marker("hermes-v2").apply { parentFile!!.mkdirs() }.writeText("")
        exitCode = 1

        store.removeInterrupted()
        store.awaitIdle()

        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertEquals(v2, store.interrupted.value)
        assertEquals(v2, store().interrupted.value)
    }

    @Test
    fun aPendingInstallThatIsCurrentIsNotInterrupted() {
        writeRecord("alias=hermes-v2", "pending.alias=hermes-v2", "pending.build_tag=v2")
        files.rootfs("hermes-v2").mkdirs()

        assertNull(store().interrupted.value)
    }

    @Test
    fun aFinishedInstallWhoseSwitchWasLostIsReused() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        files.rootfs("hermes-v2").mkdirs()
        assertEquals(v2, store.interrupted.value)

        store.update(v2)
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertEquals("hermes", store.previous.value?.alias)
        assertNull(store.interrupted.value)
        assertEquals(1, restarts.get())
    }

    @Test
    fun anUnfinishedInstallIsInstalledAgain() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        files.rootfs("hermes-v2").mkdirs()
        marker("hermes-v2").writeText("")

        store.update(v2)
        store.awaitIdle()

        assertEquals(listOf(listOf("install", "ghcr.io/oonid/hermes-agent:v2", "--override-alias", "hermes-v2"), listOf("clear-cache")), calls)
        assertEquals("hermes-v2", store.installed.value?.alias)
    }

    @Test
    fun anOlderInterruptedInstallIsCleanedFirst() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        marker("hermes-v2").apply { parentFile!!.mkdirs() }.writeText("")

        store.update(v3)
        store.awaitIdle()

        assertEquals(
            listOf(
                listOf("remove", "hermes-v2"),
                listOf("install", "ghcr.io/oonid/hermes-agent:v3", "--override-alias", "hermes-v3"),
                listOf("clear-cache"),
            ),
            calls,
        )
        assertFalse(marker("hermes-v2").exists())
        assertEquals("hermes-v3", store.installed.value?.alias)
        assertEquals("hermes", store.previous.value?.alias)
        assertNull(store.interrupted.value)
    }

    @Test
    fun aFailedCleanupOfAnOlderInstallStopsTheUpdate() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        marker("hermes-v2").apply { parentFile!!.mkdirs() }.writeText("")
        val before = files.imageRecord.readText()
        exitCode = 1

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes-v2")), calls)
        assertEquals(before, files.imageRecord.readText())
        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertEquals(v2, store.interrupted.value)
        assertEquals(0, restarts.get())
    }

    @Test
    fun anOlderInterruptedInstallWithNothingOnDiskIsReplaced() {
        val store = installedV1("pending.alias=hermes-v2", "pending.build_tag=v2", "pending.version=v0.23.0")
        exitCode = 1

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf(listOf("install", "ghcr.io/oonid/hermes-agent:v3", "--override-alias", "hermes-v3")), calls)
        assertEquals(v3, store.interrupted.value)
    }

    @Test
    fun anUpdateRemovesTheImageTwoBuildsOld() {
        writeRecord(*arrayOf("alias=hermes-v1") + previousRecord("hermes-v0", "v0"))
        files.rootfs("hermes-v1").mkdirs()
        files.rootfs("hermes-v0").mkdirs()
        val store = store()
        val keptDuringRestart = CopyOnWriteArrayList<Boolean>()
        val recordDuringRestart = CopyOnWriteArrayList<String>()
        onRestart = {
            keptDuringRestart += files.rootfs("hermes-v0").isDirectory
            recordDuringRestart += files.imageRecord.readText()
        }

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf(true), keptDuringRestart)
        assertTrue(recordDuringRestart.single().contains("older.alias=hermes-v0"))
        assertTrue(recordDuringRestart.single().contains("older.build_tag=v0"))
        assertFalse(files.imageRecord.readText().contains("older."))

        assertEquals(
            listOf(
                listOf("install", "ghcr.io/oonid/hermes-agent:v3", "--override-alias", "hermes-v3"),
                listOf("remove", "hermes-v0"),
                listOf("clear-cache"),
            ),
            calls,
        )
        assertEquals("hermes-v3", store.installed.value?.alias)
        assertEquals("hermes-v1", store.previous.value?.alias)
        assertFalse(files.imageRecord.readText().contains("hermes-v0"))
        assertEquals("Removed the older image (v0).", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun theImageTwoBuildsOldIsRemovedAfterSwitchingBackToo() {
        restartResults += listOf("port 8642 in use", null)
        writeRecord(*arrayOf("alias=hermes-v1") + previousRecord("hermes-v0", "v0"))
        files.rootfs("hermes-v1").mkdirs()
        files.rootfs("hermes-v0").mkdirs()
        val store = store()

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf("remove", "hermes-v0"), calls.last())
        assertEquals("hermes-v1", store.installed.value?.alias)
        assertEquals("hermes-v3", store.previous.value?.alias)
        assertEquals(
            listOf(
                "The new image did not start (port 8642 in use). hr is back on v1.",
                "Removing hermes-v0…",
                "Removed the older image (v0).",
            ),
            store.operation.value!!.lines.takeLast(3),
        )
        assertFalse(store.operation.value!!.succeeded)
    }

    @Test
    fun aFailedOlderRemovalKeepsItInTheRecord() {
        writeRecord(*arrayOf("alias=hermes-v1") + previousRecord("hermes-v0", "v0"))
        files.rootfs("hermes-v1").mkdirs()
        files.rootfs("hermes-v0").mkdirs()
        val store = store()
        onRestart = { exitCode = 1 }

        store.update(v3)
        store.awaitIdle()

        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertEquals("hermes-v3", store.installed.value?.alias)
        assertEquals("hermes-v1", store.previous.value?.alias)
        assertTrue(store.operation.value!!.succeeded)
        assertTrue(files.imageRecord.readText().contains("older.alias=hermes-v0"))
        exitCode = 0
        calls.clear()

        store().apply {
            assertTrue(removePrevious())
            awaitIdle()
        }

        assertEquals(listOf(listOf("remove", "hermes-v0"), listOf("remove", "hermes-v1")), calls)
        assertFalse(files.imageRecord.readText().contains("older."))
        assertFalse(files.imageRecord.readText().contains("previous."))
    }

    private fun currentV2OwingV1(): ImageStore {
        writeRecord(
            "reference=ghcr.io/oonid/hermes-agent:v2",
            "build_tag=v2",
            "alias=hermes-v2",
            "older.alias=hermes-v1",
            "older.build_tag=v1",
        )
        files.rootfs("hermes-v2").mkdirs()
        files.rootfs("hermes-v1").mkdirs()
        return store()
    }

    @Test
    fun aCrashBeforeTheOlderImageIsRemovedKeepsItInTheRecord() {
        val store = currentV2OwingV1()
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertNull(store.previous.value)

        store.update(v3)
        store.awaitIdle()

        assertEquals(
            listOf(
                listOf("remove", "hermes-v1"),
                listOf("install", "ghcr.io/oonid/hermes-agent:v3", "--override-alias", "hermes-v3"),
                listOf("clear-cache"),
            ),
            calls,
        )
        assertEquals(listOf("Removing hermes-v1…", "Removed the older image (v1)."), store.operation.value!!.lines.take(2))
        assertFalse(files.imageRecord.readText().contains("older."))
        assertEquals("hermes-v3", store.installed.value?.alias)
        assertEquals("hermes-v2", store.previous.value?.alias)
    }

    @Test
    fun aFailedOwedRemovalStopsTheUpdate() {
        val store = currentV2OwingV1()
        val before = files.imageRecord.readText()
        exitCode = 1

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes-v1")), calls)
        assertEquals(before, files.imageRecord.readText())
        assertEquals(listOf("Removing hermes-v1…", "pr-cli exited with 1."), store.operation.value!!.lines)
        assertFalse(store.operation.value!!.succeeded)
        assertEquals(0, restarts.get())
    }

    @Test
    fun anOwedRemovalWhoseContainerIsGoneIsCleared() {
        val store = currentV2OwingV1()
        files.containerDir("hermes-v1").deleteRecursively()
        marker("hermes-v1").writeText("")
        exitCode = 1

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf("remove", "hermes-v1"), calls.first())
        assertEquals("install", calls[1].first())
        assertFalse(files.imageRecord.readText().contains("older."))
    }

    @Test
    fun anOwedRemovalWithNothingOnDiskIsClearedWithoutPrCli() {
        val store = currentV2OwingV1()
        files.containerDir("hermes-v1").deleteRecursively()

        store.update(v3)
        store.awaitIdle()

        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
        assertFalse(files.imageRecord.readText().contains("older."))
    }

    @Test
    fun switchBackRemovesAnOwedOlderImageFirst() {
        writeRecord(
            *arrayOf("reference=ghcr.io/oonid/hermes-agent:v2", "build_tag=v2", "alias=hermes-v2") +
                previousRecord("hermes", "v1") + arrayOf("older.alias=hermes-v0", "older.build_tag=v0"),
        )
        files.rootfs("hermes-v2").mkdirs()
        files.rootfs("hermes").mkdirs()
        files.rootfs("hermes-v0").mkdirs()
        val store = store()

        store.switchBack()
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes-v0")), calls)
        assertEquals("hermes", store.installed.value?.alias)
        assertEquals("hermes-v2", store.previous.value?.alias)
        assertFalse(files.imageRecord.readText().contains("older."))
    }

    @Test
    fun anOlderEntryNamingAKeptImageIsNeverRemoved() {
        val store = installedV1("older.alias=hermes", "older.build_tag=v1")

        store.update(v2)
        store.awaitIdle()

        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
        assertTrue(files.rootfs("hermes").isDirectory)
        assertEquals("hermes", store.previous.value?.alias)
        assertFalse(files.imageRecord.readText().contains("older."))
    }

    @Test
    fun aPreviousWithALeftoverMarkerIsNotKept() {
        files.rootfs("hermes-v0").mkdirs()
        marker("hermes-v0").writeText("")
        val store = installedV1(*previousRecord("hermes-v0", "v0"))

        assertEquals("hermes", store.installed.value?.alias)
        assertNull(store.previous.value)
    }

    @Test
    fun aCurrentWithALeftoverMarkerFallsBackToThePrevious() {
        writeRecord(*arrayOf("alias=hermes-v2") + previousRecord("hermes", "v1"))
        files.rootfs("hermes-v2").mkdirs()
        marker("hermes-v2").writeText("")
        files.rootfs("hermes").mkdirs()

        assertEquals("hermes", store().installed.value?.alias)
    }

    @Test
    fun aCancelledOperationEndsWithoutAnError() {
        gate = CompletableDeferred()
        val store = installedV1()
        store.update(v2)
        eventually { calls.size == 1 }

        scope.cancel()
        store.awaitIdle()

        assertFalse(store.operation.value!!.lines.any { it.startsWith("ERROR") })
        assertFalse(store.operation.value!!.succeeded)
        assertEquals("hermes", store.installed.value?.alias)
    }

    @Test
    fun aMissingCurrentContainerFallsBackToThePrevious() {
        writeRecord(*arrayOf("alias=hermes-v2") + previousRecord("hermes", "v1"))
        files.rootfs("hermes").mkdirs()

        val store = store()

        assertEquals("hermes", store.installed.value?.alias)
        assertEquals("v1", store.installed.value?.buildTag)
        assertNull(store.previous.value)
        assertTrue(store.containerExists)
    }

    @Test
    fun aPreviousWithoutItsContainerIsNotKept() {
        val store = installedV1(*previousRecord("hermes-v0", "v0"))

        assertEquals("hermes", store.installed.value?.alias)
        assertNull(store.previous.value)
    }

    @Test
    fun switchBackUsesThePreviousImage() {
        assertFalse(installedV1().switchBack())
        val store = store()
        store.update(v2)
        store.awaitIdle()
        resetCounts()

        assertTrue(store.switchBack())
        store.awaitIdle()

        assertTrue(calls.isEmpty())
        assertEquals("hermes", store.installed.value?.alias)
        assertEquals("hermes-v2", store.previous.value?.alias)
        assertEquals(1, restarts.get())
        assertEquals(ImageStore.Kind.SwitchBack, store.operation.value!!.kind)
        assertEquals("Switched back to v1. The image v2 is kept until you remove it.", store.lastLine())
    }

    @Test
    fun switchBackIsRefusedWhileTheTerminalIsOpen() {
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        resetCounts()
        sessionsBlocking = true

        store.switchBack()
        store.awaitIdle()

        assertEquals(0, restarts.get())
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertEquals("End the terminal and wait for Setup and the login to finish before updating.", store.lastLine())
    }

    @Test
    fun removalsAreRefusedWhileSetupOrTheTerminalBlocks() {
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        resetCounts()
        sessionsBlocking = true

        assertTrue(store.removePrevious())
        store.awaitIdle()
        assertTrue(calls.isEmpty())
        assertEquals("hermes", store.previous.value?.alias)
        assertEquals("End the terminal and wait for Setup and the login to finish before updating.", store.lastLine())

        sessionsBlocking = false
        val interrupted = installedV1("pending.alias=hermes-v3", "pending.build_tag=v3", "pending.version=v0.24.0")
        sessionsBlocking = true
        assertTrue(interrupted.removeInterrupted())
        interrupted.awaitIdle()
        assertTrue(calls.isEmpty())
        assertEquals("v3", interrupted.interrupted.value?.tag)
        assertEquals("End the terminal and wait for Setup and the login to finish before updating.", interrupted.lastLine())
    }

    @Test
    fun removePreviousRemovesOnlyThePrevious() {
        val store = installedV1()
        assertFalse(store.removePrevious())
        store.update(v2)
        store.awaitIdle()
        resetCounts()

        assertTrue(store.removePrevious())
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes")), calls)
        assertNull(store.previous.value)
        assertNull(store().previous.value)
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertTrue(files.rootfs("hermes-v2").isDirectory)
        assertFalse(files.imageRecord.readText().contains("previous."))
        assertEquals("Removed v1.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun aFailedRemovalKeepsThePrevious() {
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        exitCode = 1

        store.removePrevious()
        store.awaitIdle()

        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertEquals("hermes", store.previous.value?.alias)
    }

    @Test
    fun removeRemovesEveryHermesContainer() {
        writeRecord(
            *arrayOf("alias=hermes-v2") + previousRecord("hermes", "v1") +
                arrayOf(
                    "pending.alias=hermes-v3",
                    "pending.build_tag=v3",
                    "pending.version=v0.24.0",
                    "older.alias=hermes-v1",
                    "older.build_tag=v1",
                ),
        )
        files.rootfs("hermes-v2").mkdirs()
        files.rootfs("hermes").mkdirs()
        marker("hermes-v3").writeText("")
        files.rootfs("hermes-v1").mkdirs()
        files.rootfs("hermes-v0").mkdirs()
        files.rootfs("other").mkdirs()
        val store = store()

        assertTrue(store.remove())
        store.awaitIdle()

        assertEquals(
            setOf(
                listOf("remove", "hermes-v3"),
                listOf("remove", "hermes"),
                listOf("remove", "hermes-v2"),
                listOf("remove", "hermes-v1"),
                listOf("remove", "hermes-v0"),
            ),
            calls.dropLast(1).toSet(),
        )
        assertEquals(6, calls.size)
        assertEquals(listOf(listOf("remove", "hermes-v2"), listOf("clear-cache")), calls.takeLast(2))
        assertFalse(files.imageRecord.exists())
        assertNull(store.installed.value)
        assertNull(store.previous.value)
        assertNull(store.interrupted.value)
        assertTrue(files.rootfs("other").isDirectory)
    }

    @Test
    fun aFailedRemoveKeepsWhatIsLeft() {
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        exitCode = 1

        store.remove()
        store.awaitIdle()

        assertEquals("pr-cli exited with 1.", store.lastLine())
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertEquals("hermes", store.previous.value?.alias)
    }

    @Test
    fun installAfterAPartialRemoveWritesAFreshRecord() {
        writeRecord(
            *arrayOf("alias=hermes-v3") + previousRecord("hermes-v0", "v0") +
                arrayOf(
                    "pending.alias=hermes-v2",
                    "pending.build_tag=v2",
                    "pending.version=v0.23.0",
                    "older.alias=hermes-v1",
                    "older.build_tag=v1",
                ),
        )
        files.rootfs("hermes-v3").mkdirs()
        marker("hermes-v3").writeText("")
        val store = store()
        assertNull(store.installed.value)

        store.install()
        store.awaitIdle()

        assertEquals("hermes", store.installed.value?.alias)
        assertNull(store.interrupted.value)
        assertNull(store().interrupted.value)
        val text = files.imageRecord.readText()
        assertFalse(text.contains("previous."))
        assertFalse(text.contains("pending."))
        assertFalse(text.contains("older."))
        assertFalse(text.contains("hermes-v3"))
    }

    @Test
    fun anOwedRemovalNeverTouchesTheCurrentImage() {
        writeRecord("alias=hermes-v3", "older.alias=hermes", "older.build_tag=v0")
        files.rootfs("hermes-v3").mkdirs()
        marker("hermes-v3").writeText("")
        val store = store()
        store.install()
        store.awaitIdle()
        calls.clear()

        store.update(v2)
        store.awaitIdle()

        assertFalse(calls.contains(listOf("remove", "hermes")))
        assertTrue(files.rootfs("hermes").isDirectory)
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertEquals("hermes", store.previous.value?.alias)
        assertFalse(files.imageRecord.readText().contains("older."))
    }

    @Test
    fun theGatewayMayStartDuringTheUpdatesRestart() {
        gate = CompletableDeferred()
        val store = installedV1()
        val inRestart = CopyOnWriteArrayList<Boolean>()
        onRestart = { inRestart += store.canLaunch }
        assertTrue(store.canLaunch)

        store.update(v2)
        eventually { calls.size == 1 }
        assertFalse(store.canLaunch)
        gate!!.complete(Unit)
        store.awaitIdle()

        assertEquals(listOf(true), inRestart)
        assertTrue(store.canLaunch)
    }

    @Test
    fun theGatewayMayNotStartDuringARemoval() {
        gate = CompletableDeferred()
        val store = installedV1()
        inUse = false

        store.remove()
        eventually { calls.size == 1 }

        assertFalse(store.canLaunch)
        gate!!.complete(Unit)
        store.awaitIdle()
    }

    @Test
    fun refreshWaitsForTheRunningOperation() {
        gate = CompletableDeferred()
        val store = installedV1()
        store.update(v2)
        eventually { calls.size == 1 }

        store.refresh()

        assertNull(store.interrupted.value)
        gate!!.complete(Unit)
        store.awaitIdle()
    }

    @Test
    fun aSwitchInterruptedDuringTheRestartIsReported() {
        writeRecord(*arrayOf("reference=ghcr.io/oonid/hermes-agent:v2", "build_tag=v2", "alias=hermes-v2", "starting.alias=hermes-v2") + previousRecord("hermes", "v1"))
        files.rootfs("hermes-v2").mkdirs()
        files.rootfs("hermes").mkdirs()

        val store = store()

        assertEquals(store.installed.value, store.unverified.value)
        assertEquals("hermes-v2", store.unverified.value?.alias)
        assertNull(installedV1("starting.alias=hermes-v2").unverified.value)
    }

    @Test
    fun aStartingKeyNamingAnotherImageIsDroppedOnTheNextCommit() {
        val store = installedV1("starting.alias=hermes-v0")
        assertNull(store.unverified.value)

        store.update(v2)
        store.awaitIdle()

        assertFalse(files.imageRecord.readText().contains("hermes-v0"))
    }

    @Test
    fun reachingRunningClearsTheNotice() {
        val store = installedV1("starting.alias=hermes")
        assertEquals("hermes", store.unverified.value?.alias)

        store.confirmRunning()

        assertNull(store.unverified.value)
        assertFalse(files.imageRecord.readText().contains("starting."))
        assertEquals("hermes", store.installed.value?.alias)
        assertNull(store().unverified.value)
    }

    @Test
    fun aFinishedUpdateLeavesNoStartingKey() {
        val recordDuringRestart = CopyOnWriteArrayList<String>()
        onRestart = { recordDuringRestart += files.imageRecord.readText() }
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()
        assertTrue(store.operation.value!!.succeeded)
        assertFalse(files.imageRecord.readText().contains("starting."))

        restartResults += listOf("port 8642 in use", null)
        store.update(v3)
        store.awaitIdle()
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertFalse(files.imageRecord.readText().contains("starting."))

        restartResults += listOf("port 8642 in use", "the gateway stopped with exit code 1")
        store.update(v3)
        store.awaitIdle()
        assertEquals("hermes-v2", store.installed.value?.alias)
        assertFalse(files.imageRecord.readText().contains("starting."))

        store.switchBack()
        store.awaitIdle()
        assertEquals("hermes-v3", store.installed.value?.alias)
        assertTrue(store.operation.value!!.succeeded)
        assertFalse(files.imageRecord.readText().contains("starting."))
        assertNull(store.unverified.value)

        assertEquals(
            listOf("hermes-v2", "hermes-v3", null, "hermes-v3", null, "hermes-v3"),
            recordDuringRestart.map { Regex("starting.alias=(\\S+)").find(it)?.groupValues?.get(1) },
        )
    }

    @Test
    fun confirmRunningWaitsForTheOperation() {
        lateinit var store: ImageStore
        val duringRestart = CopyOnWriteArrayList<String?>()
        onRestart = {
            store.confirmRunning()
            duringRestart += Regex("starting.alias=(\\S+)").find(files.imageRecord.readText())?.groupValues?.get(1)
            duringRestart += store.unverified.value?.alias
        }
        store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals(listOf("hermes-v2", "hermes-v2"), duringRestart)
        assertNull(store.unverified.value)
    }

    @Test
    fun confirmRunningWithNothingToClearWritesNothing() {
        val store = installedV1()
        val before = files.imageRecord.readText()
        files.imageRecord.setLastModified(1_000)

        store.confirmRunning()

        assertEquals(before, files.imageRecord.readText())
        assertEquals(1_000, files.imageRecord.lastModified())
    }

    private val clearCache = listOf("clear-cache")

    @Test
    fun anInstallClearsTheDownloadCache() {
        val store = store()

        store.install()
        store.awaitIdle()

        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
        assertEquals(clearCache, calls.last())
        assertEquals("Installed v0.22.3-pab570a0b-gateway.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun anUpdateThatDownloadedClearsTheCache() {
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals(listOf("install", "clear-cache"), calls.map { it.first() })
        assertEquals(listOf("restart"), events)
        assertEquals("Updated to v2. The previous image (v1) is kept until you remove it.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
    }

    @Test
    fun anUpdateReusingAContainerDoesNotClearIt() {
        restartResults += listOf("port 8642 in use", null)
        val store = installedV1()
        store.update(v2)
        store.awaitIdle()
        assertFalse(calls.contains(clearCache))
        resetCounts()

        store.update(v2)
        store.awaitIdle()

        assertTrue(store.operation.value!!.succeeded)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aFailedCacheClearDoesNotFailTheUpdate() {
        cacheExitCode = 3
        val store = installedV1()

        store.update(v2)
        store.awaitIdle()

        assertEquals(clearCache, calls.last())
        assertEquals("pr-cli exited with 3.", store.lastLine())
        assertTrue(store.operation.value!!.succeeded)
        assertEquals("hermes-v2", store.installed.value?.alias)
    }

    @Test
    fun aFailedInstallKeepsTheCache() {
        exitCode = 1
        store().apply {
            install()
            awaitIdle()
        }
        installedV1().apply {
            update(v2)
            awaitIdle()
        }

        assertEquals(listOf("install", "install"), calls.map { it.first() })
    }

    @Test
    fun removeClearsTheCacheAfterwardsAndOnlyWhenItSucceeded() {
        val store = installedV1()
        exitCode = 1
        store.remove()
        store.awaitIdle()
        assertFalse(calls.contains(clearCache))
        exitCode = 0
        calls.clear()

        store.remove()
        store.awaitIdle()

        assertEquals(listOf(listOf("remove", "hermes"), clearCache), calls)
        assertTrue(store.operation.value!!.succeeded)
    }
}
