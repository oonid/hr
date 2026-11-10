# hr M4: Image Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** hr notices a newer `gateway` build on GHCR, updates to it with one tap, keeps the old image until the new one reaches *Running*, switches back by itself if it does not, and offers to remove the old image afterwards.

**Architecture:** the image record names the container alias each build lives under, plus the previous build (kept for rollback) and an install in progress. Every container user (gateway launch, Hermes commands, the terminal, the root `.env`) asks `ImageStore` for the current alias instead of the constant `hermes`. `ImageStore` gains an Update operation that installs the new build beside the current one under its own alias, switches, restarts the gateway through the existing `GatewayRestart`, and switches back when the restart fails. `UpdateCheck` compares the `gateway` tag's build with the installed one at most once a day, or when asked.

**Tech Stack:** as M3.2; pr at `7029b1f`; no new dependencies.

**Spec:** `docs/design/hr-app.md` §3 (Updates) and §10; the findings below.

## Findings this plan relies on

- `ImageStore` (`image/ImageStore.kt`) installs `ghcr.io/oonid/hermes-agent:<build tag>` with `pr-cli install <reference> --override-alias hermes` and records `reference`, `build_tag`, `version` and `installed_at` in `files/hr/image.properties` (written with `writePrivate`, which renames a temp file into place). It treats the image as installed only when the record exists and `files/usr/var/lib/pr/containers/hermes/rootfs` is a directory. Its operations (`Kind.Install`, `Kind.Remove`) share one lock, one `operation` flow and `busy`.
- `GhcrRegistry.resolve("gateway")` returns `ImageBuild(tag, version)` for the build behind a tag. Today that is `v0.23.1-p0481780b-gateway`; the test phone runs `v0.23.0-p79f19a47-gateway`.
- pr-cli:
  - Alias rules: starts alphanumeric, then `[A-Za-z0-9_.+-]`, does not end in `.sh`, no path separators. `hermes-v0.23.1-p0481780b-gateway` is valid.
  - `rename` refuses OCI containers, so every build gets its own alias.
  - `install` of an alias that is already installed fails ("already installed").
  - An interrupted install leaves `containers/.<alias>.pr-cli-installing` (an flock marker). The next `install` of the same alias takes it over and starts clean, and `pr-cli remove <alias>` deletes it.
- These use the alias `hermes` today:
  - `HrApp.prepareGateway`: `writeRootEnv(files.rootfs(Hermes.CONTAINER))` and `CommandSessionFactory(this, Hermes.CONTAINER, …)`. It is called on every gateway start through `GatewayLaunch`, and it refuses while `images.busy`.
  - `HermesCommand`: `login hermes …`.
  - `ImageStore`.
  - The terminal: `TerminalActivity` opens session key `Hermes.TERMINAL_KEY` (`"hermes"`), and pr's `ProotSessionFactory` uses the session key as the container alias.
- `ControllerRestart.restart()` (`setup/ControllerRestart.kt`):
  - It stops the gateway, waits until it is inactive, starts it, and waits for *Running* or an inactive state (stop limit 65 s, start limit 120 s).
  - It returns `null` on *Running*, otherwise a reason.
  - If the gateway was already stopped, it simply starts it.
- An installed container is about 800 MB; the test phone had 246 GB free. The profile (`files/hr/profile`) is bind-mounted, so config, keys, logins, Hermes sessions and hr's chats survive a new image. Only the container's own `/root` starts fresh, and hr rewrites the root `.env` before every start.

## Global Constraints

- hr's AGENTS.md applies:
  - no comments; `vendor/` read only;
  - JVM logic outside Activities and `HrApp`, with JVM tests;
  - keys never reach logs, logcat or the UI;
  - stage explicit paths;
  - commits `feat|fix|chore|docs|test|build|ci: …`, body ≤ 72 columns, no trailers.
- Coverage: every changed unit keeps 80% of its lines or more.
- No device identifiers; adb commands use bare `adb`.
- Files hr writes go through `writePrivate`.
- First install keeps the alias `hermes`. Updates use `hermes-<build tag>`.
- Free space needed before an update: 2 GiB (`StatFs(filesDir).availableBytes`).
- Automatic checks happen at most once every 24 hours. *Check now* always checks.

## Review Focus

1. **An existing install keeps working after the app update.** Alias `hermes`, record without `alias`: no reinstall, and the terminal still opens the same container (Task 1, `aRecordWithoutAnAliasIsTheHermesContainer`).
2. **The new image does not reach Running** (crash, missing dependency, port taken). hr is back on the old alias with the gateway restarted, and the message names the reason. Both containers are kept, and *Update* retries without downloading again (Task 3, `aFailedStartSwitchesBack`, `retryingAFailedUpdateReusesItsContainer`).
3. **hr is killed or the phone reboots mid-update.** On the next start exactly one image is current, and an interrupted install is reported with Retry and Remove, never silently kept (Task 3, `anInterruptedInstallIsReported`, `aMissingCurrentContainerFallsBackToThePrevious`).
4. **Too little space, or the network drops during install.** The update stops before anything is switched, and the current image and its record are untouched (Task 3, `lowSpaceRefusesTheUpdate`, `aFailedInstallSwitchesNothing`).
5. **A terminal or a Hermes login is running** when Update is tapped: refused with "End the terminal and wait for the login to finish before updating." A running chat reply is cut by the restart the same way Setup's Test cuts it (Task 3, `anOpenTerminalRefusesTheUpdate`).

---

### Task 1: The current alias

**Files:**
- Modify: `image/ImageStore.kt`, `HrApp.kt`, `setup/HermesCommand.kt`, `TerminalActivity.kt`, `Hermes.kt`
- Test: `image/ImageStoreTest.kt`, `setup/HermesCommandTest.kt` (create if absent)

**Interfaces:**
- Produces:
  - `data class InstalledImage(val reference: String, val buildTag: String, val version: String, val installedAt: Long, val alias: String)`.
  - The record key is `alias`. A record without it loads with `alias = Hermes.CONTAINER`.
  - `ImageStore.currentAlias(): String?` is `installed.value?.alias`.
  - Presence (`load`, `containerOnDisk`) checks `files.rootfs(alias)` / `files.containerDir(alias)` of the recorded alias.
  - `Install` still installs as `Hermes.CONTAINER` and records that alias.
  - `HermesCommand(command, profile, alias: () -> String?)` uses `alias()` and throws `IllegalStateException("install the Hermes image first")` when it is null.
  - `prepareGateway` uses `images.currentAlias()`.
  - `TerminalActivity` uses `images.currentAlias()` as the session key and shows "Install the Hermes image first." when it is null.
  - `Hermes.TERMINAL_KEY` is removed.

- [ ] **Step 1: Write the failing tests.**
  - `aRecordWithoutAnAliasIsTheHermesContainer`: an M3 record (four keys) plus `containers/hermes/rootfs` → `installed.value?.alias == "hermes"`.
  - `aRecordedAliasIsUsed`: a record with `alias=hermes-v1` and `containers/hermes-v1/rootfs` → installed, alias `hermes-v1`.
  - `aRecordWhoseContainerIsMissingIsNotInstalled`: `alias=hermes-v1` and no rootfs → `null`.
  - `installRecordsTheHermesAlias`: after Install, the record holds `alias=hermes`.
  - `hermesCommandLogsIntoTheCurrentAlias`: the alias supplier returns `hermes-v1` → args start `login, hermes-v1, --user, root`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*ImageStoreTest' --tests '*HermesCommandTest'`. Expected: FAIL (no `alias`).
- [ ] **Step 3:** Implement the interfaces above. Wire `HrApp`: `HermesCommand(PrCliCommand(this), profiles.bind()) { images.currentAlias() }`.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest jacocoTestReport` (or the project's coverage task). Expected: PASS; ImageStore.kt and HermesCommand.kt ≥ 80%.
- [ ] **Step 5: Commit** `feat: name the container each image lives in`.

---

### Task 2: Checking for an update

**Files:**
- Create: `image/UpdateCheck.kt`
- Modify: `HrApp.kt`, `MainActivity.kt`
- Test: `image/UpdateCheckTest.kt`

**Interfaces:**
- Consumes: `ImageRegistry.resolve(tag)`, `ImageStore.installed`.
- Produces:
  - `sealed interface UpdateState`, with these variants:
    - `data object Unknown`
    - `data object Checking`
    - `data class UpToDate(val build: String)`
    - `data class Available(val installed: String, val latest: ImageBuild)`
    - `data class Failed(val reason: String)`
  - `class UpdateCheck(registry: ImageRegistry, installed: StateFlow<InstalledImage?>, store: UpdateCheck.Store, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, io: CoroutineDispatcher = Dispatchers.IO)`.
  - `interface Store { var lastCheckedAt: Long; var lastLatestTag: String?; var lastLatestVersion: String? }`.
  - `val state: StateFlow<UpdateState>` and `fun check(force: Boolean)`:
    - It does nothing while `Checking`, or without an installed image.
    - Unless `force`, it does nothing when less than 24 h have passed since `lastCheckedAt`. In that case `state` is rebuilt from the stored latest tag against the installed build.
    - Otherwise it resolves `Hermes.PULL_TAG` on `io`. A tag different from `installed.buildTag` → `Available`; the same → `UpToDate`.
    - A thrown exception → `Failed(message ?: class name)`, and `lastCheckedAt` is not advanced.
  - `HrApp.updates: UpdateCheck` stores in `hr_prefs` (`update_checked_at`, `update_latest_tag`, `update_latest_version`). `MainActivity.onStart` calls `app.updates.check(force = false)`.

- [ ] **Step 1: Write the failing tests.**
  - `aDifferentBuildIsAvailable`
  - `theSameBuildIsUpToDate`
  - `aRegistryErrorIsReportedAndRetriedNextTime`: `Failed("no network")`; the next unforced check resolves again.
  - `anUnforcedCheckWithinADayDoesNotAskTheRegistry`: the registry is called once; `state` is `Available` from the store.
  - `aForcedCheckAlwaysAsks`
  - `nothingInstalledMeansNoCheck`
- [ ] **Step 2:** Run `--tests '*UpdateCheckTest'`. Expected: FAIL (no class).
- [ ] **Step 3:** Implement as above.
- [ ] **Step 4:** Run the suite. Expected: PASS; UpdateCheck.kt ≥ 80%.
- [ ] **Step 5: Commit** `feat: check GHCR for a newer gateway build`.

---

### Task 3: Updating with rollback

**Files:**
- Modify: `image/ImageStore.kt`, `HrApp.kt`
- Test: `image/ImageStoreTest.kt`

**Interfaces:**
- Consumes: `GatewayRestart` (`setup/SetupController.kt`), `ImageBuild`.
- Produces:
  - `ImageStore` constructor additions:
    - `restart: GatewayRestart`
    - `gatewayActive: () -> Boolean`
    - `stopGateway: () -> Unit`
    - `sessionsBlocking: () -> Boolean` (a session other than the gateway is open: the terminal, or a login task)
    - `freeBytes: () -> Long`
  - `Kind.Update` and `Kind.SwitchBack`.
  - `val previous: StateFlow<InstalledImage?>`, the kept build; it needs its rootfs to exist.
  - `val interrupted: StateFlow<String?>`, the build tag of an install that did not finish.
  - `fun update(latest: ImageBuild): Boolean`, `fun switchBack(): Boolean`, `fun removePrevious(): Boolean`, `fun removeInterrupted(): Boolean`.
  - `val canLaunch: Boolean`: true when no operation runs, or while an Update or SwitchBack is in its restart step. `prepareGateway` checks it instead of `!busy`.
  - `remove()` removes the current container, the previous one and an interrupted one, then the record.
  - Record keys:
    - `alias` and the four from Task 1;
    - `previous.reference`, `previous.build_tag`, `previous.version`, `previous.installed_at`, `previous.alias`;
    - `pending.alias`, `pending.build_tag`.
    - Every change is one `writePrivate`.
- `update(latest)`, in order. Each refusal ends the operation with that line and `succeeded = false`:
  1. `sessionsBlocking()` → "End the terminal and wait for the login to finish before updating."
  2. `freeBytes() < 2 GiB` → "Updating needs 2 GB free; <n> MB are free."
  3. Target alias `hermes-<latest.tag>`. If it is the previous alias and its rootfs exists, skip to step 5.
  4. Write `pending.*`, then run `install ghcr.io/oonid/hermes-agent:<tag> --override-alias <target>`, streaming lines. A non-zero exit → "pr-cli exited with <code>.", leaving `pending.*` in place (it becomes `interrupted`); nothing switched.
  5. Note whether the gateway was active. Write the record: current = the new build, previous = the old build, no pending. Refresh the flows.
  6. `restart.restart()`. On `null`, if the gateway was not active before, call `stopGateway()`; then "Updated to <tag>. The previous image (<old tag>) is kept until you remove it."
  7. On a reason, write the record back (current = old, previous = new) and `restart.restart()` again, stopping afterwards if the gateway was not active before. Then "The new image did not start (<reason>). hr is back on <old tag>." If the second restart also fails, append "The previous image did not start either (<reason2>)."
- `switchBack()` runs steps 1, 5, 6 and 7 with the previous build as the target.
- Load recovery:
  - The current alias has no rootfs and the previous one does → load the previous as current and clear `previous.*`.
  - `pending.*` present and its alias is neither current nor previous → `interrupted = pending.build_tag`.
  - `update(latest)` with the same tag simply runs again; pr-cli takes over the marker.
  - `removeInterrupted()` runs `remove <alias>` and clears `pending.*`.
- `HrApp` wiring:
  - `restart = ControllerRestart(gateway.state, gateway::stop, gateway::start)`;
  - `gatewayActive = { gateway.state.value.active }`;
  - `stopGateway = { gateway.stop() }`;
  - `sessionsBlocking`: `sessions.running.value.any { it != Hermes.GATEWAY_KEY } || sessions.tasks.value.isNotEmpty()` (the terminal is a session keyed by its alias; a ChatGPT login is a `SessionManager` task);
  - `freeBytes = { StatFs(filesDir.path).availableBytes }`.

- [ ] **Step 1: Write the failing tests** (fake `DistroCommand`, fake `GatewayRestart` returning queued results):
  - `anUpdateInstallsBesideAndSwitches`
    - Command: `install ghcr.io/oonid/hermes-agent:v2 --override-alias hermes-v2`.
    - The record: current alias `hermes-v2`, previous `hermes`, no pending.
    - One restart; the old container is not removed.
  - `aFailedStartSwitchesBack`: restart results `["port 8642 in use", null]`.
    - Current alias `hermes`, previous `hermes-v2`.
    - The last line is "The new image did not start (port 8642 in use). hr is back on v1."
  - `retryingAFailedUpdateReusesItsContainer`: after the failure above, `update(v2)` again → no `install` call, switched, one restart.
  - `aFailedInstallSwitchesNothing`: install exit 1 → the record still current `hermes`; `interrupted == "v2"`; no restart.
  - `lowSpaceRefusesTheUpdate`: `freeBytes = 1 GiB` → no command, no record change, the line names the MB free.
  - `anOpenTerminalRefusesTheUpdate`
  - `aStoppedGatewayIsStoppedAgainAfterTheUpdate`: `gatewayActive = false` → `stopGateway` is called once after a successful restart.
  - `anInterruptedInstallIsReported`: a record with `pending.alias=hermes-v2` and no `hermes-v2/rootfs` → `interrupted == "v2"`; `removeInterrupted()` runs `remove hermes-v2` and clears it.
  - `aMissingCurrentContainerFallsBackToThePrevious`: the record current `hermes-v2` without rootfs, previous `hermes` with rootfs → installed alias `hermes`, `previous == null`.
  - `switchBackUsesThePreviousImage`
  - `removePreviousRemovesOnlyThePrevious`: runs `remove hermes`; it is refused while `previous` is null.
  - `removeRemovesEveryHermesContainer`
  - `theGatewayMayStartDuringTheUpdatesRestart`: `canLaunch` is true inside the fake restart and false during the install.
- [ ] **Step 2:** Run `--tests '*ImageStoreTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement as above, keeping all operations under the existing lock and `operation` flow.
- [ ] **Step 4:** Run the suite. Expected: PASS; ImageStore.kt ≥ 80%.
- [ ] **Step 5: Commit** `feat: update the image beside the current one and switch back on failure`.

---

### Task 4: Screens

**Files:**
- Modify: `ui/HomeScreen.kt`, `ui/SettingsScreen.kt`
- Test: none (Compose; logic is in Tasks 2–3)

**Interfaces:**
- Consumes: `app.updates.state`, `app.updates.check(force = true)`, `app.images.update(latest)`, `switchBack()`, `removePrevious()`, `removeInterrupted()`, `previous`, `interrupted`, `operation` with `Kind.Update` / `Kind.SwitchBack`.

Texts:
- **Home, the Hermes image card**, under the installed build:
  - `Available`: "Update available: <latest tag>" with **Update** (opens a confirm).
    - Title: "Update to <latest tag>?"
    - Body: "hr downloads the new image (about 800 MB), restarts the gateway on it and keeps the current one until it runs. A chat reply in progress is interrupted."
    - Buttons: **Update** / **Cancel**.
  - `UpToDate`: "Up to date".
  - `Failed`: "Couldn't check for updates: <reason>".
  - `Checking`: "Checking for updates…".
  - Always: a **Check now** text button.
  - While an Update or SwitchBack runs: its lines (as for Install), and Start buttons disabled through `canStartSession`.
  - `interrupted != null`: "An update to <tag> did not finish." with **Retry** (= `update` with the latest build when `Available`) and **Remove**.
- **Settings, the image section:**
  - "Previous image: <tag> (kept for switching back)", with **Switch back** and **Remove previous image** (confirm: "Remove <tag>? You can't switch back to it afterwards.").
  - The Remove image confirm text becomes "Remove the Hermes image? This removes every Hermes image hr installed; the profile is kept."

- [ ] **Step 1:** Implement the texts above.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `feat: show updates on Home and the previous image in Settings`.

---

### Task 5: Phone check

**Files:** none. Results go in the pull request without device names, serials, IPs or keys.

- [ ] **Step 1:** Install the debug build over the M3.2 one. Expected:
  - Home shows `v0.23.0-p79f19a47-gateway`; the gateway starts.
  - The terminal opens the same container (Review Focus 1).
  - Within a few seconds the card shows "Update available: v0.23.1-p0481780b-gateway".
- [ ] **Step 2:** Note the free space (`adb shell df -h /data`) and the size of pr's download cache.
- [ ] **Step 3:** With the gateway running and Telegram set, tap **Update**. Expected:
  - The install lines stream.
  - The gateway restarts and reaches *Running*; Hermes's `/health` shows version 0.23.1.
  - Setup status is unchanged (ChatGPT signed in, bot token saved).
  - An existing chat continues; a Telegram message is answered.
  - Settings shows the previous image.
- [ ] **Step 4:** Tap **Switch back**, then **Update** again. Expected: both reach *Running*; the second update downloads nothing.
- [ ] **Step 5:** Start an update from the previous build and force-stop hr during the download. Expected:
  - On reopening, the old build is current and runs.
  - Home reports the update that did not finish.
  - **Retry** completes it.
  - Skip this step, saying so, if the download finishes too fast to interrupt.
- [ ] **Step 6:** Rollback, from the previous build. Stop the gateway, hold its port with `adb shell toybox nc -l -p 8642`, and tap **Update**. Expected:
  - The new image fails to start (port in use) and hr switches back.
  - The previous image fails too while the port is held. The card reads "The new image did not start (…). hr is back on <old tag>. The previous image did not start either (…)."
  - After the port is released, Start works on the old build, and **Update** then succeeds without downloading.
  - If the shell user cannot hold the port, mark this step skipped (the JVM tests cover it).

- [ ] **Step 7:** **Remove previous image** → the free space returns by about 800 MB. No crashes; the API key appears 0 times in logcat (counted on the phone).
