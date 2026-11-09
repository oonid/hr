# hr M3.1: Setup Status and Chat Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Setup shows what is already configured (ChatGPT sign-in and model, API-key provider, Telegram), and the M3 chat follow-ups are closed: approval buttons match Hermes's choices, the keyboard keeps the chat followed, tool labels are redacted, every lost turn is remembered, and the gateway wait only applies when the connection was lost.

**Architecture:** a JVM-tested `ProfileStatus` reads the profile's own files (`auth.json`, `config.yaml`, `.env`) without exposing any value; `SetupController` publishes it and refreshes it after every save; the chat changes stay inside `ChatClient`, `ConversationStore`, `ChatController` and the two chat composables.

**Tech Stack:** as M3 (`docs/plans/m3-chat.md`); no new dependencies.

**Spec:** `docs/design/hr-app.md` §8 (Setup), §9 (Chat), §11; user report (2026-11-09): "I opened Setup … I didn't know if ChatGPT was already signed in or not, also I didn't know if Telegram was already entered or not"; the "Known, left for later" list of PR #6.

## Findings this plan relies on

Measured on the phone and read from Hermes `v0.23.0` (2026-11-09):

- The profile's `auth.json` (mode `0600`) is a JSON object with `version`, `providers`, `credential_pool` and `active_provider`; after the ChatGPT login both `providers` and `credential_pool` have an `openai-codex` key (`credential_pool["openai-codex"]` is a non-empty array) and `active_provider` is `"openai-codex"`. Hermes reads it as an auth store only when `providers` or `credential_pool` is an object.
- `config.yaml` holds `model:` with `provider:`, `default:` and, for a custom endpoint, `base_url:` (indented two spaces), written by `hermes config set`.
- The profile `.env` holds `NAME=value` lines; today `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USERS`, `API_SERVER_KEY` (provider keys appear as `OPENROUTER_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `HERMES_CUSTOM_API_KEY`).
- An `approval.request` carries `choices` from `["once", "session", "always", "deny"]`, reduced to `["once", "deny"]` when Hermes's smart check denied it or the session option is off, and without `"always"` when permanent approval is off. `"always"` changes Hermes's permanent allowlist.
- A healthy gateway that is slow gives `SocketTimeoutException` ("Read timed out"); a gateway that dies gives an `IOException` with a blank message, or EOF, or a connection refusal.

## Global Constraints

- hr's AGENTS.md applies: no comments; `vendor/` read only; JVM logic outside Activities and `HrApp` behind small interfaces with JVM tests; Android glue thin and checked on a phone; keys and tokens never reach logs, logcat or the UI; stage explicit paths; commits `feat|fix|chore|docs|test|build|ci: …`, body ≤ 72 columns, no trailers.
- Coverage: every changed unit in `id.or.oo.hr.setup`, `id.or.oo.hr.profile` and `id.or.oo.hr.chat` keeps 80% of its lines or more.
- No device model numbers, marketing names or serials; adb commands use bare `adb`.
- Status never shows a key, token or user id: only "saved"/"not set", a provider name, a model name, a base URL host and a count of allowed users.
- Files hr writes go through `writePrivate`.

## Review Focus

1. A profile set up only in the terminal (`hermes setup`, `hermes auth add …`): Setup's status still reflects it, read from the files, not from what hr saved.
2. A corrupt or missing `auth.json`, `config.yaml` or `.env`: status says "not set" for that part; nothing crashes.
3. Removing Telegram leaves every other `.env` line (keys, `API_SERVER_KEY`) intact.
4. An approval offering only `once`/`deny` shows two buttons; one offering `session` shows "Allow for this chat"; `always` is never offered in hr.
5. A chat whose gateway died twice in a row resolves both lost turns before the next message.

---

### Task 1: Read the profile's status

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/profile/ProfileStatus.kt`
- Modify: `app/src/main/java/id/or/oo/hr/profile/ProfileStore.kt` (`removeFromProfileEnv(names: Set<String>)`)
- Test: `app/src/test/java/id/or/oo/hr/profile/ProfileStatusTest.kt`, `ProfileStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class SetupStatus(val chatGpt: Boolean, val provider: String?, val model: String?, val baseUrlHost: String?, val savedKeys: Set<String>, val telegramToken: Boolean, val telegramUsers: Int)`.
  - `fun readStatus(profileDir: File): SetupStatus`:
    - `chatGpt`: `auth.json` parses as an object whose `credential_pool` has a non-empty `openai-codex` array or whose `providers` has an `openai-codex` object.
    - `provider`/`model`/`baseUrlHost`: from `config.yaml` (same parsing rules as `ProfileStore.hasProvider`: the `model:` block's `provider:`, `default:` and `base_url:` values, comments and quotes stripped; the host part of the base URL only).
    - `savedKeys`: the names among `OPENROUTER_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `HERMES_CUSTOM_API_KEY` with a non-blank value in `.env`.
    - `telegramToken`: a non-blank `TELEGRAM_BOT_TOKEN`; `telegramUsers`: the number of comma-separated ids in `TELEGRAM_ALLOWED_USERS` (0 when absent).
    - Any missing or unreadable file contributes nothing; never throws.
  - `ProfileStore.removeFromProfileEnv(names: Set<String>)`: rewrites the profile `.env` without those lines (keeping `API_SERVER_KEY` and everything else) through `writePrivate`, under the store's lock.

- [ ] **Step 1: Write the failing tests:** `aChatGptLoginIsSeen` (an `auth.json` shaped like the Findings, with dummy token strings); `noAuthFileIsNotSignedIn`; `aCorruptAuthFileIsNotSignedIn`; `theModelIsRead` (`openai-codex` / `gpt-5.6-luna`); `aCustomEndpointShowsOnlyItsHost` (`https://api.example.com/v1` → `api.example.com`); `savedKeysAreNamedNotShown` (the status's `toString()` contains no key value); `telegramIsCounted` (`1,22,333` → 3, token true); `removingTelegramKeepsTheRest` (Review Focus 3).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** (org.json for `auth.json`).
- [ ] **Step 4: Run.** Expected: PASS; ≥ 80%.
- [ ] **Step 5: Commit** `feat: read what the profile already has set up`.

---

### Task 2: Setup shows and refreshes the status

**Files:**
- Modify: `app/src/main/java/id/or/oo/hr/setup/SetupController.kt`, `setup/Telegram.kt` (`ProfileSettings.removeTelegram()`), `ui/SetupScreen.kt`, `ui/SetupForms.kt`, `HrApp.kt`
- Test: `SetupControllerTest.kt`, `TestRequestTest.kt` (Telegram)

**Interfaces:**
- `SetupController` gains `status: StateFlow<SetupStatus>` (constructor takes `readStatus: () -> SetupStatus`, run on `io`), refreshed at construction, by `refreshStatus()`, and after every save, Telegram removal and finished login; and `fun removeTelegram()` (Saving → `settings.removeTelegram()` → status refreshed → `Saved("Telegram removed")`).
- `suspend fun ProfileSettings.removeTelegram(): Result<Unit>`: `profiles.removeFromProfileEnv(setOf("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS"))`.
- Screen (entering Setup calls `refreshStatus()`):
  - **ChatGPT:** "Signed in · <model>" when `chatGpt` and provider is `openai-codex` (model omitted when null); "Signed in (another provider is active)" when `chatGpt` and the provider is something else; else "Not signed in". The button reads "Change model" when signed in, "Sign in with ChatGPT" otherwise.
  - **API key:** when the provider is one of `ApiProvider`'s ids: "Using <label> · <model>" (plus " · <host>" for the custom endpoint); the provider dropdown starts on it. "Key saved" next to the key field when its variable is in `savedKeys`.
  - **Telegram:** "Bot token saved · <n> allowed user(s)" or "Not set up"; a Remove button when a token is saved.

- [ ] **Step 1: Write the failing tests:** `statusIsReadAtStartAndAfterASave`; `removeTelegramClearsBothLinesAndRefreshes`; `telegramRemovalKeepsOtherKeys`.
- [ ] **Step 2–4: Run, implement, run.** Expected: PASS; changed setup files ≥ 80%.
- [ ] **Step 5: Commit** `feat: Setup shows what is already set up`.

---

### Task 3: Chat polish

**Files:**
- Modify: `chat/ChatClient.kt`, `chat/ConversationStore.kt`, `chat/ChatController.kt`, `ui/ChatConversation.kt`
- Test: `ChatClientTest.kt`, `ConversationStoreTest.kt`, `ChatControllerTest.kt`

**Interfaces and behaviour:**
- **Tool labels redacted:** `ChatClient` passes each `ChatEvent.Tool` label through `redact(label, secrets())` before `onEvent`.
- **Approval choices:** the card shows, in this order and only when in `approval.choices`: "Allow once" (`once`), "Allow for this chat" (`session`), "Deny" (`deny`); `always` is never shown. `ChatClient.answer` already refuses a choice not offered.
- **Lost turns:**
  - `Conversation.pendingTurns: List<String>` (JSON array `"pendingTurns"`, omitted when empty; a legacy `"pendingTurn"` string is read as a one-item list; any non-string element makes the file malformed);
  - before sending, every pending id is passed to `resolveLost` in order, and the resolved ones are removed;
  - a turn that ends `Broken`/`NoGateway`/`Rejected` appends its id; one that ends `Finished` or by Stop leaves only the still-unresolved older ids.
- **Wait only on a lost connection:**
  - `TurnOutcome.Broken` gains `val lost: Boolean = false`. `ChatClient` sets it for every `IOException` except `SocketTimeoutException`, and for the end-of-stream `EARLY` case.
  - `ChatController` waits for the gateway (≤3 s) only for `NoGateway` or `Broken(lost = true)`.
- **Keyboard follows:** while `follow` is true, a change in the IME's visible height (or the list's viewport height) also scrolls to the end.

- [ ] **Step 1: Write the failing tests:**
  - `toolLabelsAreRedacted` (a label containing the key → `[redacted]`).
  - `pendingTurnsRoundTripAndReadTheLegacyField`.
  - `twoLostTurnsAreBothResolvedBeforeTheNextSend` (Review Focus 5).
  - `aResolvedTurnIsDroppedAndAnUnresolvedOneKept`.
  - `aReadTimeoutDoesNotWaitForTheGateway`: `Broken(lost = false)` ends at once with its reason.
  - `aLostConnectionStillWaits`.
  - Update every existing test that names `pendingTurn`.
- [ ] **Step 2–4: Run, implement, run.** Expected: PASS; chat files ≥ 80%.
- [ ] **Step 5: Commit** `fix: chat follow-ups from M3` (and `fix: approval buttons follow Hermes's choices` for the UI part).

---

### Task 4: Spec update

**Files:** `docs/design/hr-app.md` §9 (and §8 for the status lines).

- [ ] **Step 1:** §9 says hr keeps one Hermes session per conversation (`X-Hermes-Session-Id`), sends only the new message, stores conversations for display, and resolves a turn lost to a gateway death (`Idempotency-Key`, `resolve-unknown`); §8 says Setup shows the current state of each section without showing any secret.
- [ ] **Step 2: Commit** `docs: the design follows chat sessions and Setup status`.

---

### Task 5: Phone check

**Files:** none. Results go in the pull request without device names, serials, IPs or keys.

- [ ] **Step 1:** Open Setup. Expected: "Signed in · gpt-5.6-luna", the Telegram line "Bot token saved · 1 allowed user", and a Remove button.
- [ ] **Step 2:** In the terminal, run `hermes config set model.default gpt-5.6-luna-900k`, then reopen Setup. Expected: the model line follows (Review Focus 1). Set it back afterwards.
- [ ] **Step 3:** Remove Telegram and confirm. Expected: "Not set up"; `.env` still has `API_SERVER_KEY`; the gateway restarts cleanly; re-add the token afterwards only if the user wants (they enter it).
- [ ] **Step 4:** Chat: an `rm -rf` approval shows the buttons Hermes offered; Allow for this chat → 200 in the access log.
- [ ] **Step 5:** Kill the gateway mid-turn twice (Start in between); after the second Start, a message is answered.
- [ ] **Step 6:** The keyboard opening in a chat keeps the newest message visible.
- [ ] **Step 7:** Key and token 0 times in logcat and in `files/hr/chats`; no crashes.
