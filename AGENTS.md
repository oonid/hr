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

Do not use `--recursive`: pr's other submodules (proot, musl, bionic, samba) are only
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
