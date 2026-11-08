# hr M1: Gateway on the Phone Implementation Plan

> Execute this plan task by task, with a review after each. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A first hr app that installs the Hermes `gateway` image, starts, stops and Ends the Hermes gateway in the background, shows its status (health, uptime, version) and its log, opens a terminal into the container beside it, and keeps the Hermes profile in hr's own files.

**Architecture:** hr is a Kotlin/Compose app (`:app`) that includes pr's `:proot-engine`, `:termlib` and `:session-core` from the `vendor/pr` submodule. `HrApp` implements `ProotHost` and `SessionHost`, runs `PrBootstrap`, and owns one `SessionManager` whose default factory is a terminal (`ProotSessionFactory`) into container `hermes` under key `hermes`. The gateway is a command session (`CommandSessionFactory`) in the same container under key `hermes-gateway`, with a 65 s signal wait. Both sessions bind `files/hr/profile` to `/root/.hermes/profiles/android`. JVM-testable units sit behind small interfaces: `ProfileStore` (profile directory, listener key, root `.env`), `LogBuffer` (the application-scoped sink: streaming UTF-8, ANSI and CR stripped, bounded, keys redacted), `ImageStore` with `GhcrRegistry` (resolve `:gateway` to its build tag, install it through `PrCliCommand`, remove it), and `GatewayController` (port check, prepare, open, health polling, the states, End). Task 0 adds the per-session signal wait and terminal binds to pr's `:session-core` first, in its own pull request.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, JVM 17, Compose BOM 2025.05.00 with Material 3, activity-compose 1.9.3, lifecycle 2.8.7, androidx.core 1.15.0, kotlinx-coroutines 1.9.0, `org.json` (Android's; `org.json:json:20240303` on the JVM test classpath), JUnit 4.13.2, pr's `:session-core` test fixtures (`FakePty`, `eventually`), AGP unit-test coverage (JaCoCo), GitHub Actions.

**Design:** `docs/design/hr-app.md` (M1)

## Global Constraints

- hr repository `hr.oo.or.id` (github.com/oonid/hr), branch `feat/m1-gateway`, which holds this plan; every hr command runs from the repository root.
- Task 0 runs in a pr checkout at `../pr.oo.or.id` (github.com/oonid/pr), on a new branch `feat/session-core-drain-and-binds` from `main` at `5414634`; its commands run from that checkout's root unless they start with `cd android`.
- App id and namespace `id.or.oo.hr`; `minSdk = 28`, `targetSdk = 35`, `compileSdk = 36`; `abiFilters` `arm64-v8a` only; `versionCode = 1`, `versionName = "0.1.0"`.
- Versions match pr's build: AGP `8.7.3`, Kotlin and its compose plugin `2.1.0` (declared in hr's root build because `:termlib` applies it), Gradle wrapper `8.11.1` (copied from pr), Java and Kotlin targets 17, Compose BOM `2025.05.00`, `activity-compose:1.9.3`, `lifecycle-runtime-ktx` and `lifecycle-runtime-compose:2.8.7`, `core-ktx:1.15.0`, `kotlinx-coroutines-core` and `-android:1.9.0`, `junit:4.13.2`, `org.json:json:20240303` (tests only).
- pr submodule: `vendor/pr` from `https://github.com/oonid/pr.git`, pinned at `5414634` in Task 1 and moved in Task 3 to pr's `main` with Task 0 merged; `.gitmodules` sets `ignore = untracked` (the included modules write `.cxx/` into the checkout); pr's own `vendor/termlib` (`cc5f2fd`) is initialised; never `--recursive` (pr's proot, busybox, musl, bionic and samba submodules are only for rebuilding its prebuilt binaries, and some URLs are SSH-only).
- `settings.gradle.kts` locates pr through `prCandidates`: `-PprRoot=<path>`, then `vendor/pr`, then `../pr.oo.or.id`; it includes `:termlib`, `:proot-engine` and `:session-core` from there, and hr's own `:app`.
- `gradle.properties` sets `android.experimental.enableTestFixturesKotlinSupport=true`: hr's tests use `testFixtures(project(":session-core"))`.
- Container alias `hermes`. Session keys: `hermes` (the terminal, `SessionManager`'s default factory) and `hermes-gateway` (the command session). Both run in container `hermes` side by side.
- Image: repository `ghcr.io/oonid/hermes-agent`, pull tag `gateway`; hr reads the build tag behind it from the registry (labels `org.opencontainers.image.version`, `io.github.oonid.hermes.patch-hash`, `io.github.oonid.hermes.variant`), installs that immutable tag (for example `ghcr.io/oonid/hermes-agent:v0.22.3-pab570a0b-gateway`) with `pr-cli install <ref> --override-alias hermes`, and records it.
- Files under the app's `filesDir`: `usr` (pr's prefix), `home`, `hr/profile` (the profile), `hr/api_server_key` (64 lowercase hex characters, mode `rw-------`), `hr/image.properties` (the installed build). The container root is `usr/var/lib/pr/containers/hermes/rootfs`.
- Bind: `files/hr/profile` → `/root/.hermes/profiles/android`, in the terminal and in the gateway session; the host directory exists before every open.
- Listener settings, merged into the container's `/root/.hermes/.env` (mode `rw-------`) before every start, other lines kept: `API_SERVER_ENABLED=true`, `API_SERVER_KEY=<hr/api_server_key>`, `API_SERVER_HOST=127.0.0.1`, `API_SERVER_PORT=8642`.
- Gateway command, ONE argument after `--`, not quoted again in Kotlin: `cd /root && HERMES_HOME=/root/.hermes exec hermes gateway run`. Health: `GET http://127.0.0.1:8642/health` → `{"status": "ok", ..., "version": "..."}`. Signal wait for the gateway's End: `65_000` ms.
- Reserved by `:session-core` and not used by hr for anything else: wake-lock tag `pr:sessions`, preferences `pr_prefs`, notification id 1, channels `sessions_running` and `sessions_ended`. hr's preferences file is `hr_prefs`; `PrBootstrap` version `1`.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; `android:extractNativeLibs="true"` with `packaging.jniLibs.useLegacyPackaging = true`; `id.or.oo.pr.session.SessionService` declared with hr's own `specialUse` text; cleartext HTTP allowed to `127.0.0.1` only.
- Not modified by hr's tasks: anything under `vendor/`.
- Code style (pr's AGENTS.md, repeated in hr's): no comments; follow each file's patterns.
- Commits: type `feat|fix|chore|docs|test|build|ci`, subject plus a body wrapped at 72 columns or fewer, no trailers, no mention of AI or assistants. Stage explicit paths, never `git add -A` or `git add .`.
- No device model numbers, marketing names or serials anywhere; adb commands use bare `adb`.
- Keys never appear in logcat, the in-app log, the UI, commits or the pull request.
- Tests: `./gradlew :app:testDebugUnitTest`. Per-class counts: `for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done`.
- Coverage gate: every unit that runs on the JVM keeps 80% of its lines or more: `HrFiles`, `PrivateFile`, `EnvFile`, `ProfileStore`, `LogBuffer`, `Http`, `ImageRegistry`, `ImageStore`, `GatewayState`, `GatewayProbe`, `GatewayController`, `GatewayText`. Android glue (`HrApp`, `MainActivity`, `TerminalActivity`, `ui/*`) has no JVM coverage and is checked on a phone in Task 8. The coverage command, used by Tasks 2 and 4–6:

  ```bash
  ./gradlew -q :app:createDebugUnitTestCoverageReport
  python3 - <<'EOF'
  import xml.etree.ElementTree as ET
  root = ET.parse("app/build/reports/coverage/test/debug/report.xml").getroot()
  for package in root.iter("package"):
      for source in package.iter("sourcefile"):
          for counter in source.findall("counter"):
              if counter.get("type") == "LINE":
                  covered, missed = int(counter.get("covered")), int(counter.get("missed"))
                  print(f"{package.get('name')}/{source.get('name')}: {100 * covered // (covered + missed)}%")
  EOF
  ```

## Decisions This Plan Makes

The design leaves these open; the plan settles them as follows.

- **End's drain: option (b), a per-session signal wait in `:session-core` (Task 0), with 65 s for the gateway.** Hermes's own stop path (current upstream sources) budgets for this: `cron_drain_timeout` (30 s by default) is the floor for in-flight cron jobs and API (`/v1`) runs, plus a 10 s cleanup reserve, under a 60 s shutdown watchdog (`gateway/restart.py`, `gateway/shutdown_watchdog.py`); Hermes sizes its own systemd `TimeoutStopSec` at 70 s from these. Accepting pr's fixed 10 s (option a) would SIGKILL a draining gateway at about 12 s, which Hermes documents as worse than an interrupt: an interrupted cron run is recorded as failed, a SIGKILLed one stays `last_status=running` for good, and a `/v1` caller loses its answer. The change is small and generic (any server-type command session wants it), and the alternative of copying `CommandSessionFactory` into hr would fork pr's End logic. 65 s lets Hermes's own watchdog act first; End's worst case becomes about 72.5 s (`ManagedSession.endWorstCaseMs(signalWaitMs = 65_000)`).
- **Terminal binds also go into Task 0.** The design binds the profile "in every session"; pr's `ProotSessionFactory` had no binds, so `hermes` or `hermes setup` in hr's terminal would otherwise write to the image's own empty profile, not the one the gateway serves. `ProotSessionFactory(host, binds)` adds them, with the same missing-source check as command sessions.
- **The build tag comes from the registry, before install.** pr-cli keeps no OCI labels (its `manifest.json` holds the install name, the references, the architecture and the install time), and `hermes --version` prints the Hermes version but not the patch hash. `GhcrRegistry` reads the labels of the image behind `:gateway`, hr installs the immutable build tag, so what is recorded is exactly what is installed. The same reader serves M4's update check. The running version on Home comes from `/health`.
- **The key:** 32 random bytes as 64 hex characters in `files/hr/api_server_key` (mode 600), generated once. The root `.env` is merged, not overwritten, so settings a user adds there through the terminal survive; hr also writes `API_SERVER_HOST=127.0.0.1` and `API_SERVER_PORT=8642` so the listener stays local whatever the defaults become.
- **States:** the design's *Starting*, *Running*, *Stopped*, *Stopped unexpectedly* and *Failed* (for a start that could not begin: port in use, image missing, `open` failed), plus *Stopping* for End's drain. *Stopped* carries the exit code: 0, 1 and 143 read as a clean stop; 137 says the gateway was killed after the drain. *Starting* has no timeout: it lasts until `/health` answers or the session exits. Health is polled every second while *Starting*, not while *Running*.
- **Port check:** a TCP connect to `127.0.0.1:8642` before anything starts.
- **The log** lives in memory (2,000 lines of up to 2,000 characters), owned by `HrApp`; it is lost when Android kills the process, as the sessions are. Each start adds a `Starting the gateway` line. The listener key is redacted from every line.
- **Removing the image** is in Settings already in M1, because the phone check reinstalls the container to prove the profile survives; it is refused while any session runs. Install has no Cancel in M1.
- **Permissions:** `POST_NOTIFICATIONS` is requested once, on the first Start or Terminal tap (API 33+), and again from Settings; the battery-optimisation exemption is offered in Settings and as a banner when the CPU is kept awake and hr is not exempt.
- **Layout:** a root project with an `:app` module (pr's layout, unlike rs's root-as-app), packages `id.or.oo.hr`, `.profile`, `.image`, `.gateway`, `.ui`; one `MainActivity` with Home, Gateway log and Settings, and pr's `TerminalActivity` pattern for the terminal.
- **CI** fetches only `vendor/pr` and its `vendor/termlib` (depth 1) instead of `submodules: recursive`, which would clone pr's SSH-only and very large submodules. hr needs no Rust or proot build: pr's four executables are prebuilt in `:proot-engine/src/main/jniLibs`. Gradle still compiles two small native libraries from source (`ptyjni` in `:proot-engine`, libvterm in `:termlib`), so the runner's Android SDK, NDK and CMake 3.22.1 are used, as in pr's CI.

## File Structure

pr repository (Task 0):

| File | Responsibility |
|------|----------------|
| Modify `android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt` | `signalWaitMs` per command session, passed to `ManagedSession`; `loginArgs` builds on `terminalArgs` |
| Modify `android/session-core/src/main/java/id/or/oo/pr/session/ProotSessionFactory.kt` | `binds` for terminals, checked like command sessions; `terminalArgs` |
| Modify `android/session-core/src/test/java/id/or/oo/pr/session/CommandSessionTest.kt` | The signal wait reaches End; a non-positive wait is rejected |
| Create `android/session-core/src/test/java/id/or/oo/pr/session/TerminalSessionTest.kt` | Terminal arguments with binds; missing bind source, missing pr-cli, failed start |
| Modify `docs/android-apk-architecture.md`, `AGENTS.md` | Consumer contract and test count |

hr repository:

| File | Responsibility |
|------|----------------|
| Create `.gitignore` | Build output, `local.properties`, IDE files |
| Create `.gitmodules`, `vendor/pr` | pr as a submodule, untracked content ignored |
| Create `settings.gradle.kts` | `prCandidates`; includes `:app`, `:termlib`, `:proot-engine`, `:session-core` |
| Create `build.gradle.kts` | AGP, Kotlin and compose plugin versions |
| Create `gradle.properties` | AndroidX, JVM args, Kotlin test fixtures |
| Create `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.11.1 wrapper, copied from pr |
| Create `app/build.gradle.kts` | The app module: SDKs, ABI, packaging, coverage, dependencies |
| Create `app/src/main/AndroidManifest.xml` | Permissions, `HrApp`, activities, `SessionService`, cleartext config |
| Create `app/src/main/res/values/strings.xml`, `values/themes.xml`, `drawable/ic_notification.xml`, `drawable/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml`, `xml/network_security_config.xml` | Name, window theme, icons, cleartext to 127.0.0.1 only |
| Create `AGENTS.md` | hr's conventions (pr's, plus hr's specifics) |
| Create `README.md` | What hr is; how to build |
| Create `.github/workflows/ci.yml` | Unit tests and `assembleDebug` on pushes and pull requests |
| Create `app/src/main/java/id/or/oo/hr/Hermes.kt` | Constants: alias, keys, image, paths, command, port, signal wait |
| Create `app/src/main/java/id/or/oo/hr/HrFiles.kt` | Paths under `filesDir` |
| Create `app/src/main/java/id/or/oo/hr/PrivateFile.kt` | `writePrivate`: owner-only, atomic file write |
| Create `app/src/main/java/id/or/oo/hr/profile/EnvFile.kt` | `mergeEnv`: replace managed keys, keep other lines |
| Create `app/src/main/java/id/or/oo/hr/profile/ProfileStore.kt` | Profile directory, its bind, the listener key, the root `.env` |
| Create `app/src/main/java/id/or/oo/hr/HrApp.kt` | `ProotHost`, `SessionHost`, `PrBootstrap`, the `SessionManager`; later the log, `ImageStore`, `GatewayController` |
| Create `app/src/main/java/id/or/oo/hr/TerminalActivity.kt` | The terminal into `hermes` under key `hermes` |
| Create `app/src/main/java/id/or/oo/hr/gateway/LogBuffer.kt` | The gateway's sink: streaming UTF-8, ANSI and CR stripped, bounded, redacted |
| Create `app/src/main/java/id/or/oo/hr/image/Http.kt` | `Http`, `HttpResponse`, `UrlHttp` (no redirect following) |
| Create `app/src/main/java/id/or/oo/hr/image/ImageRegistry.kt` | `ImageBuild`, `ImageRegistry`, `GhcrRegistry` |
| Create `app/src/main/java/id/or/oo/hr/image/ImageStore.kt` | Install and remove through pr-cli; the installed build; one operation at a time |
| Create `app/src/main/java/id/or/oo/hr/gateway/GatewayState.kt` | The states and `active` |
| Create `app/src/main/java/id/or/oo/hr/gateway/GatewayProbe.kt` | `GatewayProbe`, `Health`, `LocalGatewayProbe`, `parseHealth` |
| Create `app/src/main/java/id/or/oo/hr/gateway/GatewayController.kt` | `GatewayLaunch`; start, stop, health polling, exit handling |
| Create `app/src/main/java/id/or/oo/hr/gateway/GatewayText.kt` | `formatDuration`, `describe` |
| Modify `app/src/main/java/id/or/oo/hr/MainActivity.kt` | Notification permission; hosts the screens |
| Create `app/src/main/java/id/or/oo/hr/ui/HrTheme.kt`, `HrScreens.kt`, `HomeScreen.kt`, `LogsScreen.kt`, `SettingsScreen.kt`, `SessionBanners.kt` | Theme, navigation, Home, Gateway log, Settings, Keep CPU awake and battery banners |
| Create tests `HrFilesTest`, `profile/EnvFileTest`, `profile/ProfileStoreTest`, `gateway/LogBufferTest`, `TestHttpServer`, `image/UrlHttpTest`, `image/GhcrRegistryTest`, `image/ImageStoreTest`, `gateway/GatewayControllerTest`, `gateway/GatewayTextTest`, `gateway/LocalGatewayProbeTest` | `app/src/test/java/id/or/oo/hr/...` |

### Task 0: pr `:session-core`: a per-session signal wait and terminal binds (pr repository)

**Files (pr repository):**
- Modify: `android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt`, `.../session/ProotSessionFactory.kt`
- Test: `android/session-core/src/test/java/id/or/oo/pr/session/CommandSessionTest.kt` (modify), `.../session/TerminalSessionTest.kt` (create)
- Modify: `docs/android-apk-architecture.md`, `AGENTS.md`

**Interfaces:**
- Consumes: `ManagedSession(alias, pty, emulator, output, onEnded, endGraceMs, signalWaitMs, exitCodeWaitMs, killExitWaitMs)`, `ManagedSession.SIGNAL_WAIT_MS`, `ProotLauncher(host).startCustomSession(args: List<String>, rows: Int = 24, cols: Int = 80): ProotLauncher.Session?`, `ProotPty(session)`, `Bind`, test fixtures `FakePty`, `eventually`.
- Produces:
  - `CommandSessionFactory(host: ProotHost, container: String, command: String, sink: (ByteArray, Int, Int) -> Unit, binds: List<Bind> = emptyList(), signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS)`; `IllegalArgumentException("signalWaitMs must be positive: <n>")` for `signalWaitMs <= 0`
  - `ProotSessionFactory(host: ProotHost, binds: List<Bind> = emptyList())`; `open` fails with `could not start a terminal for <key>: no bind source <path>` or `: no pr-cli at <path>`
  - `internal fun terminalArgs(prefixDir: File, container: String, binds: List<Bind>): List<String>`; `loginArgs` = `terminalArgs` + `["--", command]`

pr's behaviour does not change: its `App` keeps `ProotSessionFactory(this)` (no binds, the same pr-cli arguments as `ProotLauncher.startSession` builds today, now through `startCustomSession` with the same environment and 24x80 window) and builds no command sessions. A missing pr-cli now reads `could not start a terminal for alpine: no pr-cli at …` instead of `could not start a terminal for alpine`.

- [ ] **Step 1: Branch from pr's `main`**

```bash
cd ../pr.oo.or.id
git checkout main
git pull --ff-only
git log --oneline -1
git checkout -b feat/session-core-drain-and-binds
```

Expected: `5414634 docs: bring the test counts and two notes up to date` (or a later `main`; the steps below still apply if `CommandSessionFactory.kt` and `ProotSessionFactory.kt` match the "before" states quoted here).

- [ ] **Step 2: Write the failing signal-wait tests**

In `android/session-core/src/test/java/id/or/oo/pr/session/CommandSessionTest.kt`, replace the `factory` helper:

```kotlin
    private fun factory(
        sink: (ByteArray, Int, Int) -> Unit = toReceived,
        binds: List<Bind> = this.binds,
        start: (List<String>) -> Pty? = { args -> started += args; FakePty().also { ptys += it } },
    ) = CommandSessionFactory(
        host,
        container,
        command,
        sink,
        binds,
        startSession = { args -> start(args)?.let { pty -> ProotLauncher.Session(-1).also { launched[it] = pty } } },
        pty = { session, _ -> launched.getValue(session) },
    )
```

with:

```kotlin
    private fun factory(
        sink: (ByteArray, Int, Int) -> Unit = toReceived,
        binds: List<Bind> = this.binds,
        start: (List<String>) -> Pty? = { args -> started += args; FakePty().also { ptys += it } },
        signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS,
    ) = CommandSessionFactory(
        host,
        container,
        command,
        sink,
        binds,
        startSession = { args -> start(args)?.let { pty -> ProotLauncher.Session(-1).also { launched[it] = pty } } },
        pty = { session, _ -> launched.getValue(session) },
        signalWaitMs = signalWaitMs,
    )
```

and add these two tests directly above `theFactorysPtySendsSigtermToEveryTraceeIncludingProotsDirectChild`:

```kotlin
    @Test
    fun endWaitsTheFactorysSignalWaitForTheServerBeforeTheEof() {
        val pty = FakePty().apply { signalled = setOf(gateway) }
        val session = (sessions.open(key, factory(signalWaitMs = 1_500, start = { args -> started += args; pty.also { ptys += it } }))
            as SessionManager.OpenResult.Opened).session

        session.end()

        eventually(timeoutMs = 10_000) { session.exit.value != null }
        assertEquals(listOf("signal", "eof", "close"), pty.calls)
        assertTrue(pty.msBetween("signal", "eof") >= 1_500)
        assertTrue(pty.msBetween("signal", "eof") < ManagedSession.SIGNAL_WAIT_MS)
    }

    @Test
    fun aSignalWaitThatIsNotPositiveIsRejectedWhenTheFactoryIsBuilt() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            CommandSessionFactory(host, container, command, toReceived, binds, signalWaitMs = 0)
        }

        assertEquals("signalWaitMs must be positive: 0", e.message)
    }

```

- [ ] **Step 3: Run the tests and see them fail**

```bash
cd android && ./gradlew :session-core:testDebugUnitTest
```

Expected: compilation fails with `No parameter with name 'signalWaitMs' found.` and `None of the following candidates is applicable` in `CommandSessionTest.kt`.

- [ ] **Step 4: Add the signal wait**

`android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt`:

```kotlin
package id.or.oo.pr.session

import android.util.Log
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.ProotLauncher
import java.io.File

class CommandSessionFactory internal constructor(
    private val host: ProotHost,
    private val container: String,
    private val command: String,
    private val sink: (ByteArray, Int, Int) -> Unit,
    private val binds: List<Bind>,
    private val startSession: (List<String>) -> ProotLauncher.Session?,
    private val processes: ProotProcesses = ProotProcesses(),
    private val pty: (ProotLauncher.Session, ProotProcesses) -> Pty = ::commandPty,
    private val signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS,
) : SessionManager.SessionFactory {

    constructor(
        host: ProotHost,
        container: String,
        command: String,
        sink: (ByteArray, Int, Int) -> Unit,
        binds: List<Bind> = emptyList(),
        signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS,
    ) : this(host, container, command, sink, binds, ProotLauncher(host)::startCustomSession, signalWaitMs = signalWaitMs)

    init {
        require(command.isNotBlank()) { "command must not be blank" }
        require(signalWaitMs > 0) { "signalWaitMs must be positive: $signalWaitMs" }
    }

    override fun create(alias: String, onEnded: (ManagedSession) -> Unit): ManagedSession =
        ManagedSession(
            alias = alias,
            pty = startPty(alias),
            emulator = null,
            output = isolated(alias),
            onEnded = onEnded,
            signalWaitMs = signalWaitMs,
        )

    internal fun startPty(alias: String): Pty {
        val prCli = File(host.prefixDir, "bin/pr-cli")
        check(prCli.exists()) { "could not start a command session for $alias: no pr-cli at $prCli" }
        binds.forEach { bind ->
            check(File(bind.hostPath).exists()) { "could not start a command session for $alias: no bind source ${bind.hostPath}" }
        }
        val session = startSession(loginArgs(host.prefixDir, container, command, binds))
            ?: throw IllegalStateException("could not start a command session for $alias")
        return pty(session, processes)
    }

    private fun isolated(alias: String): (ByteArray, Int, Int) -> Unit {
        var logged = false
        return { buf, off, len ->
            try {
                sink(buf, off, len)
            } catch (e: Exception) {
                if (!logged) Log.w(TAG, "the output sink of $alias failed; its output is dropped while it fails", e)
                logged = true
            }
        }
    }

    internal companion object {
        val END_POLICY = EndPolicy.SIGNAL_EVERY_TRACEE
        private const val TAG = "PR"
    }
}

internal fun commandPty(session: ProotLauncher.Session, processes: ProotProcesses = ProotProcesses()): Pty =
    ProotPty(session, processes, CommandSessionFactory.END_POLICY)

internal fun loginArgs(prefixDir: File, container: String, command: String, binds: List<Bind>): List<String> =
    listOf(File(prefixDir, "bin/pr-cli").absolutePath, "login", container, "--user", "root") +
        binds.flatMap { listOf("--custom-bind", "${it.hostPath}:${it.guestPath}") } +
        listOf("--", command)
```

- [ ] **Step 5: Run the tests and see them pass**

```bash
cd android && ./gradlew :session-core:testDebugUnitTest
for f in session-core/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`; `CommandSessionTest` 23 tests (21 before), 149 in the module, no failures.

- [ ] **Step 6: Commit**

```bash
git add android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt \
  android/session-core/src/test/java/id/or/oo/pr/session/CommandSessionTest.kt
git commit -F- <<'EOF'
feat: let a command session set its own signal wait

End waits 10 s after SIGTERM before EOF, grace and SIGKILL. A server
that drains for longer, such as a server finishing a scheduled job
or an API run, was killed mid-drain. CommandSessionFactory now takes
signalWaitMs, passed to the session's ManagedSession, so a host can
give one command session a longer drain without changing any other.
EOF
```

- [ ] **Step 7: Write the failing terminal-binds tests**

`android/session-core/src/test/java/id/or/oo/pr/session/TerminalSessionTest.kt`:

```kotlin
package id.or.oo.pr.session

import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.ProotLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class TerminalSessionTest {

    private val literalPrefix = File("/data/data/org.example.gateway/files/usr")
    private val root = Files.createTempDirectory("terminal-session").toFile()
    private val host = object : ProotHost {
        override val prefixDir = File(root, "usr")
        override val homeDir = File(root, "home")
        override val packageName = "org.example.gateway"
        override val cacheDir = File(root, "cache")
    }
    private val prCli = File(host.prefixDir, "bin/pr-cli").apply {
        parentFile!!.mkdirs()
        createNewFile()
    }
    private val profile = File(root, "profile").apply { mkdirs() }
    private val binds = listOf(Bind(profile.path, "/srv"))
    private val started = CopyOnWriteArrayList<List<String>>()

    private fun factory(binds: List<Bind> = this.binds, session: ProotLauncher.Session? = ProotLauncher.Session(-1)) =
        ProotSessionFactory(host, binds) { args -> started += args; session }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun theTerminalArgumentsCarryEachBindAndNoCommand() {
        assertEquals(
            listOf(
                "/data/data/org.example.gateway/files/usr/bin/pr-cli",
                "login",
                "debian",
                "--user",
                "root",
                "--custom-bind",
                "/data/data/org.example.host/files/home/srv:/srv",
            ),
            terminalArgs(
                literalPrefix,
                "debian",
                listOf(Bind("/data/data/org.example.host/files/home/srv", "/srv")),
            ),
        )
    }

    @Test
    fun withoutBindsTheTerminalLogsInAsRootTheWayItAlwaysHas() {
        assertEquals(
            listOf("/data/data/org.example.gateway/files/usr/bin/pr-cli", "login", "alpine", "--user", "root"),
            terminalArgs(literalPrefix, "alpine", emptyList()),
        )
    }

    @Test
    fun theCommandArgumentsAreTheTerminalArgumentsWithTheCommandAfterTheSeparator() {
        assertEquals(
            terminalArgs(literalPrefix, "debian", binds) + listOf("--", "top -b"),
            loginArgs(literalPrefix, "debian", "top -b", binds),
        )
    }

    @Test
    fun theTerminalLogsIntoTheKeyWithItsBinds() {
        factory().startLogin("debian")

        assertEquals(listOf(terminalArgs(host.prefixDir, "debian", binds)), started)
    }

    @Test
    fun aMissingBindSourceFailsTheTerminalAndStartsNothing() {
        val missing = File(root, "absent")

        val e = assertThrows(IllegalStateException::class.java) {
            factory(binds = binds + Bind(missing.path, "/mnt")).startLogin("debian")
        }

        assertEquals("could not start a terminal for debian: no bind source $missing", e.message)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aMissingPrCliFailsTheTerminalAndStartsNothing() {
        prCli.delete()

        val e = assertThrows(IllegalStateException::class.java) { factory().startLogin("debian") }

        assertEquals("could not start a terminal for debian: no pr-cli at $prCli", e.message)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aTerminalThatCannotStartIsReported() {
        val e = assertThrows(IllegalStateException::class.java) { factory(session = null).startLogin("debian") }

        assertEquals("could not start a terminal for debian", e.message)
    }
}
```

- [ ] **Step 8: Run the tests and see them fail**

```bash
cd android && ./gradlew :session-core:testDebugUnitTest
```

Expected: compilation fails with `Too many arguments for 'constructor(host: ProotHost): ProotSessionFactory'` and `Unresolved reference 'terminalArgs'`.

- [ ] **Step 9: Add binds to terminals**

`android/session-core/src/main/java/id/or/oo/pr/session/ProotSessionFactory.kt`:

```kotlin
package id.or.oo.pr.session

import android.os.Looper
import androidx.compose.ui.graphics.Color
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.ProotLauncher
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.File

class ProotSessionFactory internal constructor(
    private val host: ProotHost,
    private val binds: List<Bind>,
    private val startSession: (List<String>) -> ProotLauncher.Session?,
) : SessionManager.SessionFactory {

    constructor(host: ProotHost, binds: List<Bind> = emptyList()) :
        this(host, binds, { args -> ProotLauncher(host).startCustomSession(args, rows = 24, cols = 80) })

    override fun create(alias: String, onEnded: (ManagedSession) -> Unit): ManagedSession {
        val session = startLogin(alias)
        val pty = ProotPty(session)
        try {
            lateinit var managed: ManagedSession
            val emulator = TerminalEmulatorFactory.create(
                looper = Looper.getMainLooper(),
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color(0xFF1a1a2e),
                onKeyboardInput = { data -> managed.write(data) },
                onResize = { dims -> managed.resize(dims.rows, dims.columns) },
            )
            managed = ManagedSession(
                alias = alias,
                pty = pty,
                emulator = emulator,
                output = { buf, off, len -> emulator.writeInput(buf, off, len) },
                onEnded = onEnded,
            )
            return managed
        } catch (t: Throwable) {
            pty.forceKill()
            session.close()
            throw t
        }
    }

    internal fun startLogin(alias: String): ProotLauncher.Session {
        val prCli = File(host.prefixDir, "bin/pr-cli")
        check(prCli.exists()) { "could not start a terminal for $alias: no pr-cli at $prCli" }
        binds.forEach { bind ->
            check(File(bind.hostPath).exists()) { "could not start a terminal for $alias: no bind source ${bind.hostPath}" }
        }
        return startSession(terminalArgs(host.prefixDir, alias, binds))
            ?: throw IllegalStateException("could not start a terminal for $alias")
    }
}

internal fun terminalArgs(prefixDir: File, container: String, binds: List<Bind>): List<String> =
    listOf(File(prefixDir, "bin/pr-cli").absolutePath, "login", container, "--user", "root") +
        binds.flatMap { listOf("--custom-bind", "${it.hostPath}:${it.guestPath}") }
```

`android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt` (only `loginArgs` changes; it now builds on `terminalArgs`):

```kotlin
package id.or.oo.pr.session

import android.util.Log
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.engine.ProotLauncher
import java.io.File

class CommandSessionFactory internal constructor(
    private val host: ProotHost,
    private val container: String,
    private val command: String,
    private val sink: (ByteArray, Int, Int) -> Unit,
    private val binds: List<Bind>,
    private val startSession: (List<String>) -> ProotLauncher.Session?,
    private val processes: ProotProcesses = ProotProcesses(),
    private val pty: (ProotLauncher.Session, ProotProcesses) -> Pty = ::commandPty,
    private val signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS,
) : SessionManager.SessionFactory {

    constructor(
        host: ProotHost,
        container: String,
        command: String,
        sink: (ByteArray, Int, Int) -> Unit,
        binds: List<Bind> = emptyList(),
        signalWaitMs: Long = ManagedSession.SIGNAL_WAIT_MS,
    ) : this(host, container, command, sink, binds, ProotLauncher(host)::startCustomSession, signalWaitMs = signalWaitMs)

    init {
        require(command.isNotBlank()) { "command must not be blank" }
        require(signalWaitMs > 0) { "signalWaitMs must be positive: $signalWaitMs" }
    }

    override fun create(alias: String, onEnded: (ManagedSession) -> Unit): ManagedSession =
        ManagedSession(
            alias = alias,
            pty = startPty(alias),
            emulator = null,
            output = isolated(alias),
            onEnded = onEnded,
            signalWaitMs = signalWaitMs,
        )

    internal fun startPty(alias: String): Pty {
        val prCli = File(host.prefixDir, "bin/pr-cli")
        check(prCli.exists()) { "could not start a command session for $alias: no pr-cli at $prCli" }
        binds.forEach { bind ->
            check(File(bind.hostPath).exists()) { "could not start a command session for $alias: no bind source ${bind.hostPath}" }
        }
        val session = startSession(loginArgs(host.prefixDir, container, command, binds))
            ?: throw IllegalStateException("could not start a command session for $alias")
        return pty(session, processes)
    }

    private fun isolated(alias: String): (ByteArray, Int, Int) -> Unit {
        var logged = false
        return { buf, off, len ->
            try {
                sink(buf, off, len)
            } catch (e: Exception) {
                if (!logged) Log.w(TAG, "the output sink of $alias failed; its output is dropped while it fails", e)
                logged = true
            }
        }
    }

    internal companion object {
        val END_POLICY = EndPolicy.SIGNAL_EVERY_TRACEE
        private const val TAG = "PR"
    }
}

internal fun commandPty(session: ProotLauncher.Session, processes: ProotProcesses = ProotProcesses()): Pty =
    ProotPty(session, processes, CommandSessionFactory.END_POLICY)

internal fun loginArgs(prefixDir: File, container: String, command: String, binds: List<Bind>): List<String> =
    terminalArgs(prefixDir, container, binds) + listOf("--", command)
```

- [ ] **Step 10: Run every pr test and the build**

```bash
cd android && ./gradlew :session-core:testDebugUnitTest :app:testDebugUnitTest assembleDebug
for f in session-core/build/test-results/testDebugUnitTest/*.xml app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`; `TerminalSessionTest` 7 tests, `:session-core` 156 in all, `:app` 29 (unchanged), no failures.

- [ ] **Step 11: Check coverage of the changed code**

```bash
cd android && ./gradlew -q :session-core:createDebugUnitTestCoverageReport
python3 - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse("session-core/build/reports/coverage/test/debug/report.xml").getroot()
for source in root.iter("sourcefile"):
    if source.get("name") in ("CommandSessionFactory.kt", "ProotSessionFactory.kt"):
        missed = [line.get("nr") for line in source.findall("line") if int(line.get("ci")) == 0 and int(line.get("mi")) > 0]
        print(source.get("name"), "uncovered lines:", " ".join(missed))
EOF
```

Expected: `CommandSessionFactory.kt` misses only line 27 (the public constructor, which builds a real `ProotLauncher`) and line 73 (`commandPty`), 95% covered. `ProotSessionFactory.kt` misses lines 16–17 (the public constructor's `ProotLauncher` lambda) and 20–44 (`create`: the termlib emulator on the main looper, device-only glue with no JVM coverage before this change either); `startLogin` and `terminalArgs` (lines 48–61) are fully covered.

- [ ] **Step 12: Commit**

```bash
git add android/session-core/src/main/java/id/or/oo/pr/session/ProotSessionFactory.kt \
  android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt \
  android/session-core/src/test/java/id/or/oo/pr/session/TerminalSessionTest.kt
git commit -F- <<'EOF'
feat: bind host directories into terminal sessions

A host that bind-mounts a directory into its command sessions needs
the same view in its terminal, or a tool run there writes to the
container's own copy. ProotSessionFactory takes binds, logs in with
the same --custom-bind pairs as a command session, and fails open
when a bind source is missing, since proot would only warn. With no
binds the pr-cli arguments are the ones startSession built before.
EOF
```

- [ ] **Step 13: Document the consumer contract**

In `docs/android-apk-architecture.md`, replace:

```markdown
End timing: after SIGTERM, End waits at most 10 s for the session to exit; it then sends EOF,
waits a 2 s grace and sends SIGKILL. A server that needs longer to drain is killed.
```

with:

```markdown
End timing: after SIGTERM, End waits at most 10 s for the session to exit; it then sends EOF,
waits a 2 s grace and sends SIGKILL. A server that needs longer to drain sets its own wait:
`CommandSessionFactory(host, container, command, sink, binds, signalWaitMs = 65_000)` replaces
the 10 s for that session only, and `ManagedSession.endWorstCaseMs(signalWaitMs = 65_000)` gives
the longest End can then take, for a host that waits on it.
```

and replace:

```markdown
factory the manager was built with (pr's `ProotSessionFactory`: a terminal with a termlib
emulator, where the key is the container alias). `open(key, CommandSessionFactory(host, container,
```

with:

```markdown
factory the manager was built with (pr's `ProotSessionFactory`: a terminal with a termlib
emulator, where the key is the container alias). `ProotSessionFactory(host, binds)` adds the same
`--custom-bind host:guest` pairs to every terminal it opens, and `open` fails when a bind's host
path is missing. `open(key, CommandSessionFactory(host, container,
```

In `AGENTS.md`, replace:

```
# session-core, 147 tests: sessions, command sessions, End's /proc scan, PrCliCommand, ProcessRunner, bootstrap links
```

with:

```
# session-core, 156 tests: sessions, command sessions, terminal binds, End's /proc scan, PrCliCommand, ProcessRunner, bootstrap links
```

- [ ] **Step 14: Commit, push and open the pull request**

```bash
git add docs/android-apk-architecture.md AGENTS.md
git commit -F- <<'EOF'
docs: describe the signal wait and terminal binds

The consumer contract now says how a command session sets its own
signal wait and how End's worst case follows it, and that terminals
take binds. The session-core test count goes from 147 to 156.
EOF
git push -u origin feat/session-core-drain-and-binds
gh pr create --base main --title "feat: per-session signal wait and terminal binds in session-core" --body-file - <<'EOF'
Adds two generic options to :session-core for apps that run a server
in a command session beside a terminal (the first is hr, a Hermes
gateway app):

- CommandSessionFactory(..., signalWaitMs): End waits that long after
  SIGTERM before EOF, grace and SIGKILL, for that session only.
- ProotSessionFactory(host, binds): terminals get the same
  --custom-bind pairs as command sessions; a missing source fails open.

pr itself is unchanged: no binds, default wait. Tests: session-core
147 -> 156, app 29.
EOF
```

Expected: the pull request opens; CI passes. hr's Task 3 needs it merged into pr's `main`.

### Task 1: Project scaffold

**Files:**
- Create: `.gitignore`, `.gitmodules`, `vendor/pr` (submodule), `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/id/or/oo/hr/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/drawable/ic_notification.xml`, `app/src/main/res/drawable/ic_launcher_background.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `app/src/main/res/xml/network_security_config.xml`
- Create: `AGENTS.md`, `README.md`, `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: pr at `5414634`: modules `:termlib`, `:proot-engine`, `:session-core` (by these names, which `:session-core` uses for its own dependencies); class `id.or.oo.pr.session.SessionService` (declared in the manifest).
- Produces: a building app `id.or.oo.hr` with an empty `MainActivity`; the build commands every later task uses.

There is no unit under test yet: this task's check is that the scaffold resolves pr's modules and builds an APK with pr's native executables in it.

- [ ] **Step 1: Check the branch**

```bash
git status --short --branch
git log --oneline -2
```

Expected: `## feat/m1-gateway`, clean; the plan's commit on top of `4c6f808 docs: design the hr app`.

- [ ] **Step 2: Add pr as a submodule at `5414634`, with its termlib**

```bash
git submodule add https://github.com/oonid/pr.git vendor/pr
git -C vendor/pr checkout 5414634
git config -f .gitmodules submodule.vendor/pr.ignore untracked
git -C vendor/pr submodule update --init vendor/termlib
cat .gitmodules
git -C vendor/pr log --oneline -1
git -C vendor/pr submodule status vendor/termlib
git -C vendor/pr ls-files android/proot-engine/src/main/jniLibs
```

Expected:

```
[submodule "vendor/pr"]
	path = vendor/pr
	url = https://github.com/oonid/pr.git
	ignore = untracked
5414634 docs: bring the test counts and two notes up to date
 cc5f2fd62de2be4e2e8f8eebd1fbd69414ab462d vendor/termlib (...)
android/proot-engine/src/main/jniLibs/arm64-v8a/libbusybox.so
android/proot-engine/src/main/jniLibs/arm64-v8a/libpr-cli.so
android/proot-engine/src/main/jniLibs/arm64-v8a/libproot-loader.so
android/proot-engine/src/main/jniLibs/arm64-v8a/libproot.so
```

The four executables are tracked in pr, so hr builds no Rust and no proot.

- [ ] **Step 3: Copy pr's Gradle wrapper**

```bash
mkdir -p gradle/wrapper
cp vendor/pr/android/gradlew vendor/pr/android/gradlew.bat .
cp vendor/pr/android/gradle/wrapper/gradle-wrapper.jar vendor/pr/android/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
sha256sum gradle/wrapper/gradle-wrapper.jar
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
test -x gradlew && echo executable
```

Expected: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046  gradle/wrapper/gradle-wrapper.jar`, `distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip`, `executable`.

- [ ] **Step 4: Write the Gradle build**

`.gitignore`:

```
.gradle/
.kotlin/
build/
.cxx/
local.properties
*.apk
*.aab
*.keystore
*.jks
.idea/
*.iml
.DS_Store
```

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val prCandidates = listOfNotNull(
    providers.gradleProperty("prRoot").orNull?.let { file(it) },
    file("vendor/pr"),
    file("../pr.oo.or.id"),
)
val prRoot = prCandidates.firstOrNull { java.io.File(it, "android/session-core/build.gradle.kts").exists() }
    ?: error(
        "Cannot locate the `pr` project in any of: " +
            prCandidates.joinToString { it.path } +
            ". Run `git submodule update --init vendor/pr` and " +
            "`git -C vendor/pr submodule update --init vendor/termlib`, or pass -PprRoot=<path to pr>."
    )

rootProject.name = "hr"
include(":app")

include(":termlib")
project(":termlib").projectDir = java.io.File(prRoot, "android/termlib")
include(":proot-engine")
project(":proot-engine").projectDir = java.io.File(prRoot, "android/proot-engine")
include(":session-core")
project(":session-core").projectDir = java.io.File(prRoot, "android/session-core")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
```

`gradle.properties`:

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.experimental.enableTestFixturesKotlinSupport=true
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "id.or.oo.hr"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.or.oo.hr"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":proot-engine"))
    implementation(project(":termlib"))
    implementation(project(":session-core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation(testFixtures(project(":session-core")))
}
```

`local.properties` stays untracked; create it if Android Studio has not:

```bash
test -f local.properties || printf 'sdk.dir=%s\n' "${ANDROID_HOME:-$HOME/Android/Sdk}" > local.properties
```

- [ ] **Step 5: Write the manifest, the resources and an empty `MainActivity`**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:allowBackup="false"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name="id.or.oo.pr.session.SessionService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps the Hermes Agent gateway the user started running in a Linux container, and the user's terminal into that container" />
        </service>

    </application>

</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">hr</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#1a1a2e</item>
    </style>
</resources>
```

`app/src/main/res/drawable/ic_notification.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M5,3H8V10.5H16V3H19V21H16V13.5H8V21H5Z" />
</vector>
```

`app/src/main/res/drawable/ic_launcher_background.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#1a1a2e"
        android:pathData="M0,0h108v108H0z" />
</vector>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#e9b44c"
        android:pathData="M38,32H46V50H62V32H70V76H62V58H46V76H38Z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

`app/src/main/java/id/or/oo/hr/MainActivity.kt`:

```kotlin
package id.or.oo.hr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("hr")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Build**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/arm64-v8a/'
git status --short
```

Expected: `BUILD SUCCESSFUL` (`:app:testDebugUnitTest` has no tests yet: `NO-SOURCE`); the APK lists `libbusybox.so`, `libjni_cb_term.so`, `libpr-cli.so`, `libproot-loader.so`, `libproot.so`, `libptyjni.so` (and `libandroidx.graphics.path.so`); `git status` does not list `vendor/pr` as modified although the build wrote `vendor/pr/android/proot-engine/.cxx/`.

- [ ] **Step 7: Write `AGENTS.md` and `README.md`**

`AGENTS.md`:

````markdown
# AGENTS.md

Guide for AI coding agents working on this project.

## Project Overview

**hr**: an Android app that runs the Hermes Agent gateway on a phone, in an Alpine container
under PRoot, on the engine of oonid/pr.
- Android package: `id.or.oo.hr`; minSdk 28, targetSdk 35, compileSdk 36, arm64-v8a only
- Kotlin and Compose; JDK 17; AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1
- pr's `:proot-engine`, `:termlib` and `:session-core` come from the `vendor/pr` submodule
- Design: `docs/design/hr-app.md`; plans: `docs/plans/`

## Privacy Policy

**NEVER include personal device information in code, comments, commits, or docs.**

- No device model numbers (mask partial: `SM-XXXXX`), marketing names (`Galaxy X`) or serials
- No adb serials: use bare `adb`, never `adb -s <serial>`
- OK: manufacturer, architecture (aarch64, arm64-v8a), OS version (Android 16, SDK 36)

## Getting the Source

```bash
git submodule update --init vendor/pr
git -C vendor/pr submodule update --init vendor/termlib
```

Do not use `--recursive`: pr's other submodules (proot, busybox, musl, bionic, samba) are only
needed to rebuild its native binaries, which `:proot-engine` ships prebuilt, and several of their
URLs are SSH-only. `settings.gradle.kts` finds pr at `-PprRoot=<path>`, then `vendor/pr`, then
`../pr.oo.or.id`.

## Build and Test

```bash
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
./gradlew :app:createDebugUnitTestCoverageReport
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The coverage report is `app/build/reports/coverage/test/debug/report.xml`.

## Code Conventions

- No comments unless explicitly requested
- Follow the existing patterns in each file
- `vendor/` is read only: a change to pr's modules is a pull request to oonid/pr
- Logic that can run on the JVM lives outside Activities and the `Application`, behind small
  interfaces, and has JVM tests; Android glue stays thin and is checked on a phone
- A session's output sink is owned by `HrApp`, never by an Activity or a ViewModel
- Keys never reach logs, logcat or the UI

## Reserved Identities

pr's `:session-core` uses these in every app; hr must not use them for anything else: the
wake-lock tag `pr:sessions`, the preferences file `pr_prefs`, notification id 1, the channel ids
`sessions_running` and `sessions_ended`. hr's own preferences file is `hr_prefs`.

## Commit Style

Format: `feat|fix|chore|docs|test|build|ci: <description>`

- Do not add trailers (`Co-authored-by` and the like)
- Subject plus a body wrapped at 72 columns or fewer
- Describe technical changes directly
- Stage explicit paths, never `git add -A` or `git add .`

## Coverage Gate

- Preserve or improve test coverage on touched code
- Every unit that runs on the JVM keeps 80% of its lines or more covered
- Prefer targeted tests first, then broader coverage runs

## Superpowers Workflow

Save design specs in `docs/design/<topic>.md` and implementation plans in
`docs/plans/<topic>.md`, not in `docs/superpowers/`.
````

`README.md`:

````markdown
# hr

Hermes Agent on Android: an app that installs a prebuilt Hermes image, keeps the Hermes gateway
running in the background and opens a terminal into its container. It runs Hermes in an Alpine
container under PRoot (no root, no VM), on the engine of [oonid/pr](https://github.com/oonid/pr).

Status: in development. Design: [docs/design/hr-app.md](docs/design/hr-app.md).

## Build

Needs JDK 17, the Android SDK (API 36) with the NDK and CMake 3.22.1, and an arm64-v8a phone.

```bash
git clone https://github.com/oonid/hr.git
cd hr
git submodule update --init vendor/pr
git -C vendor/pr submodule update --init vendor/termlib
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
````

- [ ] **Step 8: Write CI**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

jobs:
  build:
    runs-on: ubuntu-24.04

    steps:
    - uses: actions/checkout@v7

    - name: Fetch pr and its termlib
      run: |
        git submodule update --init --depth 1 vendor/pr
        git -C vendor/pr submodule update --init --depth 1 vendor/termlib

    - name: Set up Java
      uses: actions/setup-java@v6
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: 'gradle'

    - name: Run unit tests
      run: ./gradlew :app:testDebugUnitTest

    - name: Build the APK
      run: ./gradlew assembleDebug

    - name: Upload APK
      uses: actions/upload-artifact@v7
      with:
        name: hr-debug
        path: app/build/outputs/apk/debug/app-debug.apk
```

The runner's preinstalled Android SDK, NDK and CMake build `ptyjni` and libvterm, as in pr's CI; AGP 8.7.3 installs its default NDK (27.0.12077973) if the image lacks it.

- [ ] **Step 9: Commit**

```bash
git add .gitignore .gitmodules vendor/pr settings.gradle.kts build.gradle.kts gradle.properties \
  gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties \
  app/build.gradle.kts app/src/main AGENTS.md README.md .github/workflows/ci.yml
git commit -F- <<'EOF'
build: scaffold the hr app on pr's modules

An empty Compose app, id.or.oo.hr, that includes pr's proot-engine,
termlib and session-core from the vendor/pr submodule at 5414634, with
-PprRoot and ../pr.oo.or.id as alternatives. The manifest carries the
permissions, extractNativeLibs and pr's SessionService with hr's own
specialUse text; cleartext HTTP is allowed to 127.0.0.1 only. AGENTS.md
takes over pr's conventions; CI runs the unit tests and assembleDebug.
EOF
```

### Task 2: Paths, the profile and the listener key

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/Hermes.kt`, `.../hr/HrFiles.kt`, `.../hr/PrivateFile.kt`, `.../hr/profile/EnvFile.kt`, `.../hr/profile/ProfileStore.kt`
- Test: `app/src/test/java/id/or/oo/hr/HrFilesTest.kt`, `.../hr/profile/EnvFileTest.kt`, `.../hr/profile/ProfileStoreTest.kt`

**Interfaces:**
- Consumes: `id.or.oo.pr.session.Bind(hostPath: String, guestPath: String)`.
- Produces:
  - `object Hermes` with `CONTAINER`, `TERMINAL_KEY`, `GATEWAY_KEY`, `REPOSITORY`, `PULL_TAG`, `PROFILE_GUEST`, `ROOT_ENV`, `GATEWAY_COMMAND`, `HOST`, `PORT`, `GATEWAY_SIGNAL_WAIT_MS`
  - `class HrFiles(filesDir: File)` with `prefixDir`, `homeDir`, `hrDir`, `profileDir`, `keyFile`, `imageRecord`, `fun containerDir(alias: String): File`, `fun rootfs(alias: String): File`
  - `fun writePrivate(file: File, text: String)`
  - `fun mergeEnv(existing: String, values: Map<String, String>): String`
  - `class ProfileStore(files: HrFiles, random: SecureRandom = SecureRandom())` with `fun ensureProfile(): File`, `fun bind(): Bind`, `fun apiServerKey(): String`, `fun writeRootEnv(rootfs: File)`

The profile is a host directory, so hr writes files in it without a shell. The key file and the root `.env` are created owner-only before anything is written to them, then renamed into place.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/id/or/oo/hr/HrFilesTest.kt`:

```kotlin
package id.or.oo.hr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HrFilesTest {

    private val files = HrFiles(File("/data/data/id.or.oo.hr/files"))

    @Test
    fun theContainerLivesWherePrCliInstallsItsOciImages() {
        assertEquals(File("/data/data/id.or.oo.hr/files/usr"), files.prefixDir)
        assertEquals(
            File("/data/data/id.or.oo.hr/files/usr/var/lib/pr/containers/hermes/rootfs"),
            files.rootfs("hermes"),
        )
    }

    @Test
    fun hrKeepsItsOwnFilesOutsideTheContainer() {
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/profile"), files.profileDir)
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/api_server_key"), files.keyFile)
        assertEquals(File("/data/data/id.or.oo.hr/files/hr/image.properties"), files.imageRecord)
        assertEquals(File("/data/data/id.or.oo.hr/files/home"), files.homeDir)
    }
}
```

`app/src/test/java/id/or/oo/hr/profile/EnvFileTest.kt`:

```kotlin
package id.or.oo.hr.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvFileTest {

    private val values = linkedMapOf("API_SERVER_ENABLED" to "true", "API_SERVER_KEY" to "k".repeat(64))

    @Test
    fun anEmptyFileGetsTheValuesInOrder() {
        assertEquals("API_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n", mergeEnv("", values))
    }

    @Test
    fun theValuesReplaceTheirKeysAndEveryOtherLineStays() {
        val existing = "# set by hand\nTELEGRAM_BOT_TOKEN=abc\nAPI_SERVER_KEY=old\n\nexport API_SERVER_ENABLED=false\nOTHER=x"

        assertEquals(
            "# set by hand\nTELEGRAM_BOT_TOKEN=abc\n\nOTHER=x\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv(existing, values),
        )
    }

    @Test
    fun aKeyThatOnlyStartsLikeAManagedOneStays() {
        assertEquals(
            "API_SERVER_KEYS=list\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv("API_SERVER_KEYS=list\n", values),
        )
    }

    @Test
    fun carriageReturnsAreDropped() {
        assertEquals(
            "A=1\nAPI_SERVER_ENABLED=true\nAPI_SERVER_KEY=${"k".repeat(64)}\n",
            mergeEnv("A=1\r\nAPI_SERVER_KEY=old\r\n", values),
        )
    }
}
```

`app/src/test/java/id/or/oo/hr/profile/ProfileStoreTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run the tests and see them fail**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: compilation fails with `Unresolved reference 'HrFiles'` and `Unresolved reference 'mergeEnv'`.

- [ ] **Step 3: Implement**

`app/src/main/java/id/or/oo/hr/Hermes.kt`:

```kotlin
package id.or.oo.hr

object Hermes {
    const val CONTAINER = "hermes"
    const val TERMINAL_KEY = "hermes"
    const val GATEWAY_KEY = "hermes-gateway"
    const val REPOSITORY = "ghcr.io/oonid/hermes-agent"
    const val PULL_TAG = "gateway"
    const val PROFILE_GUEST = "/root/.hermes/profiles/android"
    const val ROOT_ENV = "root/.hermes/.env"
    const val GATEWAY_COMMAND = "cd /root && HERMES_HOME=/root/.hermes exec hermes gateway run"
    const val HOST = "127.0.0.1"
    const val PORT = 8642
    const val GATEWAY_SIGNAL_WAIT_MS = 65_000L
}
```

`app/src/main/java/id/or/oo/hr/HrFiles.kt`:

```kotlin
package id.or.oo.hr

import java.io.File

class HrFiles(filesDir: File) {
    val prefixDir = File(filesDir, "usr")
    val homeDir = File(filesDir, "home")
    val hrDir = File(filesDir, "hr")
    val profileDir = File(hrDir, "profile")
    val keyFile = File(hrDir, "api_server_key")
    val imageRecord = File(hrDir, "image.properties")

    fun containerDir(alias: String) = File(prefixDir, "var/lib/pr/containers/$alias")

    fun rootfs(alias: String) = File(containerDir(alias), "rootfs")
}
```

`app/src/main/java/id/or/oo/hr/PrivateFile.kt`:

```kotlin
package id.or.oo.hr

import java.io.File

fun writePrivate(file: File, text: String) {
    val dir = file.absoluteFile.parentFile!!
    check(dir.isDirectory || dir.mkdirs()) { "could not create $dir" }
    val tmp = File(dir, ".${file.name}.tmp")
    tmp.delete()
    check(tmp.createNewFile()) { "could not create $tmp" }
    tmp.setReadable(false, false)
    tmp.setWritable(false, false)
    tmp.setExecutable(false, false)
    tmp.setReadable(true, true)
    tmp.setWritable(true, true)
    tmp.writeText(text)
    check(tmp.renameTo(file)) { "could not write $file" }
}
```

`app/src/main/java/id/or/oo/hr/profile/EnvFile.kt`:

```kotlin
package id.or.oo.hr.profile

fun mergeEnv(existing: String, values: Map<String, String>): String {
    val kept = if (existing.isEmpty()) {
        emptyList()
    } else {
        existing.replace("\r", "").removeSuffix("\n").split('\n').filter { envKey(it) !in values }
    }
    return (kept + values.map { (key, value) -> "$key=$value" }).joinToString("\n", postfix = "\n")
}

private fun envKey(line: String): String? {
    val assignment = line.trim().removePrefix("export ").trimStart()
    val eq = assignment.indexOf('=')
    return if (eq > 0) assignment.substring(0, eq).trim() else null
}
```

`app/src/main/java/id/or/oo/hr/profile/ProfileStore.kt`:

```kotlin
package id.or.oo.hr.profile

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.Bind
import java.io.File
import java.security.SecureRandom

class ProfileStore(private val files: HrFiles, private val random: SecureRandom = SecureRandom()) {

    private val lock = Any()
    private var key: String? = null

    fun ensureProfile(): File {
        val dir = files.profileDir
        check(dir.isDirectory || dir.mkdirs()) { "could not create the profile directory $dir" }
        return dir
    }

    fun bind(): Bind = Bind(files.profileDir.absolutePath, Hermes.PROFILE_GUEST)

    fun apiServerKey(): String = synchronized(lock) {
        key ?: (readKey() ?: newKey()).also { key = it }
    }

    fun writeRootEnv(rootfs: File) {
        check(rootfs.isDirectory) { "the Hermes container has no root at $rootfs" }
        val env = File(rootfs, Hermes.ROOT_ENV)
        val existing = if (env.isFile) env.readText() else ""
        writePrivate(env, mergeEnv(existing, listenerSettings()))
    }

    private fun listenerSettings(): Map<String, String> = linkedMapOf(
        "API_SERVER_ENABLED" to "true",
        "API_SERVER_KEY" to apiServerKey(),
        "API_SERVER_HOST" to Hermes.HOST,
        "API_SERVER_PORT" to Hermes.PORT.toString(),
    )

    private fun readKey(): String? =
        files.keyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { KEY_FORMAT.matches(it) }

    private fun newKey(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        writePrivate(files.keyFile, generated)
        return generated
    }

    private companion object {
        val KEY_FORMAT = Regex("[0-9a-f]{64}")
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest
for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`; `HrFilesTest` 2, `EnvFileTest` 4, `ProfileStoreTest` 8; 14 tests, no failures.

- [ ] **Step 5: Check coverage**

Run the coverage command from Global Constraints.

Expected: `HrFiles.kt`, `PrivateFile.kt`, `profile/EnvFile.kt` and `profile/ProfileStore.kt` at 100%.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/Hermes.kt app/src/main/java/id/or/oo/hr/HrFiles.kt \
  app/src/main/java/id/or/oo/hr/PrivateFile.kt app/src/main/java/id/or/oo/hr/profile \
  app/src/test/java/id/or/oo/hr/HrFilesTest.kt app/src/test/java/id/or/oo/hr/profile
git commit -F- <<'EOF'
feat: keep the profile and the listener key in hr's files

The Hermes profile lives in files/hr/profile and is bound over
/root/.hermes/profiles/android. The API server key is generated once,
64 hex characters, owner-only in files/hr/api_server_key. The
listener settings are merged into the container's /root/.hermes/.env
(enabled, key, 127.0.0.1, 8642) and every other line there is kept.
EOF
```

### Task 3: `HrApp` and the terminal

**Files:**
- Modify: `vendor/pr` (to pr's `main` with Task 0 merged)
- Create: `app/src/main/java/id/or/oo/hr/HrApp.kt`, `app/src/main/java/id/or/oo/hr/TerminalActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ProotHost` (`prefixDir`, `homeDir`, `packageName`, `cacheDir`, `dnsServers`), `NetworkDns.usableServers`, `SessionHost` (`sessions`, `notificationIcon`, `openAppIntent()`), `PrBootstrap(context, host, version).run()`, `SessionManager(factory, ServiceForegroundController(context), NotificationEndedNotifier(context))`, `ProotSessionFactory(host, binds)` (Task 0), `SessionManager.open(key)`, `attach`, `setVisible`, `detach`, `ManagedSession.END_TIMEOUT_MS`, termlib's `Terminal` and `SelectionController`, `ProfileStore.bind()`, `ProfileStore.ensureProfile()`.
- Produces: `class HrApp : Application(), ProotHost, SessionHost` with `val files: HrFiles`, `val profiles: ProfileStore`, `override val sessions: SessionManager`; `class TerminalActivity : ComponentActivity()` opening key `Hermes.TERMINAL_KEY`.

`HrApp` and `TerminalActivity` are Android glue with no JVM tests (Global Constraints); their checks are the build here and Task 8 on a phone. `TerminalActivity` is pr's, with hr's key and wording, and without its own notification request (`MainActivity` asks, Task 7).

- [ ] **Step 1: Move `vendor/pr` to pr's `main` with Task 0**

```bash
git -C vendor/pr fetch origin main
git -C vendor/pr checkout origin/main
git -C vendor/pr submodule update --init vendor/termlib
git -C vendor/pr log --oneline -4
grep -c 'signalWaitMs' vendor/pr/android/session-core/src/main/java/id/or/oo/pr/session/CommandSessionFactory.kt
grep -c 'binds: List<Bind> = emptyList()' vendor/pr/android/session-core/src/main/java/id/or/oo/pr/session/ProotSessionFactory.kt
```

Expected: the log shows Task 0's three commits (or their merge); the two counts are `5` and `1`. If either is `0`, Task 0 is not merged yet: stop here.

- [ ] **Step 2: Check that hr still builds on the new pr**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`, 14 tests, no failures.

- [ ] **Step 3: Commit the submodule move**

```bash
git add vendor/pr
git commit -F- <<EOF
build: move vendor/pr to the signal wait and terminal binds

pr's main now has CommandSessionFactory(signalWaitMs) and
ProotSessionFactory(host, binds), which the gateway session and the
terminal need. vendor/pr is now at pr commit
$(git -C vendor/pr rev-parse --short HEAD).
EOF
```

- [ ] **Step 4: Write `HrApp`**

`app/src/main/java/id/or/oo/hr/HrApp.kt`:

```kotlin
package id.or.oo.hr

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.engine.NetworkDns
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.session.NotificationEndedNotifier
import id.or.oo.pr.session.PrBootstrap
import id.or.oo.pr.session.ProotSessionFactory
import id.or.oo.pr.session.ServiceForegroundController
import id.or.oo.pr.session.SessionHost
import id.or.oo.pr.session.SessionManager
import java.io.File
import java.net.Inet6Address

class HrApp : Application(), ProotHost, SessionHost {

    val files: HrFiles by lazy { HrFiles(filesDir) }

    val profiles: ProfileStore by lazy { ProfileStore(files) }

    override val prefixDir: File get() = files.prefixDir

    override val homeDir: File get() = files.homeDir

    @get:JvmName("getProotPackageName")
    override val packageName: String get() = super.getPackageName()

    @get:JvmName("getProotCacheDir")
    override val cacheDir: File get() = super.getCacheDir()

    override val dnsServers: List<String>
        get() {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            val link = connectivity.getLinkProperties(network) ?: return emptyList()
            val hasIpv6DefaultRoute = link.routes.any {
                it.destination.prefixLength == 0 && it.destination.address is Inet6Address
            }
            return NetworkDns.usableServers(link.dnsServers, hasIpv6DefaultRoute)
        }

    override val sessions: SessionManager by lazy {
        SessionManager(
            ProotSessionFactory(this, listOf(profiles.bind())),
            ServiceForegroundController(this),
            NotificationEndedNotifier(this),
        )
    }

    override val notificationIcon: Int get() = R.drawable.ic_notification

    override fun openAppIntent(): Intent = Intent(this, MainActivity::class.java)

    override fun onCreate() {
        super.onCreate()
        PrBootstrap(this, this, BOOTSTRAP_VERSION).run()
        profiles.ensureProfile()
    }

    private companion object {
        const val BOOTSTRAP_VERSION = 1
    }
}
```

- [ ] **Step 5: Write `TerminalActivity`**

`app/src/main/java/id/or/oo/hr/TerminalActivity.kt`:

```kotlin
package id.or.oo.hr

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal

class TerminalActivity : ComponentActivity() {

    private val sessions: SessionManager get() = (application as HrApp).sessions
    private var session: ManagedSession? = null
    private val screen = ManagedSession.Screen { finish() }
    private var initialImeRequested = false
    private var initialImeShown = false
    private var backPressStarted = false
    private var selectionController: SelectionController? = null
    private val showLeavePrompt = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = session
                if (current == null || current.exit.value != null) finish() else showLeavePrompt.value = true
            }
        })
        open()
    }

    private fun open() {
        (application as HrApp).profiles.ensureProfile()
        when (val result = sessions.open(Hermes.TERMINAL_KEY)) {
            is SessionManager.OpenResult.Failed -> setContent { Message("Couldn't open the terminal: ${result.reason}") }
            is SessionManager.OpenResult.Ending -> {
                sessions.attach(result.session, screen)
                sessions.detach(result.session, screen)
                setContent { Message("Ending the previous terminal…") }
                lifecycleScope.launch {
                    val stopped = withTimeoutOrNull(ManagedSession.END_TIMEOUT_MS) {
                        result.session.exit.first { it != null }
                    }
                    if (isFinishing) return@launch
                    if (stopped == null) {
                        setContent { Message("Couldn't stop the previous terminal.") }
                    } else {
                        lifecycle.withStarted { if (!isFinishing) open() }
                    }
                }
            }
            is SessionManager.OpenResult.Opened -> show(result.session)
        }
    }

    private fun show(current: ManagedSession) {
        session = current
        sessions.attach(current, screen)
        if (lifecycle.currentState != Lifecycle.State.INITIALIZED) {
            sessions.setVisible(current, screen, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        if (hasWindowFocus() && !initialImeRequested) {
            initialImeRequested = true
            ensureInitialKeyboard(attempt = 0)
        }

        setContent {
            val exit by current.exit.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TERMINAL_BACKGROUND) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Terminal(
                            terminalEmulator = current.emulator!!,
                            modifier = Modifier.fillMaxSize().systemBarsPadding(),
                            initialFontSize = 12.sp,
                            backgroundColor = TERMINAL_BACKGROUND,
                            foregroundColor = Color.White,
                            keyboardEnabled = true,
                            showSoftKeyboard = true,
                            onTerminalTap = { window.decorView.post { showKeyboard() } },
                            onSelectionControllerAvailable = { selectionController = it },
                        )
                        exit?.let { ended ->
                            Text(
                                text = "Terminal ended (exit ${ended.code ?: "unknown"})",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color(0xCC000000))
                                    .navigationBarsPadding()
                                    .padding(12.dp),
                            )
                        }
                    }
                    if (showLeavePrompt.value) {
                        LeavePrompt(
                            onEnd = {
                                showLeavePrompt.value = false
                                current.end()
                                finish()
                            },
                            onKeep = {
                                showLeavePrompt.value = false
                                sessions.setVisible(current, screen, false)
                                finish()
                            },
                            onCancel = { showLeavePrompt.value = false },
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backPressStarted = true
            KeyEvent.ACTION_UP -> {
                if (backPressStarted && !event.isCanceled) {
                    val selection = selectionController
                    if (selection != null && selection.isSelectionActive) selection.clearSelection()
                    else onBackPressedDispatcher.onBackPressed()
                }
                backPressStarted = false
            }
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialImeRequested && session != null) {
            initialImeRequested = true
            ensureInitialKeyboard(attempt = 0)
        }
    }

    private fun ensureInitialKeyboard(attempt: Int) {
        window.decorView.postDelayed({
            if (isFinishing || initialImeShown) return@postDelayed
            val imeVisible = ViewCompat.getRootWindowInsets(window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                initialImeShown = true
            } else if (attempt < 5) {
                showKeyboard()
                ensureInitialKeyboard(attempt + 1)
            }
        }, 300)
    }

    private fun showKeyboard() {
        val editor = findTextEditor(window.decorView) ?: return
        editor.requestFocus()
        WindowCompat.getInsetsController(window, editor).show(WindowInsetsCompat.Type.ime())
    }

    private fun findTextEditor(view: View): View? {
        if (view.onCheckIsTextEditor()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextEditor(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun onStart() {
        super.onStart()
        session?.let { sessions.setVisible(it, screen, true) }
    }

    override fun onStop() {
        if (!isChangingConfigurations) session?.let { sessions.setVisible(it, screen, false) }
        super.onStop()
    }

    override fun onDestroy() {
        session?.let { sessions.detach(it, screen, retainEnded = isChangingConfigurations) }
        super.onDestroy()
    }

    private companion object {
        val TERMINAL_BACKGROUND = Color(0xFF1a1a2e)
    }
}

@Composable
private fun LeavePrompt(onEnd: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Leave the terminal?") },
        text = { Text("Keep running leaves the terminal working in the background. End terminal stops it and everything started in it. The gateway is not affected.") },
        confirmButton = { TextButton(onClick = onKeep) { Text("Keep running") } },
        dismissButton = {
            Row {
                TextButton(onClick = onEnd) { Text("End terminal") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Message(text: String) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1a1a2e)) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text, color = Color.White)
            }
        }
    }
}
```

- [ ] **Step 6: Declare both in the manifest**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".HrApp"
        android:allowBackup="false"
        android:extractNativeLibs="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".TerminalActivity"
            android:configChanges="orientation|screenSize|keyboard|keyboardHidden"
            android:exported="false"
            android:windowSoftInputMode="adjustResize" />

        <service
            android:name="id.or.oo.pr.session.SessionService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps the Hermes Agent gateway the user started running in a Linux container, and the user's terminal into that container" />
        </service>

    </application>

</manifest>
```

- [ ] **Step 7: Build**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
```

Expected: `BUILD SUCCESSFUL`; 14 tests, no failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/HrApp.kt app/src/main/java/id/or/oo/hr/TerminalActivity.kt \
  app/src/main/AndroidManifest.xml
git commit -F- <<'EOF'
feat: run pr's sessions in hr and open a terminal into hermes

HrApp implements ProotHost and SessionHost, runs PrBootstrap and owns
the SessionManager. Its default factory is a terminal into container
hermes, key hermes, with the profile bound over
/root/.hermes/profiles/android. TerminalActivity is pr's terminal
screen opening that key; Back offers to keep it running or end it.
EOF
```

### Task 4: The gateway log

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/gateway/LogBuffer.kt`
- Test: `app/src/test/java/id/or/oo/hr/gateway/LogBufferTest.kt`

**Interfaces:**
- Consumes: the command session's sink contract: `(ByteArray, Int, Int) -> Unit`, called on the reader thread with a reused 8 KiB buffer; PTY bytes (CRLF, ANSI escapes, UTF-8 split across reads).
- Produces: `class LogBuffer(maxLines: Int = 2_000, maxLineChars: Int = 2_000, secrets: () -> List<String> = { emptyList() })` with `fun append(buf: ByteArray, off: Int, len: Int)` (usable as `gatewayLog::append`), `fun mark(text: String)`, `fun snapshot(): List<String>`, `fun tail(count: Int): List<String>`, `val revision: StateFlow<Long>`.

`append` copies what it keeps (it decodes at once), never blocks beyond its own short lock, and keeps undecoded trailing bytes and escape state for the next read. `HrApp` owns the one instance (Task 7), so it outlives every screen.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/id/or/oo/hr/gateway/LogBufferTest.kt`:

```kotlin
package id.or.oo.hr.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    private val log = LogBuffer()

    private fun LogBuffer.append(text: String) {
        val bytes = text.toByteArray()
        append(bytes, 0, bytes.size)
    }

    @Test
    fun ptyLineEndsBecomeLines() {
        log.append("one\r\ntwo\r\n")

        assertEquals(listOf("one", "two"), log.snapshot())
    }

    @Test
    fun utf8SplitAnywhereAcrossReadsDecodesWhole() {
        val text = "naïve ✓ 日本 🚀\n"
        val bytes = text.toByteArray()
        for (cut in 1 until bytes.size) {
            val buffer = LogBuffer()
            buffer.append(bytes, 0, cut)
            buffer.append(bytes, cut, bytes.size - cut)
            assertEquals("cut at $cut", listOf(text.trimEnd('\n')), buffer.snapshot())
        }
    }

    @Test
    fun malformedBytesBecomeReplacementCharacters() {
        log.append(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), '\n'.code.toByte()), 0, 4)

        assertEquals(listOf("a�b"), log.snapshot())
    }

    @Test
    fun ansiEscapesAreStrippedEvenWhenSplitAcrossReads() {
        log.append("\u001b[3")
        log.append("2mINFO\u001b[0m ready\r\n")
        log.append("\u001b]0;hermes\u0007title gone\n")
        log.append("\u001b]8;;https://example.org\u001b\\link\u001b]8;;\u001b\\ text\n")
        log.append("\u001b(Bcharset\n")
        log.append("\u001b7saved\u001b8\n")

        assertEquals(listOf("INFO ready", "title gone", "link text", "charset", "saved"), log.snapshot())
    }

    @Test
    fun otherControlCharactersAreDroppedAndTabsKept() {
        log.append("a\u0007b\bc\u007fd\te\n")

        assertEquals(listOf("abcd\te"), log.snapshot())
    }

    @Test
    fun theBufferKeepsOnlyTheNewestLines() {
        val small = LogBuffer(maxLines = 3)
        small.append("1\n2\n3\n4\n5\n")

        assertEquals(listOf("3", "4", "5"), small.snapshot())
    }

    @Test
    fun aLongLineIsCutAtTheLimit() {
        val small = LogBuffer(maxLineChars = 5)
        small.append("abcdefgh\nij\n")

        assertEquals(listOf("abcde", "ij"), small.snapshot())
    }

    @Test
    fun anUnfinishedLineIsShownUntilItEnds() {
        log.append("loading")
        assertEquals(listOf("loading"), log.snapshot())

        log.append(" done\n")
        assertEquals(listOf("loading done"), log.snapshot())
    }

    @Test
    fun tailReturnsTheLastLines() {
        log.append("1\n2\n3\nfour")

        assertEquals(listOf("3", "four"), log.tail(2))
    }

    @Test
    fun secretsAreRedacted() {
        val secret = "0123456789abcdef"
        val redacting = LogBuffer(secrets = { listOf(secret, "") })
        redacting.append("key=$secret ok\nunfinished $secret")

        assertEquals(listOf("key=[redacted] ok", "unfinished [redacted]"), redacting.snapshot())
    }

    @Test
    fun aMarkEndsTheUnfinishedLineAndForgetsAHalfEscape() {
        log.append("half\u001b[3")
        log.mark("Starting the gateway")
        log.append("1mnext\n")

        assertEquals(listOf("half", "Starting the gateway", "1mnext"), log.snapshot())
    }

    @Test
    fun theBufferCopiesWhatItKeeps() {
        val buf = "first\n".toByteArray()
        log.append(buf, 0, buf.size)
        buf.fill('x'.code.toByte())

        assertEquals(listOf("first"), log.snapshot())
    }

    @Test
    fun onlyTheGivenRangeIsRead() {
        val buf = "xxabc\nyy".toByteArray()
        log.append(buf, 2, 4)

        assertEquals(listOf("abc"), log.snapshot())
    }

    @Test
    fun everyChangeMovesTheRevision() {
        val start = log.revision.value
        log.append("a")
        log.mark("b")

        assertTrue(log.revision.value >= start + 2)
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: compilation fails with `Unresolved reference 'LogBuffer'`.

- [ ] **Step 3: Implement**

`app/src/main/java/id/or/oo/hr/gateway/LogBuffer.kt`:

```kotlin
package id.or.oo.hr.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class LogBuffer(
    private val maxLines: Int = MAX_LINES,
    private val maxLineChars: Int = MAX_LINE_CHARS,
    private val secrets: () -> List<String> = { emptyList() },
) {
    private enum class Mode { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE, CHARSET }

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    private var mode = Mode.TEXT

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun append(buf: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            val input = ByteBuffer.allocate(carry.size + len)
            input.put(carry).put(buf, off, len)
            input.flip()
            val output = CharBuffer.allocate(input.remaining())
            decoder.decode(input, output, false)
            carry = ByteArray(input.remaining()).also { input.get(it) }
            output.flip()
            while (output.hasRemaining()) feed(output.get())
            _revision.value += 1
        }
    }

    fun mark(text: String) {
        synchronized(lock) {
            if (current.isNotEmpty()) endLine()
            mode = Mode.TEXT
            carry = ByteArray(0)
            decoder.reset()
            current.append(text)
            endLine()
            _revision.value += 1
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        if (current.isEmpty()) lines.toList() else lines.toList() + redact(current.toString())
    }

    fun tail(count: Int): List<String> = snapshot().takeLast(count)

    private fun feed(c: Char) {
        mode = when (mode) {
            Mode.TEXT -> text(c)
            Mode.ESCAPE -> when (c) {
                '[' -> Mode.CSI
                ']' -> Mode.OSC
                '(', ')', '*', '+' -> Mode.CHARSET
                else -> Mode.TEXT
            }
            Mode.CSI -> if (c in '@'..'~') Mode.TEXT else Mode.CSI
            Mode.OSC -> when (c) {
                BEL -> Mode.TEXT
                ESC -> Mode.OSC_ESCAPE
                else -> Mode.OSC
            }
            Mode.OSC_ESCAPE, Mode.CHARSET -> Mode.TEXT
        }
    }

    private fun text(c: Char): Mode {
        when {
            c == ESC -> return Mode.ESCAPE
            c == '\n' -> endLine()
            c == '\t' || (c >= ' ' && c != DEL) -> if (current.length < maxLineChars) current.append(c)
        }
        return Mode.TEXT
    }

    private fun endLine() {
        lines.addLast(redact(current.toString()))
        current.setLength(0)
        while (lines.size > maxLines) lines.removeFirst()
    }

    private fun redact(line: String): String =
        secrets().filter { it.length >= MIN_SECRET }.fold(line) { text, secret -> text.replace(secret, REDACTED) }

    companion object {
        const val MAX_LINES = 2_000
        const val MAX_LINE_CHARS = 2_000
        private const val MIN_SECRET = 8
        private const val REDACTED = "[redacted]"
        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
        private const val DEL = '\u007f'
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest
for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`; `LogBufferTest` 14; 28 tests in all, no failures.

- [ ] **Step 5: Check coverage**

Run the coverage command from Global Constraints.

Expected: `gateway/LogBuffer.kt` at 100%; the Task 2 files unchanged at 100%.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/gateway/LogBuffer.kt app/src/test/java/id/or/oo/hr/gateway/LogBufferTest.kt
git commit -F- <<'EOF'
feat: decode the gateway's PTY output into a bounded log

LogBuffer is the command session's sink. It decodes UTF-8 across
reads, drops CR, strips CSI, OSC and other escape sequences even when
they are split between reads, keeps the newest 2,000 lines of at most
2,000 characters, redacts given secrets, and marks each start.
EOF
```

### Task 5: Installing the image

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/image/Http.kt`, `.../image/ImageRegistry.kt`, `.../image/ImageStore.kt`
- Test: `app/src/test/java/id/or/oo/hr/TestHttpServer.kt`, `.../image/UrlHttpTest.kt`, `.../image/GhcrRegistryTest.kt`, `.../image/ImageStoreTest.kt`

**Interfaces:**
- Consumes: `DistroCommand` (`suspend fun run(args: List<String>, onLine: (String) -> Unit): Int`; `PrCliCommand(host)` in the app, which strips ANSI and CR per line), pr-cli `install <ref> --override-alias <alias>` and `remove <alias>`, `HrFiles`, `writePrivate`, `Hermes`, test fixture `eventually`.
- Produces:
  - `data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)`, `fun interface Http { fun get(url: String, headers: Map<String, String>): HttpResponse }`, `class UrlHttp(timeoutMs: Int = 15_000) : Http`
  - `data class ImageBuild(val tag: String, val version: String)`, `fun interface ImageRegistry { fun resolve(tag: String): ImageBuild }`, `class GhcrRegistry(http: Http, host: String = "ghcr.io", repository: String = "oonid/hermes-agent") : ImageRegistry`
  - `data class InstalledImage(val reference: String, val buildTag: String, val version: String, val installedAt: Long)`
  - `class ImageStore(command: DistroCommand, registry: ImageRegistry, files: HrFiles, scope: CoroutineScope, inUse: () -> Boolean, clock: () -> Long = System::currentTimeMillis)` with `enum class Kind { Install, Remove }`, `data class Operation(kind, lines, running, succeeded)`, `val installed: StateFlow<InstalledImage?>`, `val operation: StateFlow<Operation?>`, `val containerExists: Boolean`, `fun install(): Boolean`, `fun remove(): Boolean`, `fun dismiss(): Boolean`, `fun refresh()`

How the registry is read: an anonymous token from `https://ghcr.io/token?scope=repository:oonid/hermes-agent:pull&service=ghcr.io`; the manifest of `:gateway` (today a single arm64 manifest; an index is narrowed to `linux/arm64`); its config blob, which ghcr.io serves through a redirect to a signed URL that must be fetched without the token; the labels give `<version>-p<patch-hash>[-<variant>]`. On 2026-10-22 `:gateway` resolved to `v0.22.3-pab570a0b-gateway`. `ImageStore` runs one operation at a time in the application scope, like pr's `DistroOperations`, and an installed image is the record in `files/hr/image.properties` together with the container's `rootfs`.

The JVM tests use `org.json:json` (Task 1's `testImplementation`), because Android's `org.json` is a stub on the JVM, and a small socket server, because `com.sun.net.httpserver` is not on the Android test classpath.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/id/or/oo/hr/TestHttpServer.kt`:

```kotlin
package id.or.oo.hr

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class TestHttpServer(private val respond: (path: String, headers: Map<String, String>) -> String) : AutoCloseable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "test-http") {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@thread
                }
                client.use(::serve)
            }
        }
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val path = requestLine.split(' ')[1]
        requests += path
        client.getOutputStream().apply {
            write(respond(path, headers).toByteArray())
            flush()
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        fun response(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): String {
            val length = body.toByteArray().size
            val head = (headers + mapOf("Content-Length" to "$length", "Connection" to "close"))
                .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            return "HTTP/1.1 $code Status\r\n$head\r\n$body"
        }
    }
}
```

`app/src/test/java/id/or/oo/hr/image/UrlHttpTest.kt`:

```kotlin
package id.or.oo.hr.image

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlHttpTest {

    private val server = TestHttpServer { path, headers ->
        when (path) {
            "/ok" -> response(200, "accept=${headers["accept"]}", mapOf("Docker-Content-Digest" to "sha256:abc"))
            "/moved" -> response(307, headers = mapOf("Location" to "https://example.org/blob"))
            else -> response(404, "not here")
        }
    }
    private val base = "http://127.0.0.1:${server.port}"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aGetSendsTheHeadersAndReturnsTheBodyAndLowercaseHeaders() {
        val response = UrlHttp().get("$base/ok", mapOf("Accept" to "application/json"))

        assertEquals(200, response.code)
        assertEquals("accept=application/json", response.body)
        assertEquals("sha256:abc", response.headers["docker-content-digest"])
    }

    @Test
    fun aRedirectIsReturnedNotFollowed() {
        val response = UrlHttp().get("$base/moved", emptyMap())

        assertEquals(307, response.code)
        assertEquals("https://example.org/blob", response.headers["location"])
        assertEquals(listOf("/moved"), server.requests)
    }

    @Test
    fun anErrorStatusKeepsItsBody() {
        val response = UrlHttp().get("$base/missing", emptyMap())

        assertEquals(404, response.code)
        assertEquals("not here", response.body)
    }
}
```

`app/src/test/java/id/or/oo/hr/image/GhcrRegistryTest.kt`:

```kotlin
package id.or.oo.hr.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhcrRegistryTest {

    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private val routes = mutableMapOf<String, HttpResponse>()
    private val http = Http { url, headers ->
        requests += url to headers
        routes[url] ?: HttpResponse(404, emptyMap(), "{\"errors\":[{\"code\":\"NOT_FOUND\"}]}")
    }
    private val registry = GhcrRegistry(http)

    private val tokenUrl = "https://ghcr.io/token?scope=repository:oonid/hermes-agent:pull&service=ghcr.io"
    private val manifestUrl = "https://ghcr.io/v2/oonid/hermes-agent/manifests/gateway"
    private val configDigest = "sha256:da7c6ac2f2e2c3b56e097763024fbe7d4eaa58d7a4b6e7e6c0eb04771974cc5f"
    private val blobUrl = "https://ghcr.io/v2/oonid/hermes-agent/blobs/$configDigest"
    private val signedUrl = "https://pkg-containers.githubusercontent.com/ghcr1/blobs/sha256:da7c6a?se=2026"
    private val auth = mapOf("Authorization" to "Bearer t0ken")

    private fun json(body: String) = HttpResponse(200, emptyMap(), body)

    private fun manifest(config: String = configDigest) = """
        {"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json",
         "config":{"mediaType":"application/vnd.docker.container.image.v1+json","size":4383,"digest":"$config"},
         "layers":[{"mediaType":"application/vnd.docker.image.rootfs.diff.tar.gzip","size":4187659,
         "digest":"sha256:a9986cd6f37dbddae7862a6d4be71683472e7c2ea708e87db14f8a6393c00f00"}]}
    """.trimIndent()

    private fun config(variant: String = "gateway") = """
        {"architecture":"arm64","os":"linux","config":{"Env":["HERMES_HOME=/root/.hermes/profiles/android"],
         "Labels":{"io.github.oonid.hermes.patch-hash":"ab570a0b",
         "io.github.oonid.hermes.upstream-commit":"5b147ab036d6d19131bff8d20701b5001eab844a",
         "io.github.oonid.hermes.variant":"$variant",
         "org.opencontainers.image.revision":"819ce3dcb815dfe45506c4eb02ad0aafa16698d1",
         "org.opencontainers.image.version":"v0.22.3"}}}
    """.trimIndent()

    private fun serve(manifestBody: String = manifest(), configBody: String = config()) {
        routes[tokenUrl] = json("{\"token\":\"t0ken\"}")
        routes[manifestUrl] = json(manifestBody)
        routes[blobUrl] = HttpResponse(307, mapOf("location" to signedUrl), "")
        routes[signedUrl] = json(configBody)
    }

    @Test
    fun thePullTagResolvesToTheImmutableBuildTagFromTheLabels() {
        serve()

        assertEquals(ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3"), registry.resolve("gateway"))
    }

    @Test
    fun theRegistryIsAskedAnonymouslyAndTheSignedBlobUrlWithoutTheToken() {
        serve()

        registry.resolve("gateway")

        assertEquals(listOf(tokenUrl, manifestUrl, blobUrl, signedUrl), requests.map { it.first })
        assertEquals(emptyMap<String, String>(), requests[0].second)
        assertEquals("Bearer t0ken", requests[1].second["Authorization"])
        assertEquals(
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
            requests[1].second["Accept"],
        )
        assertEquals(auth, requests[2].second)
        assertEquals(emptyMap<String, String>(), requests[3].second)
    }

    @Test
    fun anIndexIsNarrowedToTheLinuxArm64Image() {
        val arm64 = "sha256:" + "a".repeat(64)
        serve(
            manifestBody = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                 {"digest":"sha256:${"b".repeat(64)}","platform":{"architecture":"amd64","os":"linux"}},
                 {"digest":"$arm64","platform":{"architecture":"arm64","os":"linux"}}]}
            """.trimIndent(),
        )
        routes["https://ghcr.io/v2/oonid/hermes-agent/manifests/$arm64"] = json(manifest())

        assertEquals(ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3"), registry.resolve("gateway"))
        assertEquals("https://ghcr.io/v2/oonid/hermes-agent/manifests/$arm64", requests[2].first)
    }

    @Test
    fun anIndexWithoutArm64IsReported() {
        serve(
            manifestBody = """
                {"schemaVersion":2,"manifests":[{"digest":"sha256:${"b".repeat(64)}",
                 "platform":{"architecture":"amd64","os":"linux"}}]}
            """.trimIndent(),
        )

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway has no linux/arm64 image", e.message)
    }

    @Test
    fun theFullVariantHasNoSuffix() {
        serve(configBody = config(variant = "full"))

        assertEquals(ImageBuild("v0.22.3-pab570a0b", "v0.22.3"), registry.resolve("gateway"))
    }

    @Test
    fun anImageWithoutBuildLabelsIsReported() {
        serve(configBody = """{"architecture":"arm64","os":"linux","config":{}}""")

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway has no build labels", e.message)
    }

    @Test
    fun anHttpErrorNamesTheStepAndTheStatus() {
        serve()
        routes.remove(manifestUrl)

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway: manifest gateway: HTTP 404", e.message)
    }
}
```

`app/src/test/java/id/or/oo/hr/image/ImageStoreTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests and see them fail**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: compilation fails with `Unresolved reference 'HttpResponse'`, `Unresolved reference 'Http'` and, further down, `Unresolved reference 'ImageStore'`.

- [ ] **Step 3: Implement**

`app/src/main/java/id/or/oo/hr/image/Http.kt`:

```kotlin
package id.or.oo.hr.image

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String)

fun interface Http {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class UrlHttp(private val timeoutMs: Int = TIMEOUT_MS) : Http {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val names = connection.headerFields
                .filterKeys { it != null }
                .map { (name, values) -> name.lowercase() to values.lastOrNull().orEmpty() }
                .toMap()
            return HttpResponse(code, names, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
```

`app/src/main/java/id/or/oo/hr/image/ImageRegistry.kt`:

```kotlin
package id.or.oo.hr.image

import org.json.JSONObject
import java.net.URL

data class ImageBuild(val tag: String, val version: String)

fun interface ImageRegistry {
    fun resolve(tag: String): ImageBuild
}

class GhcrRegistry(
    private val http: Http,
    private val host: String = "ghcr.io",
    private val repository: String = "oonid/hermes-agent",
) : ImageRegistry {

    override fun resolve(tag: String): ImageBuild {
        val image = "$host/$repository:$tag"
        val token = JSONObject(fetch(image, "token", tokenUrl(), emptyMap()).body).getString("token")
        val auth = mapOf("Authorization" to "Bearer $token")
        var manifest = JSONObject(fetch(image, "manifest $tag", manifestUrl(tag), auth + ACCEPT).body)
        val entries = manifest.optJSONArray("manifests")
        if (entries != null) {
            val arm64 = (0 until entries.length()).map(entries::getJSONObject).firstOrNull { entry ->
                val platform = entry.optJSONObject("platform")
                platform?.optString("os") == "linux" && platform.optString("architecture") == "arm64"
            } ?: error("$image has no linux/arm64 image")
            val digest = arm64.getString("digest")
            manifest = JSONObject(fetch(image, "manifest $digest", manifestUrl(digest), auth + ACCEPT).body)
        }
        val configDigest = manifest.getJSONObject("config").getString("digest")
        val config = JSONObject(blob(image, configDigest, auth).body)
        val labels = config.optJSONObject("config")?.optJSONObject("Labels")
        val version = labels?.optString(VERSION).orEmpty()
        val patchHash = labels?.optString(PATCH_HASH).orEmpty()
        val variant = labels?.optString(VARIANT).orEmpty()
        check(version.isNotEmpty() && patchHash.isNotEmpty() && variant.isNotEmpty()) { "$image has no build labels" }
        val suffix = if (variant == "full") "" else "-$variant"
        return ImageBuild("$version-p$patchHash$suffix", version)
    }

    private fun blob(image: String, digest: String, auth: Map<String, String>): HttpResponse {
        val url = "https://$host/v2/$repository/blobs/$digest"
        val response = http.get(url, auth)
        if (response.code in 300..399) {
            val location = response.headers["location"] ?: error("$image: config $digest: redirect without a location")
            return fetch(image, "config $digest", URL(URL(url), location).toString(), emptyMap())
        }
        return checked(image, "config $digest", response)
    }

    private fun fetch(image: String, step: String, url: String, headers: Map<String, String>): HttpResponse =
        checked(image, step, http.get(url, headers))

    private fun checked(image: String, step: String, response: HttpResponse): HttpResponse {
        check(response.code == 200) { "$image: $step: HTTP ${response.code}" }
        return response
    }

    private fun tokenUrl() = "https://$host/token?scope=repository:$repository:pull&service=$host"

    private fun manifestUrl(reference: String) = "https://$host/v2/$repository/manifests/$reference"

    private companion object {
        const val VERSION = "org.opencontainers.image.version"
        const val PATCH_HASH = "io.github.oonid.hermes.patch-hash"
        const val VARIANT = "io.github.oonid.hermes.variant"
        val ACCEPT = mapOf(
            "Accept" to "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
        )
    }
}
```

`app/src/main/java/id/or/oo/hr/image/ImageStore.kt`:

```kotlin
package id.or.oo.hr.image

import id.or.oo.hr.Hermes
import id.or.oo.hr.HrFiles
import id.or.oo.hr.writePrivate
import id.or.oo.pr.session.DistroCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

data class InstalledImage(val reference: String, val buildTag: String, val version: String, val installedAt: Long)

class ImageStore(
    private val command: DistroCommand,
    private val registry: ImageRegistry,
    private val files: HrFiles,
    private val scope: CoroutineScope,
    private val inUse: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { Install, Remove }

    data class Operation(val kind: Kind, val lines: List<String>, val running: Boolean, val succeeded: Boolean = false)

    private val lock = Any()

    private val _installed = MutableStateFlow(load())
    val installed: StateFlow<InstalledImage?> = _installed

    private val _operation = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = _operation

    val containerExists: Boolean
        get() = files.containerDir(Hermes.CONTAINER).exists()

    fun install(): Boolean = synchronized(lock) {
        if (_installed.value != null) return false
        start(Kind.Install) { installing() }
    }

    fun remove(): Boolean = synchronized(lock) {
        if (_operation.value?.running == true) return false
        if (inUse()) {
            _operation.value = Operation(Kind.Remove, listOf(IN_USE), running = false)
            return false
        }
        start(Kind.Remove) { removing() }
    }

    fun dismiss(): Boolean = synchronized(lock) {
        if (_operation.value?.running == true) return false
        _operation.value = null
        true
    }

    fun refresh() {
        _installed.value = load()
    }

    private fun start(kind: Kind, work: suspend () -> Boolean): Boolean {
        if (_operation.value?.running == true) return false
        _operation.value = Operation(kind, emptyList(), running = true)
        scope.launch {
            val succeeded = try {
                work()
            } catch (e: Exception) {
                append("ERROR: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            _operation.update { it?.copy(running = false, succeeded = succeeded) }
        }
        return true
    }

    private suspend fun installing(): Boolean {
        append("Resolving ${Hermes.REPOSITORY}:${Hermes.PULL_TAG}…")
        val build = withContext(Dispatchers.IO) { registry.resolve(Hermes.PULL_TAG) }
        val reference = "${Hermes.REPOSITORY}:${build.tag}"
        append("Installing $reference as ${Hermes.CONTAINER}…")
        val code = command.run(listOf("install", reference, "--override-alias", Hermes.CONTAINER), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        val image = InstalledImage(reference, build.tag, build.version, clock())
        save(image)
        _installed.value = image
        append("Installed ${build.tag}.")
        return true
    }

    private suspend fun removing(): Boolean {
        append("Removing ${Hermes.CONTAINER}…")
        val code = command.run(listOf("remove", Hermes.CONTAINER), ::append)
        if (code != 0) {
            append("pr-cli exited with $code.")
            return false
        }
        files.imageRecord.delete()
        _installed.value = null
        append("Removed. The profile in hr's files is kept.")
        return true
    }

    private fun append(line: String) {
        _operation.update { it?.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    private fun load(): InstalledImage? {
        if (!files.imageRecord.isFile || !files.rootfs(Hermes.CONTAINER).isDirectory) return null
        val properties = Properties().apply { load(StringReader(files.imageRecord.readText())) }
        return InstalledImage(
            reference = properties.getProperty(REFERENCE) ?: return null,
            buildTag = properties.getProperty(BUILD_TAG) ?: return null,
            version = properties.getProperty(VERSION) ?: return null,
            installedAt = properties.getProperty(INSTALLED_AT)?.toLongOrNull() ?: return null,
        )
    }

    private fun save(image: InstalledImage) {
        val properties = Properties().apply {
            setProperty(REFERENCE, image.reference)
            setProperty(BUILD_TAG, image.buildTag)
            setProperty(VERSION, image.version)
            setProperty(INSTALLED_AT, image.installedAt.toString())
        }
        val text = StringWriter().also { properties.store(it, null) }.toString()
        writePrivate(files.imageRecord, text)
    }

    private companion object {
        const val MAX_LINES = 200
        const val IN_USE = "Stop the gateway and end the terminal before removing the image."
        const val REFERENCE = "reference"
        const val BUILD_TAG = "build_tag"
        const val VERSION = "version"
        const val INSTALLED_AT = "installed_at"
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

```bash
./gradlew :app:testDebugUnitTest
for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`; `UrlHttpTest` 3, `GhcrRegistryTest` 7, `ImageStoreTest` 10; 48 tests in all, no failures.

- [ ] **Step 5: Check coverage**

Run the coverage command from Global Constraints.

Expected: `image/Http.kt` 100%, `image/ImageRegistry.kt` 97%, `image/ImageStore.kt` 97%; the earlier files unchanged.

- [ ] **Step 6: Check the registry reader against ghcr.io (optional, needs the network)**

```bash
T=$(curl -s "https://ghcr.io/token?scope=repository:oonid/hermes-agent:pull&service=ghcr.io" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
D=$(curl -s -H "Authorization: Bearer $T" -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json" https://ghcr.io/v2/oonid/hermes-agent/manifests/gateway | python3 -c 'import sys,json;print(json.load(sys.stdin)["config"]["digest"])')
curl -sL -H "Authorization: Bearer $T" "https://ghcr.io/v2/oonid/hermes-agent/blobs/$D" | python3 -c 'import sys,json;l=json.load(sys.stdin)["config"]["Labels"];print(l["org.opencontainers.image.version"]+"-p"+l["io.github.oonid.hermes.patch-hash"]+"-"+l["io.github.oonid.hermes.variant"])'
```

Expected: the current build tag, for example `v0.22.3-pab570a0b-gateway`, which also appears in `curl -s -H "Authorization: Bearer $T" https://ghcr.io/v2/oonid/hermes-agent/tags/list`. If the manifest step fails because `:gateway` became an index, `GhcrRegistry` already handles it (`anIndexIsNarrowedToTheLinuxArm64Image`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/image app/src/test/java/id/or/oo/hr/image \
  app/src/test/java/id/or/oo/hr/TestHttpServer.kt
git commit -F- <<'EOF'
feat: install the gateway image by its build tag

GhcrRegistry reads the labels of the image behind :gateway (version,
patch hash, variant) and gives its immutable build tag; pr-cli keeps
no labels, so this is the only exact record. ImageStore installs that
tag through pr-cli under alias hermes, records it in
files/hr/image.properties, removes the container on request while no
session runs, and keeps the profile, which lives outside it.
EOF
```

### Task 6: The gateway lifecycle

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/gateway/GatewayState.kt`, `.../gateway/GatewayProbe.kt`, `.../gateway/GatewayController.kt`, `.../gateway/GatewayText.kt`
- Test: `app/src/test/java/id/or/oo/hr/gateway/GatewayControllerTest.kt`, `.../gateway/GatewayTextTest.kt`, `.../gateway/LocalGatewayProbeTest.kt`

**Interfaces:**
- Consumes: `SessionManager.open(key, factory): OpenResult` (`Opened`, `Ending`, `Failed`), `SessionManager.end(key)`, `SessionManager.SessionFactory`, `ManagedSession.exit: StateFlow<Exit?>`, `ManagedSession.endRequested`, `ManagedSession.end()`, `LogBuffer.mark`, `LogBuffer.tail`, `Http`, `UrlHttp`, `HttpResponse`, `Hermes`; test fixtures `FakePty`, `eventually`, and `ManagedSession(alias, pty, emulator, output, onEnded, endGraceMs, signalWaitMs, exitCodeWaitMs)`.
- Produces:
  - `sealed interface GatewayState` with `Stopped(exitCode: Int? = null)`, `Starting(since: Long)`, `Running(since: Long, version: String?)`, `Stopping`, `StoppedUnexpectedly(exitCode: Int?, lastLines: List<String>)`, `Failed(reason: String)`; `val GatewayState.active: Boolean`
  - `data class Health(val version: String?)`, `interface GatewayProbe { fun portInUse(): Boolean; fun health(): Health? }`, `class LocalGatewayProbe(host: String = "127.0.0.1", port: Int = 8642, timeoutMs: Int = 2_000, http: Http = UrlHttp(timeoutMs)) : GatewayProbe`, `internal fun parseHealth(response: HttpResponse): Health?`
  - `fun interface GatewayLaunch { fun prepare(): SessionManager.SessionFactory }`
  - `class GatewayController(sessions: SessionManager, launch: GatewayLaunch, probe: GatewayProbe, log: LogBuffer, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, pollMs: Long = 1_000)` with `val state: StateFlow<GatewayState>`, `fun start(): Boolean`, `fun stop(): Boolean`, and `PORT_IN_USE`, `STILL_ENDING`, `START_MARK`, `LAST_LINES`
  - `fun formatDuration(ms: Long): String`, `fun describe(state: GatewayState, now: Long): String`

The flow of a start: *Starting* at once; off the main thread, the port check (a TCP connect to 127.0.0.1:8642), then `GatewayLaunch.prepare()` (in the app: the image is installed, the profile exists, the root `.env` is written, and a `CommandSessionFactory` is built with the bind, the log's `append` as sink and the 65 s signal wait), a `Starting the gateway` mark in the log, `open("hermes-gateway", factory)`, then `/health` every second until it answers (*Running*, with its version) or the session exits. An exit after End (`endRequested`) is *Stopped* with its code, which is 1 for Hermes after SIGTERM; any other exit is *Stopped unexpectedly* with the last 20 log lines. A Stop that arrives before the session opens opens nothing. The tests run the real `SessionManager` and `ManagedSession` over pr's `FakePty`; `GatewayPty` exits with 1 when signalled, as the gateway does.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/id/or/oo/hr/gateway/GatewayControllerTest.kt`:

```kotlin
package id.or.oo.hr.gateway

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
import org.junit.Test
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

    private val controller = GatewayController(
        sessions,
        { prepared.incrementAndGet(); prepare() },
        probe,
        log,
        scope,
        clock = { now },
        pollMs = 10,
    )

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
    fun stopDoesNothingWhenNothingRuns() {
        assertFalse(controller.stop())
        assertEquals(GatewayState.Stopped(), controller.state.value)
    }

    private companion object {
        const val GATEWAY_PID = 1810
    }
}
```

`app/src/test/java/id/or/oo/hr/gateway/GatewayTextTest.kt`:

```kotlin
package id.or.oo.hr.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTextTest {

    @Test
    fun durationsReadInTheirLargestUnits() {
        assertEquals("0 s", formatDuration(-5))
        assertEquals("27 s", formatDuration(27_400))
        assertEquals("3 min 5 s", formatDuration(185_000))
        assertEquals("2 h 4 min", formatDuration(7_440_000))
        assertEquals("3 d 1 h", formatDuration(262_800_000))
    }

    @Test
    fun aStopAfterEndReadsAsStoppedWhateverTheGatewaysExitCode() {
        assertEquals("Stopped", describe(GatewayState.Stopped(), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(1), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(0), 0))
        assertEquals("Stopped", describe(GatewayState.Stopped(143), 0))
        assertEquals("Stopped (exit 2)", describe(GatewayState.Stopped(2), 0))
    }

    @Test
    fun aKilledGatewayIsCalledOut() {
        assertEquals("Stopped. The gateway did not exit after SIGTERM and was killed.", describe(GatewayState.Stopped(137), 0))
    }

    @Test
    fun startingAndRunningCountFromTheStart() {
        assertEquals("Starting… 12 s", describe(GatewayState.Starting(1_000), 13_000))
        assertEquals("Running for 1 h 0 min", describe(GatewayState.Running(0, "v0.22.3"), 3_600_000))
    }

    @Test
    fun theOtherStatesSayWhatHappened() {
        assertEquals(
            "Stopping… Hermes finishes its running work first, which can take up to a minute.",
            describe(GatewayState.Stopping, 0),
        )
        assertEquals("Stopped unexpectedly (exit 78)", describe(GatewayState.StoppedUnexpectedly(78, emptyList()), 0))
        assertEquals("Stopped unexpectedly (exit unknown)", describe(GatewayState.StoppedUnexpectedly(null, emptyList()), 0))
        assertEquals("Couldn't start: no pr-cli", describe(GatewayState.Failed("no pr-cli"), 0))
    }
}
```

`app/src/test/java/id/or/oo/hr/gateway/LocalGatewayProbeTest.kt`:

```kotlin
package id.or.oo.hr.gateway

import id.or.oo.hr.TestHttpServer
import id.or.oo.hr.TestHttpServer.Companion.response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class LocalGatewayProbeTest {

    private var reply = response(200, """{"status": "ok", "platform": "hermes-agent", "version": "0.22.3"}""")
    private val server = TestHttpServer { _, _ -> reply }
    private val probe = LocalGatewayProbe(port = server.port)

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aHealthyGatewayReportsItsVersion() {
        assertEquals(Health("0.22.3"), probe.health())
        assertEquals(listOf("/health"), server.requests)
    }

    @Test
    fun aHealthAnswerWithoutAVersionIsStillHealthy() {
        reply = response(200, """{"status": "ok"}""")

        assertEquals(Health(null), probe.health())
    }

    @Test
    fun anythingButAnOkStatusIsNotHealthy() {
        reply = response(200, """{"status": "starting"}""")
        assertNull(probe.health())

        reply = response(503, """{"status": "ok"}""")
        assertNull(probe.health())

        reply = response(200, "<html>proxy</html>")
        assertNull(probe.health())
    }

    @Test
    fun aListeningPortIsInUse() {
        assertTrue(probe.portInUse())
    }

    @Test
    fun aClosedPortIsFreeAndHasNoHealth() {
        val port = ServerSocket(0).use { it.localPort }
        val closed = LocalGatewayProbe(port = port)

        assertFalse(closed.portInUse())
        assertNull(closed.health())
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: compilation fails with `Unresolved reference 'GatewayProbe'`, `Unresolved reference 'Health'` and `'portInUse' overrides nothing`.

- [ ] **Step 3: Implement**

`app/src/main/java/id/or/oo/hr/gateway/GatewayState.kt`:

```kotlin
package id.or.oo.hr.gateway

sealed interface GatewayState {
    data class Stopped(val exitCode: Int? = null) : GatewayState
    data class Starting(val since: Long) : GatewayState
    data class Running(val since: Long, val version: String?) : GatewayState
    data object Stopping : GatewayState
    data class StoppedUnexpectedly(val exitCode: Int?, val lastLines: List<String>) : GatewayState
    data class Failed(val reason: String) : GatewayState
}

val GatewayState.active: Boolean
    get() = this is GatewayState.Starting || this is GatewayState.Running || this == GatewayState.Stopping
```

`app/src/main/java/id/or/oo/hr/gateway/GatewayProbe.kt`:

```kotlin
package id.or.oo.hr.gateway

import id.or.oo.hr.Hermes
import id.or.oo.hr.image.Http
import id.or.oo.hr.image.HttpResponse
import id.or.oo.hr.image.UrlHttp
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class Health(val version: String?)

interface GatewayProbe {
    fun portInUse(): Boolean
    fun health(): Health?
}

class LocalGatewayProbe(
    private val host: String = Hermes.HOST,
    private val port: Int = Hermes.PORT,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val http: Http = UrlHttp(timeoutMs),
) : GatewayProbe {

    override fun portInUse(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: IOException) {
        false
    }

    override fun health(): Health? = try {
        parseHealth(http.get("http://$host:$port/health", emptyMap()))
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}

internal fun parseHealth(response: HttpResponse): Health? {
    if (response.code != 200) return null
    val body = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
    if (body.optString("status") != "ok") return null
    return Health(body.optString("version").ifEmpty { null })
}
```

`app/src/main/java/id/or/oo/hr/gateway/GatewayController.kt`:

```kotlin
package id.or.oo.hr.gateway

import id.or.oo.hr.Hermes
import id.or.oo.pr.session.ManagedSession
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun interface GatewayLaunch {
    fun prepare(): SessionManager.SessionFactory
}

class GatewayController(
    private val sessions: SessionManager,
    private val launch: GatewayLaunch,
    private val probe: GatewayProbe,
    private val log: LogBuffer,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
) {
    private val lock = Any()
    private var stopRequested = false

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped())
    val state: StateFlow<GatewayState> = _state

    fun start(): Boolean {
        val since = synchronized(lock) {
            if (_state.value.active) return false
            stopRequested = false
            clock().also { _state.value = GatewayState.Starting(it) }
        }
        scope.launch { run(since) }
        return true
    }

    fun stop(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current !is GatewayState.Starting && current !is GatewayState.Running) return false
            stopRequested = true
            _state.value = GatewayState.Stopping
        }
        sessions.end(Hermes.GATEWAY_KEY)
        return true
    }

    private suspend fun run(since: Long) {
        if (probe.portInUse()) return settle(GatewayState.Failed(PORT_IN_USE))
        val factory = try {
            launch.prepare()
        } catch (e: Exception) {
            return settle(GatewayState.Failed(e.message ?: e.javaClass.simpleName))
        }
        if (synchronized(lock) { stopRequested }) return settle(GatewayState.Stopped())
        log.mark(START_MARK)
        val session = when (val result = sessions.open(Hermes.GATEWAY_KEY, factory)) {
            is SessionManager.OpenResult.Failed -> return settle(GatewayState.Failed(result.reason))
            is SessionManager.OpenResult.Ending -> return settle(GatewayState.Failed(STILL_ENDING))
            is SessionManager.OpenResult.Opened -> result.session
        }
        if (synchronized(lock) { stopRequested }) session.end()
        follow(session, since)
    }

    private suspend fun follow(session: ManagedSession, since: Long) = coroutineScope {
        val ready = launch {
            while (isActive) {
                val health = probe.health()
                if (health != null) {
                    _state.compareAndSet(GatewayState.Starting(since), GatewayState.Running(since, health.version))
                    break
                }
                delay(pollMs)
            }
        }
        val exit = session.exit.filterNotNull().first()
        ready.cancel()
        settle(
            if (session.endRequested) GatewayState.Stopped(exit.code)
            else GatewayState.StoppedUnexpectedly(exit.code, log.tail(LAST_LINES)),
        )
    }

    private fun settle(state: GatewayState) {
        synchronized(lock) { _state.value = state }
    }

    companion object {
        const val POLL_MS = 1_000L
        const val LAST_LINES = 20
        const val START_MARK = "Starting the gateway"
        const val PORT_IN_USE = "something already answers on 127.0.0.1:8642, perhaps a gateway started " +
            "in another app; hr does not start a second one"
        const val STILL_ENDING = "the previous gateway is still stopping; try again when it has stopped"
    }
}
```

`app/src/main/java/id/or/oo/hr/gateway/GatewayText.kt`:

```kotlin
package id.or.oo.hr.gateway

fun formatDuration(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> "${seconds / 60} min ${seconds % 60} s"
        seconds < 86_400 -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
        else -> "${seconds / 86_400} d ${seconds % 86_400 / 3_600} h"
    }
}

fun describe(state: GatewayState, now: Long): String = when (state) {
    is GatewayState.Stopped -> when (state.exitCode) {
        null, 0, 1, 143 -> "Stopped"
        137 -> "Stopped. The gateway did not exit after SIGTERM and was killed."
        else -> "Stopped (exit ${state.exitCode})"
    }
    is GatewayState.Starting -> "Starting… ${formatDuration(now - state.since)}"
    is GatewayState.Running -> "Running for ${formatDuration(now - state.since)}"
    GatewayState.Stopping -> "Stopping… Hermes finishes its running work first, which can take up to a minute."
    is GatewayState.StoppedUnexpectedly -> "Stopped unexpectedly (exit ${state.exitCode ?: "unknown"})"
    is GatewayState.Failed -> "Couldn't start: ${state.reason}"
}
```

- [ ] **Step 4: Run the tests and see them pass, five times**

```bash
./gradlew :app:testDebugUnitTest
for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
for i in 1 2 3 4; do ./gradlew -q :app:testDebugUnitTest --rerun || break; done
```

Expected: `BUILD SUCCESSFUL`; `GatewayControllerTest` 13, `GatewayTextTest` 5, `LocalGatewayProbeTest` 5; 71 tests in all, no failures; the four reruns print nothing (the controller tests use real threads and `eventually`, so a timing assumption would show up here).

- [ ] **Step 5: Check coverage**

Run the coverage command from Global Constraints.

Expected: `gateway/GatewayController.kt` 98%, `gateway/GatewayProbe.kt`, `gateway/GatewayState.kt` and `gateway/GatewayText.kt` 100%; the earlier files unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/gateway/GatewayState.kt app/src/main/java/id/or/oo/hr/gateway/GatewayProbe.kt \
  app/src/main/java/id/or/oo/hr/gateway/GatewayController.kt app/src/main/java/id/or/oo/hr/gateway/GatewayText.kt \
  app/src/test/java/id/or/oo/hr/gateway/GatewayControllerTest.kt app/src/test/java/id/or/oo/hr/gateway/GatewayTextTest.kt \
  app/src/test/java/id/or/oo/hr/gateway/LocalGatewayProbeTest.kt
git commit -F- <<'EOF'
feat: start, watch and stop the gateway session

GatewayController refuses to start when something answers on
127.0.0.1:8642, prepares the launch, opens the command session under
key hermes-gateway, and polls /health until it answers. End leads to
Stopped with the exit code, where Hermes's 1 after SIGTERM is clean
and 137 means it was killed; any other exit is Stopped unexpectedly
with the last log lines. A Stop before the session opens opens none.
EOF
```

### Task 7: The screens

**Files:**
- Modify: `app/src/main/java/id/or/oo/hr/HrApp.kt`, `app/src/main/java/id/or/oo/hr/MainActivity.kt`
- Create: `app/src/main/java/id/or/oo/hr/ui/HrTheme.kt`, `.../ui/HrScreens.kt`, `.../ui/HomeScreen.kt`, `.../ui/LogsScreen.kt`, `.../ui/SettingsScreen.kt`, `.../ui/SessionBanners.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–6; `PrCliCommand(host)`, `CommandSessionFactory(host, container, command, sink, binds, signalWaitMs)` (Task 0), `SessionService`, `Context.isIgnoringBatteryOptimizations()`, `SessionManager.running`, `keepCpuAwake`, `setKeepCpuAwake`, `backgroundError`.
- Produces:
  - `HrApp`: `val gatewayLog: LogBuffer` (secrets: the listener key), `val images: ImageStore`, `val gateway: GatewayController`; the launch that checks the image, ensures the profile, writes the root `.env` and builds the gateway's `CommandSessionFactory`
  - `class HrActions(startGateway: () -> Unit, openTerminal: () -> Unit, askNotifications: () -> Unit)`, `enum class Screen { Home, Logs, Settings }`, `@Composable fun HrScreens(app: HrApp, actions: HrActions)`, `HomeScreen`, `LogsScreen`, `SettingsScreen`, `SessionBanners`, `Banner`, `LogLines`, `HrTheme`, `Context.requestBatteryExemption()`, `Context.canPostNotifications()`

What each screen shows:

- **Home:** the image (its build tag, or Install with the download size, the install lines and Dismiss); the gateway (`describe(state, now)` ticking every second, the version from `/health` and the API address while *Running*, the last lines after an unexpected stop; Start, Stop, Restart, or a disabled Stopping…); Keep CPU awake while a session runs, the battery banner, the background-start error; the last 8 log lines with Full log; Terminal and Settings.
- **Gateway log:** every line of `LogBuffer`, following the end.
- **Settings:** notifications (Allow, which asks on API 33+, and Open settings), battery (Allow unrestricted, the system exemption dialog), the image (reference, install time, Remove image with a confirmation, disabled while a session runs).

`MainActivity` asks for `POST_NOTIFICATIONS` once, on the first Start or Terminal tap (API 33+), remembered in `hr_prefs`; Settings can ask again. When the permission or the battery exemption is newly granted while sessions run, the foreground service is started again so the running notification appears and its text updates. These are Android glue: the check is the build here and Task 8.

- [ ] **Step 1: Wire the log, the image and the gateway into `HrApp`**

`app/src/main/java/id/or/oo/hr/HrApp.kt`:

```kotlin
package id.or.oo.hr

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import id.or.oo.hr.gateway.GatewayController
import id.or.oo.hr.gateway.GatewayLaunch
import id.or.oo.hr.gateway.LocalGatewayProbe
import id.or.oo.hr.gateway.LogBuffer
import id.or.oo.hr.image.GhcrRegistry
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.UrlHttp
import id.or.oo.hr.profile.ProfileStore
import id.or.oo.pr.engine.NetworkDns
import id.or.oo.pr.engine.ProotHost
import id.or.oo.pr.session.CommandSessionFactory
import id.or.oo.pr.session.NotificationEndedNotifier
import id.or.oo.pr.session.PrBootstrap
import id.or.oo.pr.session.PrCliCommand
import id.or.oo.pr.session.ProotSessionFactory
import id.or.oo.pr.session.ServiceForegroundController
import id.or.oo.pr.session.SessionHost
import id.or.oo.pr.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.Inet6Address

class HrApp : Application(), ProotHost, SessionHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val files: HrFiles by lazy { HrFiles(filesDir) }

    val profiles: ProfileStore by lazy { ProfileStore(files) }

    override val prefixDir: File get() = files.prefixDir

    override val homeDir: File get() = files.homeDir

    @get:JvmName("getProotPackageName")
    override val packageName: String get() = super.getPackageName()

    @get:JvmName("getProotCacheDir")
    override val cacheDir: File get() = super.getCacheDir()

    override val dnsServers: List<String>
        get() {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            val link = connectivity.getLinkProperties(network) ?: return emptyList()
            val hasIpv6DefaultRoute = link.routes.any {
                it.destination.prefixLength == 0 && it.destination.address is Inet6Address
            }
            return NetworkDns.usableServers(link.dnsServers, hasIpv6DefaultRoute)
        }

    override val sessions: SessionManager by lazy {
        SessionManager(
            ProotSessionFactory(this, listOf(profiles.bind())),
            ServiceForegroundController(this),
            NotificationEndedNotifier(this),
        )
    }

    override val notificationIcon: Int get() = R.drawable.ic_notification

    override fun openAppIntent(): Intent = Intent(this, MainActivity::class.java)

    val gatewayLog: LogBuffer by lazy { LogBuffer(secrets = { listOf(profiles.apiServerKey()) }) }

    val images: ImageStore by lazy {
        ImageStore(
            PrCliCommand(this),
            GhcrRegistry(UrlHttp()),
            files,
            scope,
            inUse = { sessions.running.value.isNotEmpty() },
        )
    }

    val gateway: GatewayController by lazy {
        GatewayController(sessions, GatewayLaunch { prepareGateway() }, LocalGatewayProbe(), gatewayLog, scope)
    }

    override fun onCreate() {
        super.onCreate()
        PrBootstrap(this, this, BOOTSTRAP_VERSION).run()
        profiles.ensureProfile()
    }

    private fun prepareGateway(): SessionManager.SessionFactory {
        check(images.installed.value != null) { "install the Hermes image first" }
        profiles.ensureProfile()
        profiles.writeRootEnv(files.rootfs(Hermes.CONTAINER))
        return CommandSessionFactory(
            this,
            Hermes.CONTAINER,
            Hermes.GATEWAY_COMMAND,
            gatewayLog::append,
            listOf(profiles.bind()),
            signalWaitMs = Hermes.GATEWAY_SIGNAL_WAIT_MS,
        )
    }

    private companion object {
        const val BOOTSTRAP_VERSION = 1
    }
}
```

- [ ] **Step 2: Write the theme and the navigation**

`app/src/main/java/id/or/oo/hr/ui/HrTheme.kt`:

```kotlin
package id.or.oo.hr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1A1A2E)
private val Gold = Color(0xFFE9B44C)

@Composable
fun HrTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Gold, onPrimary = Navy, background = Navy, surface = Navy)
    } else {
        lightColorScheme(primary = Navy, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
```

`app/src/main/java/id/or/oo/hr/ui/HrScreens.kt`:

```kotlin
package id.or.oo.hr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.or.oo.hr.HrApp

class HrActions(
    val startGateway: () -> Unit,
    val openTerminal: () -> Unit,
    val askNotifications: () -> Unit,
)

enum class Screen(val title: String) { Home("hr"), Logs("Gateway log"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrScreens(app: HrApp, actions: HrActions) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = { screen = Screen.Home }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.Home -> HomeScreen(app, actions, modifier) { screen = it }
            Screen.Logs -> LogsScreen(app, modifier)
            Screen.Settings -> SettingsScreen(app, actions, modifier)
        }
    }
}
```

- [ ] **Step 3: Write Home**

`app/src/main/java/id/or/oo/hr/ui/HomeScreen.kt`:

```kotlin
package id.or.oo.hr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.or.oo.hr.Hermes
import id.or.oo.hr.HrApp
import id.or.oo.hr.gateway.GatewayState
import id.or.oo.hr.gateway.describe
import id.or.oo.hr.image.ImageStore
import id.or.oo.hr.image.InstalledImage
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(app: HrApp, actions: HrActions, modifier: Modifier, open: (Screen) -> Unit) {
    val installed by app.images.installed.collectAsState()
    val operation by app.images.operation.collectAsState()
    val state by app.gateway.state.collectAsState()
    val revision by app.gatewayLog.revision.collectAsState()
    val recent = remember(revision) { app.gatewayLog.tail(RECENT_LINES) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ImageCard(installed, operation, onInstall = { app.images.install() }, onDismiss = { app.images.dismiss() })
        if (installed != null) {
            GatewayCard(state, now, onStart = actions.startGateway, onStop = { app.gateway.stop() })
        }
        SessionBanners(app)
        if (recent.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Recent log", style = MaterialTheme.typography.titleSmall)
                    LogLines(recent)
                    TextButton(onClick = { open(Screen.Logs) }) { Text("Full log") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions.openTerminal, enabled = installed != null) { Text("Terminal") }
            OutlinedButton(onClick = { open(Screen.Settings) }) { Text("Settings") }
        }
    }
}

@Composable
private fun ImageCard(
    installed: InstalledImage?,
    operation: ImageStore.Operation?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Hermes image", style = MaterialTheme.typography.titleSmall)
            if (installed != null) {
                Text(installed.buildTag)
            } else {
                Text("Hermes is not installed. The ${Hermes.PULL_TAG} image downloads about 255 MB and takes about 750 MB.")
            }
            operation?.let { current ->
                LogLines(current.lines.takeLast(RECENT_LINES))
                if (!current.running) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (installed == null && operation?.running != true) {
                Button(onClick = onInstall) { Text("Install") }
            }
        }
    }
}

@Composable
private fun GatewayCard(state: GatewayState, now: Long, onStart: () -> Unit, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Gateway", style = MaterialTheme.typography.titleSmall)
            Text(describe(state, now))
            if (state is GatewayState.Running) {
                Text("Version ${state.version ?: "unknown"}")
                Text("API http://${Hermes.HOST}:${Hermes.PORT}")
            }
            if (state is GatewayState.StoppedUnexpectedly) LogLines(state.lastLines)
            when (state) {
                is GatewayState.Starting, is GatewayState.Running -> Button(onClick = onStop) { Text("Stop") }
                GatewayState.Stopping -> Button(onClick = {}, enabled = false) { Text("Stopping…") }
                is GatewayState.StoppedUnexpectedly -> Button(onClick = onStart) { Text("Restart") }
                is GatewayState.Stopped, is GatewayState.Failed -> Button(onClick = onStart) { Text("Start") }
            }
        }
    }
}

@Composable
fun LogLines(lines: List<String>) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        lines.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val RECENT_LINES = 8
```

- [ ] **Step 4: Write the log screen**

`app/src/main/java/id/or/oo/hr/ui/LogsScreen.kt`:

```kotlin
package id.or.oo.hr.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import id.or.oo.hr.HrApp

@Composable
fun LogsScreen(app: HrApp, modifier: Modifier) {
    val revision by app.gatewayLog.revision.collectAsState()
    val lines = remember(revision) { app.gatewayLog.snapshot() }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }
    if (lines.isEmpty()) {
        Text("The gateway has not written anything yet.", modifier = modifier.padding(12.dp))
        return
    }
    LazyColumn(state = list, modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        itemsIndexed(lines) { _, line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

- [ ] **Step 5: Write the banners and Settings**

`app/src/main/java/id/or/oo/hr/ui/SessionBanners.kt`:

```kotlin
package id.or.oo.hr.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import id.or.oo.hr.HrApp
import id.or.oo.pr.session.SessionService
import id.or.oo.pr.session.isIgnoringBatteryOptimizations

@Composable
fun SessionBanners(app: HrApp) {
    val context = LocalContext.current
    val running by app.sessions.running.collectAsState()
    val keepAwake by app.sessions.keepCpuAwake.collectAsState()
    val backgroundError by app.sessions.backgroundError.collectAsState()
    val resumes = remember { mutableIntStateOf(0) }
    val granted = remember { booleanArrayOf(context.canPostNotifications(), context.isIgnoringBatteryOptimizations()) }
    LifecycleResumeEffect(Unit) {
        resumes.intValue++
        val notifications = context.canPostNotifications()
        val exempt = context.isIgnoringBatteryOptimizations()
        val newlyGranted = (notifications && !granted[0]) || (exempt && !granted[1])
        if (newlyGranted && app.sessions.running.value.isNotEmpty()) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }
        granted[0] = notifications
        granted[1] = exempt
        onPauseOrDispose {}
    }
    val exempt = remember(resumes.intValue, keepAwake) { context.isIgnoringBatteryOptimizations() }

    Column {
        if (running.isNotEmpty() && backgroundError == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep CPU awake", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = keepAwake, onCheckedChange = { app.sessions.setKeepCpuAwake(it) })
                }
            }
        }
        if (keepAwake && !exempt) {
            Banner(
                text = "Some phones' battery savers can still stop the gateway. Allow hr to run unrestricted.",
                action = "Allow",
                onAction = { context.requestBatteryExemption() },
            )
        }
        backgroundError?.let { reason ->
            Banner(text = "Background running unavailable: $reason", action = null, onAction = {})
        }
    }
}

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@SuppressLint("BatteryLife")
fun Context.requestBatteryExemption() {
    try {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        )
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (missing: ActivityNotFoundException) {
        }
    }
}
```

`app/src/main/java/id/or/oo/hr/ui/SettingsScreen.kt`:

```kotlin
package id.or.oo.hr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import id.or.oo.hr.HrApp
import id.or.oo.hr.image.ImageStore
import id.or.oo.pr.session.isIgnoringBatteryOptimizations
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(app: HrApp, actions: HrActions, modifier: Modifier) {
    val context = LocalContext.current
    val resumes = remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes.intValue++
        onPauseOrDispose {}
    }
    val notifications = remember(resumes.intValue) { context.canPostNotifications() }
    val exempt = remember(resumes.intValue) { context.isIgnoringBatteryOptimizations() }
    val installed by app.images.installed.collectAsState()
    val operation by app.images.operation.collectAsState()
    val running by app.sessions.running.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Notifications") {
            Text(if (notifications) "Allowed" else "Not allowed: hr cannot tell you when the gateway stops.")
            if (!notifications) {
                Row {
                    Button(onClick = actions.askNotifications) { Text("Allow") }
                    TextButton(onClick = { context.openNotificationSettings() }) { Text("Open settings") }
                }
            }
        }
        Section("Battery") {
            Text(if (exempt) "Unrestricted" else "Optimised: some phones stop background apps that are not exempt.")
            if (!exempt) Button(onClick = { context.requestBatteryExemption() }) { Text("Allow unrestricted") }
        }
        Section("Hermes image") {
            val image = installed
            if (image == null) {
                Text("Not installed")
            } else {
                Text(image.reference)
                Text("Installed ${DateFormat.getDateTimeInstance().format(Date(image.installedAt))}")
            }
            operation?.takeIf { it.kind == ImageStore.Kind.Remove }?.let { LogLines(it.lines) }
            if (app.images.containerExists) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = running.isEmpty() && operation?.running != true,
                ) { Text("Remove image") }
                if (running.isNotEmpty()) Text("Stop the gateway and end the terminal to remove the image.")
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the Hermes image?") },
            text = { Text("The container is deleted. Your profile (config, keys, sessions, memories) stays in hr's files.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    app.images.remove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

fun Context.canPostNotifications(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}
```

- [ ] **Step 6: Host the screens in `MainActivity`**

`app/src/main/java/id/or/oo/hr/MainActivity.kt`:

```kotlin
package id.or.oo.hr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import id.or.oo.hr.ui.HrActions
import id.or.oo.hr.ui.HrScreens
import id.or.oo.hr.ui.HrTheme
import id.or.oo.pr.session.SessionService

class MainActivity : ComponentActivity() {

    private val app: HrApp get() = application as HrApp

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && app.sessions.running.value.isNotEmpty()) {
                ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = HrActions(
            startGateway = {
                askNotificationsOnce()
                app.gateway.start()
            },
            openTerminal = {
                askNotificationsOnce()
                startActivity(Intent(this, TerminalActivity::class.java))
            },
            askNotifications = ::askNotifications,
        )
        setContent { HrTheme { HrScreens(app, actions) } }
    }

    override fun onResume() {
        super.onResume()
        app.images.refresh()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
        askNotifications()
    }

    private companion object {
        const val PREFS = "hr_prefs"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
```

- [ ] **Step 7: Build and run every test**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
for f in app/build/test-results/testDebugUnitTest/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
```

Expected: `BUILD SUCCESSFUL`, no warning from a file under `app/src/main/java/id/or/oo/hr/`; 71 tests, no failures; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/id/or/oo/hr/HrApp.kt app/src/main/java/id/or/oo/hr/MainActivity.kt \
  app/src/main/java/id/or/oo/hr/ui
git commit -F- <<'EOF'
feat: show install, gateway status, logs and settings

Home installs the image and shows its build tag, starts and stops the
gateway with its state, uptime and version, and shows the last log
lines. The log screen follows the whole buffer. Settings asks for the
notification permission and the battery exemption and removes the
image. HrApp owns the log, the image store and the gateway controller;
the gateway session gets the profile bind and a 65 s signal wait.
EOF
```

### Task 8: Phone check (design §4 M1, §7, §11)

**Files:** none. **Interfaces:** none. No commit; put the results (times, build tag, versions, pass or fail per step) in the pull request, without device names or serials.

A person runs this with one arm64 phone on Android 13 or later connected (`adb devices` lists exactly one), with the network, from the hr repository root on a host with `curl` and `python3`. pr may be installed too; its data is separate. Paths below are relative to hr's `files` directory unless they start with `/`.

- [ ] **Step 1: Build, install and open**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n id.or.oo.hr/.MainActivity
sleep 5
adb logcat -d -s PR | grep -E 'Running bootstrap|Bootstrap complete|Bootstrap failed'
adb shell run-as id.or.oo.hr ls -l files/usr/bin/pr-cli files/usr/bin/proot
adb shell run-as id.or.oo.hr ls -la files/hr
adb logcat -d -b crash
```

Expected: Home shows "Hermes is not installed…" and Install; `Running bootstrap...` and `Bootstrap complete (...ms)`, no `Bootstrap failed`; `pr-cli` and `proot` link to `lib*.so` in the app's native library directory (pr ships no busybox since `b691445`, so there is no `sh` link); `files/hr` holds `profile/`; no crash output.

- [ ] **Step 2: Install the image**

Tap Install; note the time until "Installed ….".

```bash
adb shell run-as id.or.oo.hr cat files/hr/image.properties
adb shell run-as id.or.oo.hr ls files/usr/var/lib/pr/containers
T=$(curl -s "https://ghcr.io/token?scope=repository:oonid/hermes-agent:pull&service=ghcr.io" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -H "Authorization: Bearer $T" https://ghcr.io/v2/oonid/hermes-agent/tags/list
```

Expected: the lines go "Resolving ghcr.io/oonid/hermes-agent:gateway…", "Installing ghcr.io/oonid/hermes-agent:<build tag> as hermes…", pr-cli's download and extract lines without escape codes, "Installed <build tag>."; about a minute (48 s measured for this image with pr). `image.properties` holds `reference`, `build_tag`, `version` and `installed_at`; `containers` lists `hermes`; the build tag is among the tags on ghcr.io. Home now shows the build tag and the gateway card, *Stopped*.

- [ ] **Step 3: First Start and the notification permission**

Tap Start. Expected at once: the system asks to allow notifications (API 33+); allow it. Note the time from Start to *Running*.

```bash
adb shell dumpsys package id.or.oo.hr | grep 'android.permission.POST_NOTIFICATIONS: granted'
adb forward tcp:8642 tcp:8642
curl -s http://127.0.0.1:8642/health; echo
adb shell dumpsys activity services id.or.oo.hr | grep -E 'SessionService|isForeground'
adb shell dumpsys notification --noredact | grep -E 'android.title=|android.text=' | grep -E 'session|hermes'
adb shell run-as id.or.oo.hr ps -A -o PID,PPID,NAME | grep -E 'proot|pr-cli|python|hermes'
```

Expected: `granted=true`; the state goes *Starting… N s* to *Running for …* in about 25–60 s, with "Version" equal to the `version` in `/health`, which prints `{"status": "ok", "platform": "hermes-agent", "version": "…"}`; `SessionService` with `isForeground=true`; the notification "1 session running" with text `hermes-gateway`; a `pr-cli`, a `proot` and the gateway's processes. Home's recent log shows the gateway's own output, readable, without escape codes or `^M`.

- [ ] **Step 4: The listener settings and the key stay private**

```bash
adb shell run-as id.or.oo.hr ls -l files/hr/api_server_key files/usr/var/lib/pr/containers/hermes/rootfs/root/.hermes/.env
adb shell run-as id.or.oo.hr grep -v '^API_SERVER_KEY=' files/usr/var/lib/pr/containers/hermes/rootfs/root/.hermes/.env
adb shell "run-as id.or.oo.hr awk -F= '/^API_SERVER_KEY=/ {print length(\$2)}' files/usr/var/lib/pr/containers/hermes/rootfs/root/.hermes/.env"
KEY=$(adb shell run-as id.or.oo.hr cat files/hr/api_server_key)
adb logcat -d | grep -c "$KEY"
```

Expected: both files `-rw-------`; the `.env` reads `API_SERVER_ENABLED=true`, `API_SERVER_HOST=127.0.0.1`, `API_SERVER_PORT=8642` besides the key line; the key is 64 characters; logcat holds it 0 times. Do not print, paste or save `$KEY` anywhere; `unset KEY` at the end of Task 8.

- [ ] **Step 5: A terminal beside the gateway, in the same profile**

Tap Terminal, and run in it:

```sh
hermes --version
hermes gateway status
echo from-terminal > /root/.hermes/profiles/android/marker
ls -la /root/.hermes/profiles/android
```

```bash
adb shell run-as id.or.oo.hr cat files/hr/profile/marker
adb shell dumpsys notification --noredact | grep -E 'android.title=|android.text=' | grep -E 'session|hermes'
```

Expected: `hermes --version` prints the version Home shows (`Hermes Agent v0.22.3 …` for the build above); `hermes gateway status` reports the gateway running and serving the `android` profile; `marker` reads `from-terminal` on the host side; the notification says "2 sessions running" with `hermes-gateway, hermes`. Press Back and choose Keep running: Home still shows *Running*. Open Terminal again: the same shell, with the commands above still on screen. Press Back and choose End terminal: the notification goes back to "1 session running", the gateway stays *Running*, and `curl -s http://127.0.0.1:8642/health` still answers.

- [ ] **Step 6: In the background, with the CPU kept awake and the battery exemption**

On Home, switch Keep CPU awake on. Expected: the battery banner appears ("Some phones' battery savers can still stop the gateway…") and the notification text adds "CPU kept awake · Battery saver may stop sessions; allow in the app". Tap Allow and accept the system dialog.

```bash
adb shell dumpsys deviceidle whitelist | grep id.or.oo.hr
adb shell dumpsys power | grep 'pr:sessions'
```

Expected: a `user,id.or.oo.hr,…` line; a `PARTIAL_WAKE_LOCK 'pr:sessions'` line; the banner is gone and the notification no longer mentions the battery saver; Settings shows Battery "Unrestricted". Press Home, turn the screen off, wait 10 minutes, then:

```bash
curl -s http://127.0.0.1:8642/health; echo
```

Expected: the health answer; reopening hr shows *Running for 10 min …* or more.

- [ ] **Step 7: End stops the real gateway with SIGTERM (idle)**

Tap Stop. Note the time until *Stopped*.

```bash
adb shell run-as id.or.oo.hr find files/usr/var/lib/pr/containers/hermes/rootfs/root/.hermes/logs files/hr/profile -name gateway.log
LOG=files/usr/var/lib/pr/containers/hermes/rootfs/root/.hermes/logs/gateway.log
adb shell "run-as id.or.oo.hr grep -h -E 'Received SIG|Gateway stopped' $LOG" | tail -n 4
adb shell run-as id.or.oo.hr ps -A -o PID,NAME | grep -E 'proot|pr-cli|python|hermes'
adb shell dumpsys activity services id.or.oo.hr | grep SessionService
adb shell dumpsys notification --noredact | grep 'session ended'
```

Expected: `find` lists the gateway's log (if it is not the one in `LOG`, set `LOG` to the path it prints); *Stopping…* then *Stopped* within a few seconds (0.12 s teardown was measured for an idle gateway under pr), never "Stopped. The gateway did not exit after SIGTERM and was killed."; the gateway's log has a `Received SIGTERM …` line followed by `Gateway stopped (total teardown 0.…s)`; no process left; no `SessionService`; no "session ended" notification (End was asked for).

- [ ] **Step 8: The drain with an API run in flight**

On the host, start a model that answers after 45 s, reachable from the phone, and give the profile a model and an API key through hr's files:

```bash
mkdir -p /tmp/hr-drain && cat > /tmp/hr-drain/slow_model.py <<'EOF'
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY = float(sys.argv[1]) if len(sys.argv) > 1 else 35


class SlowModel(BaseHTTPRequestHandler):
    def send_json(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.send_json({"object": "list", "data": [{"id": "slow", "object": "model", "context_length": 131072}]})

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        print(time.strftime("%H:%M:%S"), "request", self.path, flush=True)
        time.sleep(DELAY)
        created = int(time.time())
        if request.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for delta, finish in (({"role": "assistant", "content": "done"}, None), ({}, "stop")):
                chunk = {"id": "slow-1", "object": "chat.completion.chunk", "created": created, "model": "slow",
                         "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
                self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            self.send_json({"id": "slow-1", "object": "chat.completion", "created": created, "model": "slow",
                            "choices": [{"index": 0, "message": {"role": "assistant", "content": "done"},
                                         "finish_reason": "stop"}],
                            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
        print(time.strftime("%H:%M:%S"), "replied", flush=True)


ThreadingHTTPServer(("127.0.0.1", 8000), SlowModel).serve_forever()
EOF
python3 -I /tmp/hr-drain/slow_model.py 45 &
adb reverse tcp:8000 tcp:8000
printf 'model:\n  default: slow\n  provider: custom\n  base_url: http://127.0.0.1:8000/v1\n  api_key: none\n  context_length: 131072\n' \
  | adb shell "run-as id.or.oo.hr sh -c 'cat > files/hr/profile/config.yaml'"
printf 'API_SERVER_KEY=drain-check-0123456789abcdef\n' \
  | adb shell "run-as id.or.oo.hr sh -c 'cat > files/hr/profile/.env'"
```

Tap Start and wait for *Running*. Then:

```bash
curl -s -m 120 http://127.0.0.1:8642/p/android/v1/chat/completions \
  -H 'Authorization: Bearer drain-check-0123456789abcdef' -H 'Content-Type: application/json' \
  -d '{"model": "hermes-agent", "messages": [{"role": "user", "content": "hi"}]}' &
```

When the slow model prints `request /v1/chat/completions`, tap Stop at once and note the time until *Stopped*.

```bash
adb shell "run-as id.or.oo.hr grep -h -E 'Received SIG|Gateway stopped' $LOG" | tail -n 4
```

Expected: *Stopping…* for roughly 30–40 s (Hermes waits up to its 30 s `cron_drain_timeout` for the API run, then interrupts it and cleans up), then *Stopped*, not the "killed" text; `Gateway stopped (total teardown N s)` with N below 60; `curl` ends with an error or an interrupted answer. Record N in the pull request. If the state reads "killed", or N is 60 or more, the 65 s signal wait is too short for this Hermes: raise `Hermes.GATEWAY_SIGNAL_WAIT_MS` in a follow-up. Under pr's fixed 10 s the same run would have been SIGKILLed at about 12 s.

- [ ] **Step 9: End all from the notification**

Tap Start, wait for *Running*, open Terminal, press Home, and tap End all in the notification.

Expected: both sessions stop; Home shows *Stopped* (not "killed", not *Stopped unexpectedly*); no "session ended" notification.

- [ ] **Step 10: An unexpected exit, and Restart**

Tap Start, wait for *Running*, open Terminal and run:

```sh
kill -9 "$(sed -n 's/.*"pid": \([0-9]*\).*/\1/p' /root/.hermes/gateway.pid)"
```

(The gateway runs as `python3 -I -c '<launcher>' …`, so `pkill -f 'hermes gateway run'` matches nothing; its PID is in `gateway.pid`. The 137 below needs pr `9c06739` or later, whose proot reports a death by signal.)

Press Back, choose Keep running.

```bash
adb shell dumpsys notification --noredact | grep 'android.title=hermes-gateway: session ended'
```

Expected: Home shows *Stopped unexpectedly (exit 137)* (or another non-zero code) with the last log lines and Restart; the notification "hermes-gateway: session ended (exit …)". Tap Restart: *Starting*, then *Running*.

- [ ] **Step 11: Something else on port 8642**

Tap Stop and wait for *Stopped*. In the terminal (still running from Step 10) run:

```sh
while true; do nc -l -p 8642 < /dev/null; done &
```

Tap Start. Expected: *Couldn't start: something already answers on 127.0.0.1:8642, perhaps a gateway started in another app; hr does not start a second one*; the notification lists only `hermes`. In the terminal run `kill %1; pkill nc`, tap Start again: *Running*. Tap Stop.

- [ ] **Step 12: The profile survives a reinstall of the container**

With the terminal from Step 10 still running, open Settings. Expected: Remove image is disabled, with "Stop the gateway and end the terminal to remove the image." End the terminal (Terminal, Back, End terminal); Settings now enables Remove image. Tap it and confirm.

```bash
adb shell run-as id.or.oo.hr ls files/usr/var/lib/pr/containers
adb shell run-as id.or.oo.hr ls -a files/hr/profile
adb shell run-as id.or.oo.hr ls files/hr
```

Expected: "Removed. The profile in hr's files is kept."; `containers` no longer lists `hermes`; `files/hr/profile` still holds `marker`, `config.yaml`, `.env` and what Hermes wrote there; `files/hr` has no `image.properties` but keeps `api_server_key`; Home offers Install. Tap Install, then Start: *Running*. Open Terminal and run `cat /root/.hermes/profiles/android/marker /root/.hermes/profiles/android/config.yaml`: `from-terminal` and the model block from Step 8. End the terminal and Stop the gateway.

- [ ] **Step 13: Android kills the app**

Tap Start, wait for *Running*, then:

```bash
adb shell am force-stop id.or.oo.hr
curl -s -m 3 http://127.0.0.1:8642/health || echo down
adb shell am start -n id.or.oo.hr/.MainActivity
```

Expected: `down`; hr opens on Home with the build tag and the gateway *Stopped*; Start works again (then Stop).

- [ ] **Step 14: Clean up and check for crashes**

```bash
adb shell run-as id.or.oo.hr rm files/hr/profile/config.yaml files/hr/profile/.env files/hr/profile/marker
adb reverse --remove tcp:8000
adb forward --remove tcp:8642
kill %1
unset KEY
adb logcat -d -b crash
```

Expected: no crash output.

---

## Self-Review Against the Design

| Design section | Covered by |
|----------------|-----------|
| §4 M1: install the image | Task 5 (`GhcrRegistry`, `ImageStore`), Task 7 (Home's Install), Task 8 Steps 2 and 12 |
| §4 M1: start, stop and End the gateway in the background | Task 0 (per-session signal wait), Task 6 (`GatewayController`), Task 7 (Start, Stop, Restart; `SessionService` through `SessionManager`), Task 8 Steps 3, 6–10 |
| §4 M1: status (health, uptime, version) | Task 6 (`LocalGatewayProbe`, `Running(since, version)`, `describe`, `formatDuration`), Task 7 (Home ticks every second), Task 8 Step 3 |
| §4 M1: logs | Task 4 (`LogBuffer`), Task 7 (recent lines on Home, the Gateway log screen, last lines after an unexpected stop), Task 8 Steps 3, 7 and 10 |
| §4 M1: a terminal into the container | Task 0 (terminal binds), Task 3 (`TerminalActivity`, key `hermes`), Task 8 Step 5 |
| §4 M1: the profile kept in hr's own files | Task 2 (`ProfileStore`), Task 3 (terminal bind), Task 7 (gateway bind), Task 8 Steps 5 and 12 |
| §4: each milestone ends with a check on a phone | Task 8 |
| §5: a Compose app, arm64-v8a, minSdk 28, on `:proot-engine`, `:termlib` and `:session-core` | Task 1 |
| §5: `ImageStore` (install, list and remove; the alias and build tag) | Task 5; "list" is the one `hermes` container and its record, since M1 has one image (M4 adds aliases per build) |
| §5: `GatewayController` (start, stop, watch, poll `/health`, state) | Task 6 |
| §5: `ProfileStore` (`config.yaml` and `.env` in hr's files) | Task 2: the profile directory, its bind and the root `.env`; writing the profile's `config.yaml` and `.env` is M2's setup |
| §5: UI Home, Logs, Terminal, Settings | Task 3 (Terminal), Task 7 (Home, Logs, Settings); Setup and Chat are M2 and M3 |
| §6: container alias `hermes` in hr's private files; hr's data apart from pr's | Global Constraints; Task 1 (app id `id.or.oo.hr`), Task 5 (`--override-alias hermes`) |
| §6: profile `files/hr/profile` bound to `/root/.hermes/profiles/android` in every session; survives image removal | Tasks 0, 2, 3 and 7; Task 8 Step 12 |
| §6: hr writes `config.yaml` and `.env` directly, without a shell | Task 2 (`writePrivate`, `mergeEnv`); Task 8 Step 8 writes a profile `config.yaml` through hr's files |
| §6: the root `API_SERVER_KEY` generated once, kept privately, `/root/.hermes/.env` written before every start; listener on `127.0.0.1` only | Task 2 (`apiServerKey`, `writeRootEnv`), Task 7 (`prepareGateway`), Task 8 Step 4 |
| §6: permissions: internet, network state, foreground service (`specialUse`), wake lock, notifications, battery exemption; no shared storage | Task 1 (manifest), Task 7 (runtime request, exemption), Task 8 Steps 3 and 6 |
| §7 Start: a headless session runs the gateway from the root home with the profile bound, under the foreground service, notification and Keep CPU awake | Task 6, Task 7 (`GATEWAY_COMMAND` with `cd /root`, the bind, `SessionBanners`), Task 8 Steps 3 and 6 |
| §7 *Starting* until `/health` answers, then *Running* | Task 6 (`startOpensTheGatewayAndItIsStartingUntilHealthAnswers`), Task 8 Step 3 |
| §7 Before Start: something on 8642 means no second gateway | Task 6 (`somethingOnThePortStopsTheStartBeforeAnythingRuns`), Task 8 Step 11 |
| §7 Stop: End sends SIGTERM and waits for the drain; exit 1 after End is clean | Task 0, Task 6 (`stopSendsEndAndExit1AfterItIsACleanStop`, `aGatewayThatIgnoresSigtermIsKilledAndSaysSo`), Task 8 Steps 7–9 |
| §7 Unexpected exit: *Stopped unexpectedly* with the last lines and Restart; no restart loop; after Android kills the app, *Stopped* | Task 6, Task 7, Task 8 Steps 10 and 13 |
| §7 Not in v1: start at boot | Not implemented: no boot receiver |
| §11: every pr-cli and gateway failure shown with its text | Task 5 (pr-cli lines, `ERROR: …`, `pr-cli exited with N.`), Task 6 (`Failed(reason)`, last lines), Task 7 |
| §11: keys never in logs or the UI | Task 2 (owner-only files), Task 4 (`secretsAreRedacted`), Task 7 (the key is never shown), Task 8 Step 4 |
| §11 JVM tests: writing `.env`; health and state transitions | Tasks 2 and 6. Writing `config.yaml`, device-code parsing, the chat stream parser, and the update pointer belong to M2, M3 and M4 |
| §11 phone checks: install, start, the API, End | Task 8 (the API here is `/health` and one `/v1` run in Step 8; chat is M3, update M4) |

Requirements carried over from the reviews of pr's `:session-core`:

| Requirement | Covered by |
|-------------|-----------|
| End's SIGTERM verified on the real gateway (clean teardown, no SIGKILL) | Task 8 Step 7 |
| The drain: up to about 40 s for Hermes against End's 10 s | Decisions (option b), Task 0, Task 6 (Stopped 137 reads as killed), Task 7 (65 s wait), Task 8 Step 8 |
| The sink is application-scoped, gets raw PTY bytes; a same-kind reopen returns the existing session | Task 4 (streaming decoder, ANSI and CR stripped, bounded), Task 7 (`HrApp.gatewayLog`), Task 6 (`aStartedGatewayIsNotStartedTwice`; a new factory only after the previous session exited) |
| `POST_NOTIFICATIONS` at runtime on API 33+; the battery exemption the notification text expects | Task 7, Task 8 Steps 3 and 6 |
| Consumer build: test-fixtures flag, `useLegacyPackaging` and `extractNativeLibs`, root compose plugin, termlib initialised, `INTERNET`, `SessionService` with hr's `specialUse` text, `ProotHost` and `SessionHost` | Task 1, Task 3 |
| A terminal and the gateway side by side under different keys | Task 3 (`hermes`), Task 6 (`hermes-gateway`), Task 8 Step 5 |
