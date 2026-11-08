# hr M2: First-Run Setup Implementation Plan

> Execute this plan task by task, with a review after each. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** hr sets Hermes up from the phone: a ChatGPT login by device code with a model picked from the account's own list, the API-key providers of the design, an optional Telegram bot token, a test request through hr's gateway, and the terminal running `hermes setup` for anything else.

**Architecture:** Setup logic lives in a new package `id.or.oo.hr.setup`, behind small interfaces with JVM tests; `SetupScreen` is thin. Hermes commands (the login, the model list, `hermes config set`) run as one-shot `pr-cli login hermes … -- <command>` processes through pr's `PrCliCommand`, with the profile bound as in the terminal, under a `SessionManager` task so the process survives while the user approves the login in a browser. hr writes the profile's `.env` itself (`mergeEnv`, `writePrivate`); `config.yaml` changes go through `hermes config set`, which merges into whatever Hermes or `hermes setup` already wrote. The test request goes to hr's own gateway at `/p/android/v1/chat/completions` with the profile's `API_SERVER_KEY`.

**Tech Stack:** as M1 (`docs/plans/m1-gateway.md`), on AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, Compose BOM 2026.09.00 and pr at `9c06739` or later.

**Spec:** `docs/design/hr-app.md` §4 (M2), §5, §6, §8; findings from the M2 investigation on the phone (2026-11-08), summarised below.

## Findings this plan relies on

Measured in hr's container with image `v0.23.0-pab570a0b-gateway` (Hermes 0.23.0):

- `hermes auth add openai-codex --no-browser` uses the device-code flow and prints, among other lines:
  `     https://auth.openai.com/codex/device` (after "Open this URL in your browser:"),
  `     6RTM-9MWDR` (after "Enter this code:"), in ANSI colour, then waits; on approval it prints
  `Added openai-codex OAuth credential #1: "openai-codex-oauth-1"` and exits 0. Approval took about 95 s.
- The credential lands in the profile: `/root/.hermes/profiles/android/auth.json`, mode `rw-------`.
  `hermes auth status openai-codex` then prints `openai-codex: logged in`.
- The login sets no model. The account's live list (Hermes's `hermes_cli.models._codex_catalog("openai-codex", True)`)
  began `gpt-6.1-sol, gpt-6.1-sol-900k, gpt-6.1-terra, …, gpt-5.6-luna-900k`; a model from Hermes's offline
  fallback (`gpt-5.4-mini`) was refused with HTTP 400 "not supported when using Codex with a ChatGPT account".
- `hermes config set model.provider openai-codex` and `hermes config set model.default <model>` write
  `model:\n  provider: openai-codex\n  default: <model>` to the profile's `config.yaml`.
- With `API_SERVER_KEY` in the profile's `.env` and the gateway restarted,
  `POST http://127.0.0.1:8642/p/android/v1/chat/completions` answered HTTP 200 in 17 s with content `ok`.
  Without a profile key, `/p/android/v1` answers 401 to every key.
- `hermes chat` in the terminal starts its own background gateway
  (`python3 -m hermes_cli.main --profile default gateway run --quiet --idle-exit`), which held port 8642 for
  more than 10 minutes; hr's Start then fails with `PORT_IN_USE`.
- Hermes's provider ids include `openrouter` (`OPENROUTER_API_KEY`) and `custom` ("Custom endpoint", OpenAI chat
  transport). `ANTHROPIC_API_KEY`, `NOUS_API_KEY`, `OPENAI_API_KEY`, `TELEGRAM_BOT_TOKEN` and
  `TELEGRAM_ALLOWED_USERS` appear in its source; the other provider ids are pinned in Task 1.

## Global Constraints

- hr's AGENTS.md applies: no comments; `vendor/` read only; logic that can run on the JVM lives outside Activities and `HrApp` behind small interfaces with JVM tests; Android glue stays thin and is checked on a phone; keys never reach logs, logcat or the UI; stage explicit paths; commits `feat|fix|chore|docs|test|build|ci: …` with a body wrapped at 72 columns, no trailers.
- Coverage: every new unit in `id.or.oo.hr.setup` keeps 80% of its lines or more.
- No device model numbers, marketing names or serials anywhere; adb commands use bare `adb`.
- Reserved by pr's `:session-core`: wake-lock tag `pr:sessions`, `pr_prefs`, notification id 1, channels `sessions_running`, `sessions_ended`. hr's preferences file is `HrApp.PREFS` (`hr_prefs`).
- Profile files hr writes go through `writePrivate` (owner-only, written then renamed). The profile `.env` always carries `API_SERVER_KEY=<ProfileStore.apiServerKey()>`, the same key the root `.env` holds.
- Hermes commands run with `--custom-bind <files.profileDir>:/root/.hermes/profiles/android` and a login shell, so `HERMES_HOME` is the `android` profile (`/etc/profile.d/hermes.sh`).
- No key is ever passed on a command line, in an environment variable of a command, or printed: keys go only into the profile `.env` by hr's own file write.

## Review Focus

1. The user leaves hr for the browser to approve the login and stays away a few minutes: the login process keeps running (a `SessionManager` task holds the foreground service) and hr shows *Logged in* on return.
2. The device code is never approved, or the user taps Cancel: the process is stopped (coroutine cancelled, pr's `ProcessRunner` stops it), the state reads *Cancelled* or *Failed* with Hermes's last line, and a new Sign in works.
3. The model list cannot be read (offline, token expiring, a Hermes update renames `_codex_catalog`): the picker falls back to a text field, and Save still works with a typed model.
4. A stray `hermes chat` gateway holds 8642 when the user taps Test: the result names that cause ("a gateway started from the terminal, for example by `hermes chat`") instead of a bare connection error.
5. Values `hermes setup` already wrote stay: Save merges into the profile `.env` (other lines kept) and changes `config.yaml` only through `hermes config set`.

---

### Task 1: Pin Hermes's provider ids and key variables on the phone

**Files:** none. **Interfaces:** Produces the table below, copied into Task 5's `ApiProvider` and Task 6's Telegram keys.

| Form entry | `model.provider` | Key variable in the profile `.env` | Other settings |
|---|---|---|---|
| OpenRouter | `openrouter` | `OPENROUTER_API_KEY` | – |
| OpenAI | `openai-api` | `OPENAI_API_KEY` | – |
| Anthropic | `anthropic` | `ANTHROPIC_API_KEY` | – |
| OpenAI-compatible | `custom` | `HERMES_CUSTOM_API_KEY` | `model.base_url`, and `model.api_key` set to the literal `${HERMES_CUSTOM_API_KEY}` |

Pinned on the phone (2026-11-08) with Hermes's `get_provider`, `PROVIDER_REGISTRY` and `custom_endpoint_key_env("")`:

- `nous` has auth type `oauth_device_code` and no key variable; `NOUS_API_KEY` is read only for the model catalogue. Nous therefore leaves the API-key form: the Advanced section points to `hermes auth add nous` in the terminal.
- A custom endpoint has no key variable of its own. `hermes setup` writes the key to `.env` and puts `${<VAR>}` in `model.api_key`, which Hermes expands when it reads the config; `HERMES_CUSTOM_API_KEY` is Hermes's own name for an endpoint without an identity.

- [x] **Step 1: List the provider definitions with Hermes's own Python**

In hr's terminal on the phone (image installed, gateway stopped), write the probe to a file and run it:

```sh
V=$(ls -d /root/.hermes/installs/*/environments/*/venv | head -1)
grep -rhoE 'id="[a-z0-9-]+", name="[^"]+"[^)]{0,200}api_key_env_vars=\([^)]*\)' /root/.hermes/hermes-agent --include=*.py | sort -u > /root/providers.txt
grep -rhoE '"(anthropic|nous|openai|openai-api)"[^\n]{0,120}' /root/.hermes/hermes-agent/hermes_cli/auth*.py | sort -u | head -40 >> /root/providers.txt
```

Expected: `/root/providers.txt` names an id for each row "to pin" above, with its `api_key_env_vars`.

- [x] **Step 2: Check each id against Hermes**

For each pinned id: `hermes config set model.provider <id> && hermes config check`. Expected: no "unknown provider". Restore `hermes config set model.provider openai-codex` afterwards, then `rm /root/providers.txt`.

- [x] **Step 3: Record the table** in this plan (replace each "to pin") and in the pull request.

---

### Task 2: Run Hermes commands in the container

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/HermesCommand.kt`
- Test: `app/src/test/java/id/or/oo/hr/setup/HermesCommandTest.kt`

**Interfaces:**
- Consumes: `id.or.oo.pr.session.DistroCommand` (`suspend fun run(args: List<String>, onLine: (String) -> Unit): Int`), `Bind`, `Hermes.CONTAINER`.
- Produces: `class HermesCommand(command: DistroCommand, profile: Bind)` with `suspend fun run(script: String, onLine: (String) -> Unit): Int`; top-level `fun stripAnsi(line: String): String`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun runsTheScriptInHermesWithTheProfileBound() {
    val seen = mutableListOf<List<String>>()
    val cmd = HermesCommand({ args, _ -> seen += args; 0 }, Bind("/data/x/files/hr/profile", "/root/.hermes/profiles/android"))
    runBlocking { cmd.run("hermes auth status openai-codex") {} }
    assertEquals(listOf("login", "hermes", "--user", "root",
        "--custom-bind", "/data/x/files/hr/profile:/root/.hermes/profiles/android",
        "--", "hermes auth status openai-codex"), seen.single())
}
@Test fun linesArriveWithoutEscapeCodes() {
    assertEquals("     6RTM-9MWDR", stripAnsi("     \u001b[94m6RTM-9MWDR\u001b[0m"))
}
@Test fun theExitCodeIsReturned() { /* fake returns 3 → run returns 3 */ }
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*HermesCommandTest*'`. Expected: FAIL (class missing).
- [ ] **Step 3: Implement** `HermesCommand` (args exactly as the test; each line passed through `stripAnsi` before `onLine`) and `stripAnsi` (remove `ESC [ … final-byte` sequences).
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: run Hermes commands in the container with the profile bound`.

---

### Task 3: The ChatGPT device-code login

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/CodexLogin.kt`
- Test: `app/src/test/java/id/or/oo/hr/setup/CodexLoginTest.kt`

**Interfaces:**
- Consumes: `HermesCommand` (Task 2); a `TaskHolder` (`fun begin(label: String, onCancel: () -> Unit): AutoCloseable`), which `HrApp` backs with `sessions.beginTask(label, onCancel)` / `endTask`. `CodexLogin` passes its own `cancel` as `onCancel`, so the notification's Cancel stops the login.
- Produces:
  - `sealed interface LoginState { Idle; Starting; Waiting(val url: String, val code: String); LoggedIn; Cancelled; Failed(val reason: String) }`
  - `class CodexLogin(command: HermesCommand, tasks: TaskHolder, scope: CoroutineScope)` with `val state: StateFlow<LoginState>`, `fun start()`, `fun cancel()`, `suspend fun check(): Boolean` (runs `hermes auth status openai-codex`, true when a line equals `openai-codex: logged in`).
  - `const val LOGIN_SCRIPT = "PYTHONUNBUFFERED=1 hermes auth add openai-codex --no-browser"`; task label `"Signing in to ChatGPT…"`.

- [ ] **Step 1: Write the failing tests** (fake `HermesCommand` feeding the lines from the Findings, one by one, then an exit code)

- `theUrlAndTheCodeAreShownWhileWaiting`: after the URL line and the code line → `Waiting("https://auth.openai.com/codex/device", "6RTM-9MWDR")`.
- `theAddedLineAndExit0AreLoggedIn`: `Added openai-codex OAuth credential #1: "openai-codex-oauth-1"`, exit 0 → `LoggedIn`.
- `aNonZeroExitIsFailedWithTheLastLine`: last line `Error: device code expired`, exit 1 → `Failed("Error: device code expired")`.
- `cancelStopsTheCommandAndSaysCancelled`: `cancel()` while `Waiting` → the fake's coroutine is cancelled, state `Cancelled`.
- `aTaskIsHeldWhileTheLoginRuns`: the `TaskHolder` is opened once at `start()` and closed after the exit (Review Focus 1).
- `startWhileRunningDoesNothing`; `checkIsTrueOnlyForLoggedIn`.

- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** `CodexLogin`. The code is the first line matching `^[A-Z0-9]{4}-[A-Z0-9]{4,6}$` after trimming; the URL is the first `https://\S+` line.
- [ ] **Step 4: Run** them. Expected: PASS; coverage of `CodexLogin.kt` ≥ 80%.
- [ ] **Step 5: Commit** `feat: sign in to ChatGPT by device code`.

---

### Task 4: The account's models and the profile settings

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/ModelCatalog.kt`, `app/src/main/java/id/or/oo/hr/setup/ProfileSettings.kt`
- Modify: `app/src/main/java/id/or/oo/hr/profile/ProfileStore.kt` (add `writeProfileEnv`)
- Test: `app/src/test/java/id/or/oo/hr/setup/ModelCatalogTest.kt`, `app/src/test/java/id/or/oo/hr/setup/ProfileSettingsTest.kt`, `app/src/test/java/id/or/oo/hr/profile/ProfileStoreTest.kt`

**Interfaces:**
- Produces:
  - `class ModelCatalog(command: HermesCommand)` with `suspend fun codexModels(): List<String>?` (null when the script fails or prints no list); `const val CODEX_MODELS_SCRIPT` runs, in the container:
    ``V=$(ls -d /root/.hermes/installs/*/environments/*/venv | head -1); "$V/bin/python3" -c 'import json,sys; sys.path.insert(0,"/root/.hermes/hermes-agent"); from hermes_cli.models import _codex_catalog; print("MODELS " + json.dumps(list(_codex_catalog("openai-codex", True))))'``
    and `codexModels` reads the line starting `MODELS `.
  - `ProfileStore.writeProfileEnv(values: Map<String, String>)`: merges `values + ("API_SERVER_KEY" to apiServerKey())` into `files.profileDir/.env` with `mergeEnv` and `writePrivate`.
  - `class ProfileSettings(command: HermesCommand, profiles: ProfileStore)` with `suspend fun useModel(provider: String, model: String, baseUrl: String? = null): Result<Unit>`: runs `hermes config set model.provider <provider> && hermes config set model.default <model>` (plus `&& hermes config set model.base_url <baseUrl>`), each value checked against `^[A-Za-z0-9._:/@+-]+$` before it reaches the script; then `profiles.writeProfileEnv(emptyMap())`.

- [ ] **Step 1: Write the failing tests**

- `theModelsLineIsParsed`: fake prints `MODELS ["gpt-6.1-sol", "gpt-5.6-luna"]` → `listOf("gpt-6.1-sol", "gpt-5.6-luna")`.
- `noModelsLineIsNull` and `aFailingScriptIsNull` (Review Focus 3).
- `theProfileEnvGetsTheKeyAndKeepsOtherLines`: existing `.env` `TELEGRAM_BOT_TOKEN=x\nFOO=bar\n`, `writeProfileEnv(mapOf("OPENROUTER_API_KEY" to "k"))` → contains `FOO=bar`, `TELEGRAM_BOT_TOKEN=x`, `OPENROUTER_API_KEY=k`, `API_SERVER_KEY=<64 hex>`; the file is owner-only (Review Focus 5).
- `useModelSetsProviderAndModelThroughHermes`: the fake receives `hermes config set model.provider openai-codex && hermes config set model.default gpt-5.6-luna`.
- `aValueWithShellCharactersIsRefused`: model `gpt;rm -rf /` → `Result.failure`, no command run.

- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** the three units.
- [ ] **Step 4: Run** them. Expected: PASS; both new files ≥ 80%.
- [ ] **Step 5: Commit** `feat: list the account's models and write the profile's settings`.

---

### Task 5: API-key providers

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/ApiProvider.kt`
- Test: `app/src/test/java/id/or/oo/hr/setup/ApiProviderTest.kt`

**Interfaces:**
- Consumes: Task 1's table, `ProfileSettings`, `ProfileStore.writeProfileEnv`.
- Produces: `enum class ApiProvider(val label: String, val id: String, val keyVariable: String, val needsBaseUrl: Boolean)` with entries `OpenRouter`, `OpenAI`, `Anthropic`, `Compatible` (values from Task 1; no Nous entry); `suspend fun ProfileSettings.useApiKey(provider: ApiProvider, key: String, model: String, baseUrl: String?): Result<Unit>` that writes `provider.keyVariable=key` with `writeProfileEnv` (never a command) and then `useModel(provider.id, model, baseUrl)`. For `Compatible`, `useModel` also runs `hermes config set model.api_key '${HERMES_CUSTOM_API_KEY}'`: a fixed literal in single quotes, never user text, so it is exempt from the value check.

- [ ] **Step 1: Write the failing tests**: `theKeyGoesOnlyToTheEnvFile` (the fake `HermesCommand` never sees the key text); `compatibleNeedsABaseUrl` (`Result.failure` without one); `compatibleReferencesItsKeyFromTheConfig` (the script contains `hermes config set model.api_key '${HERMES_CUSTOM_API_KEY}'` and `model.base_url <url>`); `aBlankKeyIsRefused`; one test per entry asserting its `id` and `keyVariable` from Task 1.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat: set up the API-key providers` (with Task 1's table in the body).

---

### Task 6: Telegram and the test request

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/TestRequest.kt`
- Modify: `app/src/main/java/id/or/oo/hr/image/Http.kt` (add `post`), `app/src/main/java/id/or/oo/hr/gateway/GatewayController.kt:154` (`PORT_IN_USE` text), `app/src/main/java/id/or/oo/hr/HrApp.kt` (log secrets)
- Test: `app/src/test/java/id/or/oo/hr/setup/TestRequestTest.kt`, `app/src/test/java/id/or/oo/hr/image/UrlHttpTest.kt`

**Interfaces:**
- Produces:
  - `Http.post(url: String, headers: Map<String, String>, body: String): HttpResponse` (default method throwing `UnsupportedOperationException` so existing fakes compile; `UrlHttp` implements it with a 120 s read timeout).
  - `sealed interface TestResult { Answered(val text: String); Rejected(val code: Int, val body: String); NoGateway; Failed(val reason: String) }`
  - `class TestRequest(http: Http, key: () -> String)` with `fun send(): TestResult`: POST `http://127.0.0.1:8642/p/android/v1/chat/completions`, header `Authorization: Bearer <key>`, body `{"model": "hermes-agent", "messages": [{"role": "user", "content": "Reply with exactly one word: ok"}]}`; 200 → `Answered(choices[0].message.content)`; 4xx/5xx → `Rejected`; connection refused → `NoGateway`.
  - `suspend fun ProfileSettings.useTelegram(token: String, allowedUsers: String)`: writes `TELEGRAM_BOT_TOKEN` and, when not blank, `TELEGRAM_ALLOWED_USERS` (digits and commas only) with `writeProfileEnv`.
  - `GatewayController.PORT_IN_USE` becomes: `"something already answers on 127.0.0.1:8642: a gateway started in another app, or from hr's terminal (hermes chat starts one); hr does not start a second one"`.
  - `HrApp.gatewayLog` secrets: the API key plus every value of the profile `.env` whose name ends in `_KEY` or `_TOKEN`.

- [ ] **Step 1: Write the failing tests** against `TestHttpServer`: `anOkAnswerIsAnswered`, `a401IsRejected`, `noServerIsNoGateway`, `theKeyIsSentAsBearer` (server sees `Authorization: Bearer k`), `telegramUsersMustBeDigits`, and in `UrlHttpTest` `postSendsTheBody`. Update the existing `PORT_IN_USE` assertion in `GatewayControllerTest` to the new text (Review Focus 4).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** all JVM tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: Telegram, the test request, and a clearer port-in-use message`.

---

### Task 7: The setup flow

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/setup/SetupController.kt`
- Test: `app/src/test/java/id/or/oo/hr/setup/SetupControllerTest.kt`

**Interfaces:**
- Consumes: `CodexLogin`, `ModelCatalog`, `ProfileSettings`, `ApiProvider`, `TestRequest`, a `GatewayRestart` (`suspend fun restart(): String?`: null when the gateway is *Running* afterwards, otherwise the reason, such as `GatewayState.Failed.reason`, which is `PORT_IN_USE` when a stray gateway holds the port).
- Produces also: `class ControllerRestart(state: StateFlow<GatewayState>, stop: () -> Boolean, start: () -> Boolean, stopMs: Long = 65_000, startMs: Long = 120_000) : GatewayRestart`, in `id.or.oo.hr.setup`, with its own tests (`restartsAndWaitsForRunning`, `aFailedStartGivesItsReason`, `aStartThatNeverRunsTimesOut`). Task 8 wires it in `HrApp` with `gateway.state`, `gateway::stop`, `gateway::start`.
- Produces: `class SetupController(…)` with `val step: StateFlow<SetupStep>` (`Choose`, `ChatGpt(login: LoginState, models: List<String>?)`, `Saving`, `Testing`, `Done(result: TestResult)`, `Error(reason: String)`), `fun chooseChatGpt()`, `fun chooseApiKey(provider, key, model, baseUrl)`, `fun saveCodexModel(model: String)`, `fun test()`.

`saveCodexModel` and `chooseApiKey` save, then `test()` restarts the gateway (Hermes reads the profile at start) and sends the request.

- [ ] **Step 1: Write the failing tests** with fakes: `loggingInThenSavingTestsThroughTheGateway` (order: login → `codexModels` → `useModel("openai-codex", m)` → `restart` → `send`); `modelsUnavailableStillAllowsATypedModel` (Review Focus 3); `aRestartThatFailsIsAnErrorWithTheGatewaysReason` (restart returns `PORT_IN_USE` → `Error` whose reason contains it, Review Focus 4); `testWithoutAnyProviderIsAnError`; and the three `ControllerRestart` tests.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `ControllerRestart` calls `stop()`, waits up to `stopMs` for a state that is not active, calls `start()`, then waits up to `startMs` for `Running` (null) or a settled non-running state (its reason: `Failed.reason`, or a text naming `StoppedUnexpectedly`'s exit code), or the bound (a timeout text).
- [ ] **Step 4: Run.** Expected: PASS; `SetupController.kt` ≥ 80%.
- [ ] **Step 5: Commit** `feat: the setup flow from provider to test request`.

---

### Task 8: The Setup screen

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/ui/SetupScreen.kt`
- Modify: `app/src/main/java/id/or/oo/hr/ui/HrScreens.kt` (`Screen.Setup("Setup")`), `app/src/main/java/id/or/oo/hr/ui/HomeScreen.kt` (a Setup button beside Terminal and Settings), `app/src/main/java/id/or/oo/hr/HrApp.kt` (build `SetupController` and its parts)

The screen, top to bottom:

- **ChatGPT:** "Sign in with ChatGPT". While `Waiting`: a button "Open https://auth.openai.com/codex/device" (`ACTION_VIEW`), the code in large monospace with Copy, "Waiting for you to approve…", Cancel. After `LoggedIn`: a model dropdown from `models`, or a text field when `models` is null; Save.
- **API key:** a provider dropdown (`ApiProvider.label`), a password field for the key (never shown back; the field is empty after Save), Model, and Base URL when `needsBaseUrl`; Save.
- **Telegram (optional):** bot token (password field) and allowed user ids; Save.
- **Test:** the button, then the answer text, or the reason.
- **Advanced:** "Open the terminal and run `hermes setup` for other providers and platforms, or `hermes auth add nous` to sign in to Nous Portal. Commands such as `hermes chat` start a gateway of their own; while it runs, hr cannot start its gateway. Use Test here instead." and a Terminal button.

- [ ] **Step 1: Build** `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 2: Commit** `feat: the Setup screen`.

---

### Task 9: Phone check

**Files:** none. Results go in the pull request without device names, serials or keys.

- [ ] **Step 1:** Install, open Setup, tap "Sign in with ChatGPT". Expected: the URL button and the code within about 10 s.
- [ ] **Step 2:** Approve in the phone's browser, staying out of hr for at least 2 minutes. Expected: back in hr, *Logged in*; `adb shell run-as id.or.oo.hr ls -l files/hr/profile/auth.json` → `-rw-------` (Review Focus 1).
- [ ] **Step 3:** The model dropdown lists the account's models; pick the smallest `luna`; Save; Test. Expected: the gateway restarts and the answer `ok` within about 60 s.
- [ ] **Step 4:** Sign in again and tap Cancel. Expected: *Cancelled*, no `hermes auth` process left (`adb shell run-as id.or.oo.hr ps -A -o ARGS | grep 'auth add'` prints nothing) (Review Focus 2).
- [ ] **Step 5:** In the terminal run `hermes chat -Q -q "hi"`, then Test. Expected: the new `PORT_IN_USE` text; after the stray gateway exits (or `kill` of its PID), Test answers again (Review Focus 4).
- [ ] **Step 6:** `adb shell run-as id.or.oo.hr ls -l files/hr/profile/.env` → `-rw-------`; the key appears 0 times in logcat, counted on the phone: `adb shell 'K=$(run-as id.or.oo.hr cat files/hr/api_server_key); logcat -d | grep -cF "$K"'`.
- [ ] **Step 7:** If you have a Telegram bot token: save it with your user id, restart the gateway, and message the bot. Expected: an answer in Telegram. Otherwise mark the step skipped.
- [ ] **Step 8:** `adb logcat -d -b crash` for `id.or.oo.hr`. Expected: nothing.
