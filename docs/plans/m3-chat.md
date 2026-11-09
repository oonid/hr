# hr M3: In-App Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** chat with the phone's Hermes inside hr: a list of conversations, a streamed reply with tool status lines, Stop, approval prompts, and a way to start the gateway when it is not running.

**Architecture:** a new package `id.or.oo.hr.chat`. An SSE reader and an event mapper turn the gateway's Chat Completions stream into `ChatEvent`s; `ChatClient` sends one turn to `/p/android/v1/chat/completions` with `stream: true` and `X-Hermes-Session-Id`; `ConversationStore` keeps conversations as owner-only JSON files in hr's private storage; `ChatController` (app-scoped, so a turn survives leaving the screen) ties them together; `ChatScreen` is thin.

**Tech Stack:** as M2 (`docs/plans/m2-setup.md`): AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, Compose BOM 2026.09.00, pr `7029b1f`; `HttpURLConnection` and `org.json`, no new dependencies. Image `v0.23.0-p79f19a47-gateway` (Hermes 0.23.0).

**Spec:** `docs/design/hr-app.md` §5 (`ChatClient`), §9 (Chat), §11; the findings below.

## Findings this plan relies on

Read from Hermes 0.23.0 (`gateway/platforms/api_server*.py`) and measured on the phone (2026-11-09):

- `POST /p/android/v1/chat/completions` with `"stream": true` answers `200`, `Content-Type: text/event-stream`, and echoes `X-Hermes-Session-Id`. Frames are `data: <json>\n\n`, optionally preceded by `event: <name>\n`:
  - `chat.completion.chunk` with `choices[0].delta.role`, `delta.content` (text pieces), `delta.reasoning_content` (ignored by hr), and a last chunk with `finish_reason` (`stop`, or `length` etc. with a `hermes` object) and `usage`;
  - `event: hermes.tool.progress` with `{"tool", "emoji", "label", "toolCallId", "status": "running"}`, later `{"tool", "toolCallId", "status": "completed"}`;
  - `event: approval.request` with `{"event", "run_id", "command", "description", "choices": ["once", …, "deny"], …}` (sometimes `request_id`); the turn waits until `POST /p/android/v1/runs/{run_id}/approval` with `{"choice": "once" | "deny"}` (plus `request_id` when given);
  - `event: hermes.status` (ignored by hr); `: keepalive` comment lines every 10 s while the agent is quiet; finally `data: [DONE]`.
- Measured: a turn that ran `date +%Y` streamed its first frame at 0.2 s, tool running at 4.9 s, completed at 7.4 s, text at 9.1 s, `[DONE]` at 9.6 s.
- With `X-Hermes-Session-Id: <id>` the server keeps the conversation (history, including tool calls and results) in the profile's session store: a second turn that sent only the new message was answered from the first turn. Ids are at most 256 characters, no CR/LF/NUL, no path tricks. Without the header the server derives an id from the first user message and uses only the history in the body, which drops tool calls.
- When the client disconnects, the server interrupts the agent ("SSE client disconnected; interrupted agent task").

**Decision (deviates from §9's wording):** each conversation carries a session id (`hr-<uuid>`) sent as `X-Hermes-Session-Id`, and each turn sends only the new user message. hr still stores every conversation for display, as §9 says, but Hermes holds the context. Sending the stored text history instead would lose the tool calls and results between turns.

## Global Constraints

- hr's AGENTS.md applies: no comments; `vendor/` read only; JVM logic outside Activities and `HrApp` behind small interfaces with JVM tests; Android glue thin and checked on a phone; keys never reach logs, logcat or the UI; stage explicit paths; commits `feat|fix|chore|docs|test|build|ci: …`, body ≤ 72 columns, no trailers.
- Coverage: every new unit in `id.or.oo.hr.chat` keeps 80% of its lines or more.
- No device model numbers, marketing names or serials; adb commands use bare `adb`.
- Files hr writes go through `writePrivate` (owner-only, written then renamed).
- The API key is read from `ProfileStore.apiServerKey()` at send time and goes only into the `Authorization: Bearer` header; error bodies shown in the UI are redacted with `ProfileStore.secrets()` (as `TestRequest` does).
- No new library dependencies.

## Review Focus

1. A long tool run streams no text for minutes: keepalives arrive every 10 s, the read timeout (60 s) never fires, and the reply completes.
2. The user leaves the chat or the app mid-turn: the turn keeps running (app scope; the gateway's foreground service keeps hr alive), and on return the reply is complete and saved.
3. The gateway stops or restarts mid-turn (Stop, Setup's Test): the partial reply is kept and marked interrupted, a clear error shows, and the next message works.
4. Hermes asks for approval mid-turn: the command shows with Allow once and Deny; the answer is posted and the stream continues; Stop still works while waiting.
5. Text arrives split across frames, with multi-byte characters (emoji, non-Latin): it is shown and saved intact.

---

### Task 1: Read the stream

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/chat/Sse.kt`, `app/src/main/java/id/or/oo/hr/chat/ChatEvent.kt`
- Test: `app/src/test/java/id/or/oo/hr/chat/SseTest.kt`, `app/src/test/java/id/or/oo/hr/chat/ChatEventTest.kt`

**Interfaces:**
- Produces:
  - `data class SseEvent(val event: String?, val data: String)`; `fun readSse(lines: Iterator<String>, onEvent: (SseEvent) -> Unit)`: an empty line ends an event; `event:` sets its name; `data:` lines (one space after the colon stripped) join with `\n`; lines starting `:` and unknown fields are ignored; an event with no data is not delivered.
  - `sealed interface ChatEvent { data class Text(val delta: String); data class Tool(val callId: String, val label: String, val running: Boolean); data class Approval(val runId: String, val requestId: String?, val command: String, val description: String, val choices: List<String>); data class Finished(val reason: String); data object Done }`; `fun chatEvent(e: SseEvent): ChatEvent?` (null for frames hr ignores: role-only and reasoning deltas, `hermes.status`, unparseable JSON). For a completed tool frame, `label` is `""`; the controller keeps the label from the running frame. The `Tool` label for a running frame is `"<emoji> <label>"` when an emoji is present.

- [ ] **Step 1: Write the failing tests**, using the Findings' frames verbatim:
  - `framesSplitOnBlankLines`, `aNamedEventKeepsItsName`, `multiLineDataJoins`, `keepaliveCommentsAreIgnored`, `aTrailingEventWithoutABlankLineIsDelivered`.
  - `contentDeltasAreText`, `roleAndReasoningAreIgnored`, `toolRunningAndCompleted` (`Tool("call_1", "💻 date +%Y", true)` then `Tool("call_1", "", false)`), `anApprovalCarriesItsChoices`, `finishReasonIsFinished`, `doneIsDone`, `badJsonIsIgnored`, `multiByteTextSurvives` (a delta of `"héllo 👋"`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SseTest*' --tests '*ChatEventTest*'`. Expected: FAIL (types missing).
- [ ] **Step 3: Implement** both files (org.json, as `TestRequest`).
- [ ] **Step 4: Run** them. Expected: PASS; both files ≥ 80%.
- [ ] **Step 5: Commit** `feat: read the gateway's chat stream`.

---

### Task 2: Send a turn and answer an approval

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/chat/ChatClient.kt`
- Modify (tests only): `app/src/test/java/id/or/oo/hr/TestHttpServer.kt` (a handler that can write a streamed body piece by piece, with delays)
- Test: `app/src/test/java/id/or/oo/hr/chat/ChatClientTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `sealed interface TurnOutcome { data class Finished(val reason: String); data class Rejected(val code: Int, val body: String); data object NoGateway; data class Broken(val reason: String) }`.
  - `interface ChatTurns { suspend fun send(sessionId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome; suspend fun answer(runId: String, requestId: String?, choice: String): Boolean }` (the seam `ChatController` uses).
  - `class ChatClient(key: () -> String, secrets: () -> List<String>, base: String = BASE, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000) : ChatTurns` with
    - `suspend fun send(sessionId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome`: POST `$base/chat/completions`, headers `Authorization: Bearer <key>`, `Content-Type: application/json`, `Accept: text/event-stream`, `X-Hermes-Session-Id: <sessionId>`; body `{"model": "hermes-agent", "stream": true, "messages": [{"role": "user", "content": <text>}]}`; reads the body as UTF-8 lines through `readSse` + `chatEvent` on `Dispatchers.IO`; `Finished(reason)` when a finish frame arrived (the last one wins), even if `[DONE]` follows; a stream that ends without one is `Broken("the reply ended early")`; HTTP ≥ 400 → `Rejected(code, redacted body, first 500 chars)`; `ConnectException` → `NoGateway`; other `IOException` → `Broken(message)`. Cancelling the coroutine disconnects the connection (so the server interrupts the agent) and rethrows.
    - `suspend fun answer(runId: String, requestId: String?, choice: String): Boolean`: POST `$base/runs/<runId>/approval` with `{"choice": choice}` (+ `request_id`), true on 2xx.
  - `const val BASE = "http://127.0.0.1:8642/p/android/v1"`.

- [ ] **Step 1: Write the failing tests** against `TestHttpServer`: `aStreamedReplyArrivesAsEvents` (headers and body as above are what the server saw; events in order); `theSessionIdIsSent`; `aSlowTurnWithKeepalivesCompletes` (`readTimeoutMs = 300`, the server writes `: keepalive` every 100 ms for 1 s, then text and finish → `Finished("stop")`, Review Focus 1); `aStreamCutShortIsBroken` (Review Focus 3); `a401IsRejectedAndRedacted`; `noServerIsNoGateway`; `cancellingDisconnects` (the server sees its write fail after the cancel); `anApprovalAnswerIsPosted` (path, body, `request_id`); `textSplitAcrossWritesKeepsMultiByteCharacters` (the server splits `👋`'s UTF-8 bytes across two writes, Review Focus 5).
- [ ] **Step 2: Run** `--tests '*ChatClientTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Read with `BufferedReader(InputStreamReader(stream, UTF_8))`; cancellation through `suspendCancellableCoroutine` or `runInterruptible` with `connection.disconnect()` on cancel.
- [ ] **Step 4: Run.** Expected: PASS; `ChatClient.kt` ≥ 80%.
- [ ] **Step 5: Commit** `feat: stream a chat turn from the gateway`.

---

### Task 3: Keep conversations

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/chat/ConversationStore.kt`
- Modify: `app/src/main/java/id/or/oo/hr/HrFiles.kt` (`val chatsDir = File(hrDir, "chats")`)
- Test: `app/src/test/java/id/or/oo/hr/chat/ConversationStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class ChatMessage(val fromUser: Boolean, val text: String, val tools: List<String> = emptyList(), val interrupted: Boolean = false)`.
  - `data class Conversation(val id: String, val sessionId: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>)`.
  - `class ConversationStore(dir: File)` with `fun list(): List<Conversation>` (newest `updatedAt` first, messages omitted for speed: `messages = emptyList()`), `fun load(id: String): Conversation?`, `fun save(c: Conversation)` (`writePrivate` to `<dir>/<id>.json`), `fun delete(id: String)`.
  - `fun titleFor(firstMessage: String): String`: whitespace collapsed, at most 40 characters plus `…` when cut; `"New chat"` when blank.

- [ ] **Step 1: Write the failing tests:** `aSavedConversationLoadsBack` (all fields, multi-byte text); `theListIsNewestFirstWithoutMessages`; `aCorruptFileIsSkipped` (`list()` ignores it, `load()` returns null); `filesAreOwnerOnly`; `deleteRemovesTheFile`; `anIdWithPathCharactersIsRefused` (`save`/`load` with `../x` throw `IllegalArgumentException`; ids match `^[A-Za-z0-9-]{1,64}$`); `titlesAreShortened`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** with org.json.
- [ ] **Step 4: Run.** Expected: PASS; ≥ 80%.
- [ ] **Step 5: Commit** `feat: keep chat conversations in hr's files`.

---

### Task 4: The chat flow

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/chat/ChatController.kt`
- Test: `app/src/test/java/id/or/oo/hr/chat/ChatControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 2 and 3; `GatewayState` (`gateway/GatewayState.kt`).
- Produces:
  - `data class Turn(val text: String, val tools: List<ToolLine>, val approval: ChatEvent.Approval?, val answering: Boolean)` with `data class ToolLine(val callId: String, val label: String, val running: Boolean)`.
  - `data class ChatView(val conversation: Conversation, val turn: Turn?, val error: String?)`.
  - `class ChatController(store: ConversationStore, client: ChatTurns, gateway: StateFlow<GatewayState>, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, newId: () -> String = { UUID.randomUUID().toString() })` (`ChatTurns` from Task 2).
  - Members: `val conversations: StateFlow<List<Conversation>>`, `val view: StateFlow<ChatView?>`, `fun refresh()`, `fun newChat()`, `fun open(id: String)`, `fun close()`, `fun send(text: String)`, `fun stop()`, `fun answer(choice: String)`, `fun delete(id: String)`.
- Behaviour:
  - `newChat()` opens an unsaved conversation with id `newId()`, session id `"hr-" + newId()`, title `"New chat"`; it is saved on its first `send`.
  - `send(text)`: blank text or a running turn → nothing; gateway not `Running` → `view.error = "the gateway is not running"` and nothing sent; otherwise append the user message and save (title from the first message), start the turn in `scope` (one turn at a time across all conversations), fold events into `turn` (text appended, tool lines added on running and marked done on completed, approval set), and on the outcome append the assistant message (text, tool labels) and save:
    - `Finished("stop")` → saved, `error = null`;
    - `Finished(other)` → saved, `error = "the reply stopped early (<reason>)"`;
    - `Broken`/`NoGateway`/`Rejected` → partial text saved with `interrupted = true` (nothing saved when no text and no tools) and `error` set to the reason (`NoGateway` → `"the gateway is not running"`, `Rejected(401)` → `"the gateway refused hr's key (HTTP 401)"`, other codes `"the gateway refused the request (HTTP <code>): <body>"`).
  - `stop()` cancels the turn; partial text saved with `interrupted = true`, `error = null`.
  - `answer(choice)` posts it for the current approval and clears `approval` (keeps it with an error on failure).
  - `open`/`close`/`newChat` while a turn runs do not cancel it; the turn's conversation is saved when it ends; `view` shows the turn only for its own conversation.
  - `delete(id)` refuses (does nothing) for the conversation of a running turn.

- [ ] **Step 1: Write the failing tests** with a fake `ChatTurns` stepped by `CompletableDeferred`s and a real `ConversationStore` in a temp dir: `aTurnStreamsAndIsSaved`; `toolLinesFollowRunningAndCompleted`; `aGatewayThatIsNotRunningSendsNothing`; `stopKeepsThePartialReplyAsInterrupted` (Review Focus 3/4); `aBrokenTurnKeepsItsTextAndShowsTheError` (Review Focus 3); `a401SaysTheKeyWasRefused`; `theTurnSurvivesOpeningAnotherConversation` (Review Focus 2); `anApprovalIsAnsweredAndCleared` (Review Focus 4); `oneTurnAtATime`; `theFirstMessageNamesTheConversation`; `deletingTheRunningConversationIsRefused`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Same race discipline as `SetupController`: one `job` under a lock, results applied only when the job is still current.
- [ ] **Step 4: Run.** Expected: PASS; ≥ 80%.
- [ ] **Step 5: Commit** `feat: the chat flow from message to saved reply`.

---

### Task 5: The Chat screen

**Files:**
- Create: `app/src/main/java/id/or/oo/hr/ui/ChatScreen.kt`
- Modify: `app/src/main/java/id/or/oo/hr/ui/HrScreens.kt` (`Screen.Chat("Chat")`; Back from an open conversation returns to the list), `app/src/main/java/id/or/oo/hr/ui/HomeScreen.kt` (a Chat button first in the Terminal/Setup/Settings row, enabled when the image is installed), `app/src/main/java/id/or/oo/hr/HrApp.kt` (`ChatClient(profiles::apiServerKey, profiles::secrets)`, `ConversationStore(files.chatsDir)`, `val chat: ChatController` on `gatewayScope`)

The screen:

- **List** (no conversation open): "New chat" button; conversations by title and time (newest first); long-press → Delete with a confirm dialog.
- **Conversation:** messages top to bottom (the user's right-aligned, Hermes's left, selectable text; an interrupted reply ends with "(interrupted)"; tool labels in small text above a reply); while a turn runs: the streamed text, tool lines (`⏳`/`✓` + label), and Stop instead of Send; an approval card ("Hermes wants to run:" + command in monospace + description, buttons "Allow once" and "Deny"); the error line in the error colour; when the gateway is not `Running`, a banner "The gateway is not running." with Start (`actions.startGateway`) instead of the input. The input is a multi-line text field with Send, cleared after Send. Auto-scroll to the end while streaming.

- [ ] **Step 1: Build** `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all tests pass, no new warnings.
- [ ] **Step 2: Commit** `feat: the Chat screen`.

---

### Task 6: Phone check

**Files:** none. Results go in the pull request without device names, serials or keys.

- [ ] **Step 1:** Install; Home → Chat → New chat; send "Hi". Expected: text streams in within a few seconds; the conversation is listed with title "Hi".
- [ ] **Step 2:** Send "Run `date +%Y` in the terminal and tell me the year." Expected: a `💻 date +%Y` line goes from ⏳ to ✓, then the year. Then "What did I ask you to run?" Expected: the answer names `date +%Y` (server-side session).
- [ ] **Step 3:** Ask for something long (for example "Count slowly from 1 to 40 using the terminal, one `sleep 2` per number"); leave hr for a minute; return. Expected: the reply completed and is saved (Review Focus 1, 2).
- [ ] **Step 4:** Start another long turn and tap Stop. Expected: "(interrupted)" on the partial reply; the gateway log shows "SSE client disconnected; interrupted agent task"; the next message works.
- [ ] **Step 5:** Stop the gateway from Home mid-turn. Expected: partial reply marked interrupted, error shown; the banner offers Start; after Start a new message works (Review Focus 3).
- [ ] **Step 6:** Ask for a command that needs approval (for example "Delete the file /root/x with rm -rf"). Expected: the approval card; Deny → Hermes reports it was not allowed; again with Allow once → it runs (Review Focus 4). If no approval is requested, note the command used and mark the step skipped.
- [ ] **Step 7:** Send "Say 'héllo 👋 你好'". Expected: shown and, after reopening the conversation, saved intact (Review Focus 5).
- [ ] **Step 8:** `adb shell run-as id.or.oo.hr ls -l files/hr/chats/` → every file `-rw-------`; the API key 0 times in logcat (counted on the phone); `adb logcat -d -b crash` → nothing for `id.or.oo.hr`.

---

### Task 7: Follow-ups from the phone check

Found on the phone (2026-11-09) after Tasks 1–5 and the final review:

- When the gateway dies during a turn (killed, crashed, or Android stops it), Hermes 0.23.0 leaves that turn's admission in state `unknown` and pauses the session (`gateway.session_authority: Session … paused: unknown_execution`); every later turn on it ends with `finish_reason: "error"`. The session resumes after `POST /p/android/v1/runs/{run_id}/resolve-unknown` with `{"admission_id": …, "execution_generation": …}`, both read from `GET /p/android/v1/runs/{run_id}` (status `unknown`). For a Chat Completions turn sent with header `Idempotency-Key: <k>`, the run id is `chat:<sha256 hex of "android\u0000<API_SERVER_KEY>">:<k>` (measured: the `android` profile name matches; its full path and `default` give 404). A user's Stop does not pause the session.
- The list does not follow new content once it is not at the very bottom: after returning to hr, a new message, the streamed reply and the approval card stay below the visible area.
- When the gateway is killed mid-turn the error reads `EOFException`: the stream breaks before `GatewayController` reports the exit, and an `IOException` without a message shows its class name.
- User and Hermes messages share one bubble colour; long ones are hard to tell apart.

**Files:**
- Modify: `app/src/main/java/id/or/oo/hr/chat/ChatClient.kt`, `chat/ConversationStore.kt`, `chat/ChatController.kt`, `ui/ChatConversation.kt` (and `ui/ChatScreen.kt` if needed)
- Test: `ChatClientTest.kt`, `ConversationStoreTest.kt`, `ChatControllerTest.kt`

**Interfaces:**
- `ChatTurns.send(sessionId: String, turnId: String, text: String, onEvent: (ChatEvent) -> Unit): TurnOutcome` — `ChatClient` sends `Idempotency-Key: <turnId>`.
- `ChatTurns.resolveLost(turnId: String): Boolean` — `ChatClient`: run id as above (`Hermes` profile name `android`, key from `key()` at call time); `GET $base/runs/<run id>`; 404 or a status other than `unknown` → true; `unknown` → `POST …/resolve-unknown` with the two fields (org.json) → true on 2xx; HTTP errors → false; `ConnectException`/`IOException` → false; never throws except `CancellationException`; the key never appears in any outcome or log.
- `Conversation.pendingTurn: String? = null` (JSON `"pendingTurn"`, omitted when null; a non-string value makes the file malformed like other fields).
- `ChatController`:
  - each `send` makes `turnId = newId()`; the user message is saved with `pendingTurn = turnId`;
  - the turn's end clears `pendingTurn` on `Finished(…)` and on a user Stop, and keeps it on `Broken`, `NoGateway` and `Rejected`;
  - before sending, inside the turn job, when the conversation's previous `pendingTurn` is set, it calls `resolveLost(previous)` first. It sends either way, and the new user message's save already replaces `pendingTurn` with the new turn id.
- `ChatController` errors:
  - when a turn ends `Broken` or `NoGateway`, it waits up to 3 s for `gateway` to leave `Running`; if it does, the error is `"the gateway stopped during the reply"`;
  - `ChatClient` maps an `IOException` with a blank message to `Broken("the connection to the gateway was lost")`.
- Chat view scrolling: a `follow` state, true when a conversation opens and after each Send. The user's own drag sets it to `!list.canScrollForward` (only while `isScrollInProgress` from a user gesture). While `follow` is true, any change in message count, streamed text length, tool line count or approval presence scrolls to `(lastIndex, Int.MAX_VALUE)`.
- Bubbles: the user's messages right-aligned in `primaryContainer`/`onPrimaryContainer`; Hermes's left-aligned in `surfaceVariant`/`onSurfaceVariant`; both at most 85% of the width.

- [ ] **Step 1: Write the failing tests:**
  - `ChatClientTest`: `theTurnIdIsSentAsIdempotencyKey`; `aLostTurnIsResolved` (GET at the exact run id, then the POST body); `aTurnThatIsNotUnknownIsLeftAlone`; `aMissingRunCountsAsResolved` (404); `noGatewayMeansNotResolved`; `aBlankIOExceptionSaysTheConnectionWasLost`.
  - `ConversationStoreTest`: `pendingTurnRoundTrips`, `aNonStringPendingTurnIsMalformed`.
  - `ChatControllerTest`: `aBrokenTurnLeavesItsTurnPending`; `aFinishedTurnClearsIt`; `stopClearsIt`; `theNextSendResolvesTheLostTurnFirst` (order: resolveLost(old) → send(new)); `aGatewayThatStopsSoonAfterSaysSo` (gateway turns StoppedUnexpectedly 1 s after the Broken outcome → the gateway-stopped text).
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the full suite and the coverage report. Expected: PASS; `id.or.oo.hr.chat` files ≥ 80%.
- [ ] **Step 5: Commit** `fix: recover a chat after the gateway dies mid-reply` (and `fix: keep the chat scrolled to new content` for the UI part).
- [ ] **Step 6: Phone:** kill the gateway mid-turn, Start, send again → answered (no `finish_reason: error`); the error line reads "the gateway stopped during the reply"; after leaving and reopening a chat, a new message and its reply stay in view; scrolling up during a long reply stays put.
