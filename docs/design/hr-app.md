# hr: Hermes Agent on Android

**Date:** 2026-10-22
**Status:** design approved; not implemented

## 1. Goal

An Android app, `id.or.oo.hr`, that turns a phone into a Hermes Agent server: it installs a
prebuilt Hermes image, keeps the Hermes gateway running in the background, sets up a model
provider (including a ChatGPT login) and optionally Telegram, and offers an in-app chat with the
local Hermes. It runs Hermes in an Alpine container under PRoot (no root, no VM), on the engine of
the generic app oonid/pr.

## 2. Context

- **The image:** `ghcr.io/oonid/hermes-agent`, built from upstream Hermes releases by the
  `android` branch of oonid/hermes-agent. The `gateway` variant downloads 255 MB, takes 748 MB on
  the phone, installs through pr-cli in 48 s and launches in 2–3 s. Its contract:
  - user data lives in the profile `HERMES_HOME=/root/.hermes/profiles/android`;
  - the gateway runs once per container, started from the root home
    (`HERMES_HOME=/root/.hermes hermes gateway run`);
  - its listener settings (`API_SERVER_ENABLED`, `API_SERVER_KEY`) are read only from
    `/root/.hermes/.env`;
  - the API for the profile is `:8642/p/android/v1`, authenticated with the `API_SERVER_KEY` in
    the profile's `.env`;
  - after SIGTERM the gateway exits with code 1 on purpose.
- **Upstream Hermes on Android:** there is no official app. A native Termux package exists but is
  marked "currently broken" and best-effort; upstream `install.sh` refuses Termux. Updating Hermes
  in place on a phone is fragile (`hermes update` builds Rust and gets killed), which is why hr
  updates by installing a newer image.
- **oonid/pr:** its `:proot-engine` (proot, pr-cli, busybox, PTY) and `:termlib` (terminal) are
  generic modules, already reused by rs.oo.or.id. pr-cli can bind-mount host directories
  (`login --custom-bind host:guest`) and run a command (`login <alias> -- '<cmd>'`). Its
  background-session code (sessions, foreground service, End and drain, notifications, `/proc`
  scanning) is generic but lives in pr's `:app`.

## 3. Decisions

| Topic | Decision |
|-------|----------|
| Scope | Gateway server plus in-app chat |
| Repository | A new repository for hr (local `hr.oo.or.id`), app id `id.or.oo.hr`; pr's modules included through a `vendor/pr` git submodule with a `../pr.oo.or.id` fallback, as in rs.oo.or.id |
| Session code | Extracted first in oonid/pr into a generic module `:session-core`, then included by hr |
| Image | The `gateway` variant; installed at first run, its exact build tag recorded |
| Updates | hr checks GHCR and offers an update; the user taps Update; the old image is kept until the new one runs |
| Chat | The gateway's OpenAI-compatible Chat Completions API with streaming |
| Setup | A native form for common providers, ChatGPT login by device code, optional Telegram, and a terminal running `hermes setup` for anything else |
| Approach | A native Compose app on pr's engine (not a WebView over Hermes's dashboard) |

## 4. Milestones

Each milestone has its own plan and pull request, and ends with a check on a phone.

| # | Repository | Delivers |
|---|------------|----------|
| M0 | oonid/pr | `:session-core` extracted from pr's `:app` with no behaviour change, plus a session that runs a given command with extra bind mounts |
| M1 | hr | Install the image; start, stop and End the gateway in the background; status (health, uptime, version); logs; a terminal into the container; the profile kept in hr's own files |
| M2 | hr | First-run setup: providers, ChatGPT login, optional Telegram, test request, `hermes setup` fallback |
| M3 | hr | In-app chat |
| M4 | hr | Image updates with rollback |

M1 and M2 together give Hermes on the phone around the clock, reached through Telegram or the API.
M3 adds chat without a messaging platform. M0 gets a short design of its own in the pr repository,
since it changes pr's code.

## 5. Architecture

A Kotlin/Compose app, arm64-v8a, minSdk 28, using `:proot-engine`, `:termlib` and `:session-core`.

| Unit | Responsibility |
|------|----------------|
| `ImageStore` | Install, list and remove Hermes images through pr-cli; keep the current image's alias and build tag |
| `GatewayController` | Start, stop and watch the gateway session; poll `/health`; expose its state |
| `ProfileStore` | Read and write the profile's `config.yaml` and `.env` in hr's files |
| `ProviderSetup` | The setup form's logic; the ChatGPT device-code login; the test request |
| `ChatClient` | Streaming Chat Completions over HTTP, including tool-progress events |
| UI | Home (status, Start/Stop), Setup, Chat, Logs, Terminal, Settings |

## 6. Files and Data

- **Container:** alias `hermes` (later aliases per build for updates, §10), inside hr's private
  files. hr's data never mixes with pr's, so both apps can be installed on one phone.
- **Profile:** `files/hr/profile` in hr's private storage, bind-mounted to
  `/root/.hermes/profiles/android` in every session. Config, keys, the ChatGPT login (`auth.json`),
  sessions and memories therefore survive image updates and removal. Because the directory is a
  host directory, hr writes `config.yaml` and `.env` directly, without a shell.
- **Listener settings:** hr generates the root `API_SERVER_KEY` once, keeps it in private storage,
  and writes `/root/.hermes/.env` in the container before every start. The listener binds to
  `127.0.0.1` only.
- **Permissions:** internet, network state, foreground service (`specialUse`), wake lock,
  notifications, and the battery-optimisation exemption request. No shared-storage permission.

## 7. Gateway Lifecycle

- **Start:** a headless session runs `HERMES_HOME=/root/.hermes hermes gateway run` with the
  profile bound, under the foreground service with its notification and the "Keep CPU awake"
  option. The state is *Starting* until `/health` answers (25–30 s on the phone measured so far),
  then *Running*.
- **Before Start:** if something already answers on port 8642 (for example a gateway started in
  oonid/pr), hr says so and does not start a second one.
- **Stop:** End sends SIGTERM and waits for the drain, as in pr. Exit code 1 after End counts as a
  clean stop.
- **Unexpected exit:** the state becomes *Stopped unexpectedly*, with the last log lines and a
  Restart button. v1 has no automatic restart loop. If Android killed the app, opening it shows
  *Stopped*.
- **Not in v1:** starting at boot (Android limits foreground-service starts at boot).

## 8. Setup (M2)

- **Providers in the form:** OpenRouter, OpenAI (API key), Anthropic, Nous, an OpenAI-compatible
  endpoint (base URL, key, model), and ChatGPT login.
- **ChatGPT login:** hr runs `hermes auth add openai-codex` in the container (device code, Hermes's
  default flow), shows the verification URL as a button and the code ready to copy, and waits for
  success. The login is stored in the profile's `auth.json`.
- **Telegram:** an optional bot token.
- **Test:** after saving, a one-word request through the gateway confirms the provider works.
- **Advanced:** a terminal running `hermes setup`, for every other provider or platform Hermes
  supports.

## 9. Chat (M3)

- hr stores the conversations and sends the history with each turn to
  `/p/android/v1/chat/completions`, streaming.
- `hermes.tool.progress` events show as status lines while a turn runs.
- Chat needs the gateway; when it is not running, the screen offers to start it.

## 10. Updates (M4)

- hr reads the build tag behind `:gateway` from GHCR when the app opens (at most daily) or when the
  user taps Check, and shows "Update available" for a newer build.
- **Update:**
  1. Install the new build beside the current one, under its own alias.
  2. Stop the gateway and switch the current image.
  3. Start the gateway on the same bind-mounted profile.
  4. Keep the old image until the new one is *Running*, then offer to remove it.
- If the new image does not reach *Running*, hr switches back to the old one.

## 11. Errors and Testing

- Every pr-cli and gateway failure is shown with its text. Keys never appear in logs or the UI.
- **JVM tests:** writing `config.yaml` and `.env`; parsing the device-code output; health and state
  transitions; the chat stream parser; the update pointer and rollback.
- **Phone checks** at the end of each milestone: install, start, the API and chat, End, and an
  update.

## 12. Out of Scope for v1

Starting at boot, an automatic restart loop, more messaging platforms in the form, Hermes's
desktop protocol (`/api/ws`), images other than `gateway`, other ABIs, and Play Store publishing.
