# hr M3.2: Redaction and Small Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** no secret hr knows reaches the chat screen or a saved chat, including the ChatGPT login's tokens and secrets split across streamed chunks; plus three small fixes left from M2.

**Architecture:** `ProfileStore.secrets()` grows to include the token values in the profile's `auth.json`; `ChatController` redacts the streamed reply on the whole accumulated text (so a secret split across chunks is still caught) and redacts loaded conversations when it shows them; the M2 fixes are local edits.

**Tech Stack:** as M3.1 (`docs/plans/m3-1-polish.md`); no new dependencies.

**Spec:** `docs/design/hr-app.md` §11 ("Keys never appear in logs or the UI"); AGENTS.md; the follow-ups recorded after PR #7.

## Findings this plan relies on

- Hermes 0.23.0 (`agent/credential_pool.py`, `hermes_cli/auth.py`) keeps login secrets in `auth.json` under the keys `access_token`, `refresh_token`, `agent_key` and, for pooled keys, `api_key` — in `providers.<id>`, in `credential_pool.<id>[]` entries, and sometimes nested under `tokens`. Other fields (`expires_at`, ids, URLs) are not secret.
- Redaction today: `redact(text, secrets)` (longest first, values shorter than 8 ignored) covers error bodies, tool labels and the approval card; `ProfileStore.secrets()` returns the API server key plus profile `.env` values named `*_KEY`, `*_TOKEN`, `*_PASSWORD`, `*_SECRET`, cached by the `.env` file's stamp. The gateway log (`LogBuffer`) uses the same `secrets()`.
- The streamed reply (`ChatEvent.Text` deltas) is appended raw in `ChatController.fold` and saved raw; a secret can arrive split over two deltas.
- Chats saved before M3.1 may hold unredacted tool labels.
- M2 leftovers: `TestRequest` shows `e.message` of an `IOException` unredacted (`setup/TestRequest.kt:36-37`); a dead custom endpoint ends a Test with just "timeout" (a `SocketTimeoutException` message), and a chat turn with "Read timed out"; `CodexLogin`'s `LIMIT_MS` is a public top-level constant (`setup/CodexLogin.kt:15`).

## Global Constraints

- hr's AGENTS.md applies: no comments; `vendor/` read only; JVM logic outside Activities and `HrApp` with JVM tests; keys never reach logs, logcat or the UI; stage explicit paths; commits `feat|fix|chore|docs|test|build|ci: …`, body ≤ 72 columns, no trailers.
- Coverage: every changed unit keeps 80% of its lines or more.
- No device identifiers; adb commands use bare `adb`.
- Test data uses dummy secrets only (≥ 8 characters).

## Review Focus

1. A secret split across two streamed deltas never appears on screen, not even for one frame, and is not saved.
2. A refreshed ChatGPT token (Hermes rewrites `auth.json`) is redacted from then on, without restarting hr.
3. A reply that legitimately contains a short word equal to part of a secret is not mangled (the 8-character minimum and whole-value matching hold).
4. Opening a chat saved before M3.1 shows its tool labels redacted, without rewriting the file.
5. A dead custom endpoint gives a message that says what to check, in both Test and chat.

---

### Task 1: The login's tokens are secrets

**Files:**
- Modify: `app/src/main/java/id/or/oo/hr/profile/ProfileStore.kt`
- Test: `app/src/test/java/id/or/oo/hr/profile/ProfileStoreTest.kt`

**Interfaces:**
- `ProfileStore.secrets()` also returns every string value of a key named `access_token`, `refresh_token`, `agent_key` or `api_key` found anywhere in the profile's `auth.json` (a recursive walk over objects and arrays), cached by `auth.json`'s stamp (lastModified + length) the same way `.env` is; a missing or unparseable `auth.json` contributes nothing and never throws.

- [ ] **Step 1: Write the failing tests:** `loginTokensAreSecrets` (an `auth.json` shaped like the Findings with dummy values under `providers.openai-codex.tokens.access_token`, `credential_pool.openai-codex[0].refresh_token`, `…agent_key`, `…api_key` → all four returned; `expires_at` and an `id` are not); `aRewrittenAuthFileIsPickedUp` (Review Focus 2); `aCorruptAuthFileAddsNothing`; the existing `.env` secrets tests unchanged.
- [ ] **Step 2–4:** Run (FAIL), implement (org.json), run (PASS; ProfileStore.kt ≥ 80%).
- [ ] **Step 5: Commit** `fix: treat the ChatGPT login's tokens as secrets`.

---

### Task 2: Redact the reply and old chats

**Files:**
- Modify: `app/src/main/java/id/or/oo/hr/chat/ChatController.kt`, `app/src/main/java/id/or/oo/hr/HrApp.kt`
- Test: `app/src/test/java/id/or/oo/hr/chat/ChatControllerTest.kt`

**Interfaces:**
- `ChatController` takes `secrets: () -> List<String>` (HrApp passes `profiles::secrets`).
- The running turn keeps the raw accumulated text privately; the `Turn.text` it publishes is `redact(raw, secrets())` computed on the whole text at every fold, so a secret split across deltas is redacted once complete. Saving the reply uses the same redacted text.
- Any trailing part of the raw text that could be the start of a secret (the last up to `longest secret − 1` characters matching a secret's prefix) is held back from the published text until the next delta or the end of the turn, so a partial secret is never shown (Review Focus 1). At the end of the turn everything is flushed (redacted).
- When a conversation is shown (`open`, and the view after each save), its messages' `text` and `tools` are redacted for display; the file is not rewritten (Review Focus 4).

- [ ] **Step 1: Write the failing tests:** `aSecretSplitAcrossDeltasIsNeverShown` (deltas `"key is hrsecret"` + `"12345 done"` with secret `hrsecret12345`; assert no published `Turn.text` ever contains `hrsecret` and the saved reply reads `key is [redacted] done`); `aPrefixThatIsNotASecretIsReleased` (deltas ending `"hrsec"` then `"tion"` → `hrsection` shown once complete); `aShortWordIsNotMangled` (Review Focus 3); `anOldChatIsShownRedacted` (a file with an unredacted tool label → the view shows `[redacted]`, the file is unchanged); the existing tests updated for the new constructor parameter.
- [ ] **Step 2–4:** Run (FAIL), implement, run (PASS; ChatController.kt ≥ 80%).
- [ ] **Step 5: Commit** `fix: redact the reply and saved chats on screen`.

---

### Task 3: M2 leftovers

**Files:**
- Modify: `setup/TestRequest.kt`, `chat/ChatClient.kt`, `setup/CodexLogin.kt`
- Test: `TestRequestTest.kt`, `ChatClientTest.kt`, `CodexLoginTest.kt`

**Interfaces and texts:**
- `TestRequest`: an `IOException` message goes through `redact(…, secrets())`; a `SocketTimeoutException` gives `Failed("the gateway did not answer in time; the model endpoint may be unreachable — check the provider's base URL and key")`.
- `ChatClient`: a `SocketTimeoutException` ends the turn with `Broken("Hermes did not answer in time; the model endpoint may be unreachable", lost = false)`.
- `CodexLogin`: `LIMIT_MS` becomes `private const val`.

- [ ] **Step 1: Write the failing tests:** `aTimeoutSaysWhatToCheck` (TestRequest, a server that never answers within a short read timeout), `anIOExceptionMessageIsRedacted`, `aChatTimeoutSaysWhatToCheck` (ChatClient).
- [ ] **Step 2–4:** Run (FAIL), implement, run (PASS).
- [ ] **Step 5: Commit** `fix: clearer timeouts and redacted Test errors`.

---

### Task 4: Phone check

**Files:** none. Results go in the pull request without device names, serials, IPs or keys.

- [ ] **Step 1:** Ask Hermes to "Repeat exactly: my key is <the dummy HR_PROBE_TOKEN value>" after adding a dummy `HR_PROBE_TOKEN` line to the profile `.env` (removed afterwards). Expected: the reply shows `[redacted]` while streaming and when the chat is reopened; the value appears 0 times in `files/hr/chats` and logcat (counted on the phone).
- [ ] **Step 2:** The ChatGPT access token (read on the phone only) appears 0 times in hr's gateway log view dump, logcat and `files/hr/chats`.
- [ ] **Step 3:** Open a chat saved before M3.1: tool labels show redacted where they contained a secret (or note that none did).
- [ ] **Step 4:** Point the OpenAI-compatible provider at an unreachable endpoint only if it can be restored to ChatGPT afterwards; otherwise mark skipped (the JVM tests cover the text).
- [ ] **Step 5:** No crashes; key 0 times in logcat.
