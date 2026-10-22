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
