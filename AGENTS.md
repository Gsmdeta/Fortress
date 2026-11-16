# Fortress project instructions

## Project overview

- **Type:** Android application (Kotlin + Jetpack Compose + JNI)
- **Package:** `dev.fortress.console`
- **Backend:** none; on-device Android services and root shell only
- **Database:** file-backed JSON in `Context.filesDir`
- **Auth:** local PIN gate; privileged actions require explicit user action and root/Android consent
- **Build:** Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17
- **Current release:** versionName 1.6.0 / versionCode 16
- **Live source root:** repository root

## Source layout

- `app/src/main/java/dev/fortress/` — production Kotlin
- `app/src/main/cpp/` — read-only JNI heuristics
- `app/src/main/assets/` — bundled CVE catalog
- `app/src/main/res/` — manifest/resources/icons
- `tasks/` — plan, state, and bug log; update before and after non-trivial work
- `tools/` — deterministic smoke and validation helpers
- `.github/workflows/` — CI build and QA gates

## Safety boundary

Fortress is defensive tooling for devices the operator owns. Root actions may inspect state, install/remove the Fortress Magisk module, apply explicit DROP-only firewall rules, stop a named app, or apply a documented local hardening command. Never add memory writes, kernel patching, traffic redirection, credential collection, stealth, persistence outside the declared guard opt-in, or data upload without explicit consent.

## Change workflow

1. Read `tasks/Plans.md`, current git diff, and the affected implementation before editing.
2. Keep privileged work behind explicit UI actions; make rootless behavior honest and usable.
3. Update `tasks/Task_state.js` and append `tasks/Debug_Log.md` for each fixed bug.
4. Prefer small, reviewable changes. Do not commit or push without explicit user approval.
5. Run `python3 tools/qa_smoke.py`, `git diff --check`, and JSON validation locally. Run Gradle tests/lint/build in GitHub Actions when the sandbox lacks the Android toolchain.
6. For a release candidate, verify both `android` and `Build FORTRESS APK` workflows on the exact merge commit and record the artifact SHA-256 in `tasks/Debug_Log.md`.

## Kotlin / Android rules

- Use Kotlin coroutines/Flow for long-running work; cancel services and collectors in lifecycle teardown.
- Do not perform blocking shell or file I/O on the main thread.
- Treat JNI as optional: unsupported ABI or native load failure must degrade to a visible limited-mode result, never crash the console.
- Every `VpnService` must use `android.permission.BIND_VPN_SERVICE`, call `prepare()` before start, close its descriptor, and prevent duplicate readers.
- Android 14 foreground services must declare and pass the matching type; keep the manifest subtype property.
- Root shell commands must be bounded, newline/NUL guarded, sentinel-safe, and limited to defensive operations.
- Compose controls need stable text labels/content descriptions and layouts that fit 320dp-wide phones.
- Keep the CVE catalog honest: `softwarePatchAvailable=true` only when a concrete local mitigation exists.
- Every software mitigation that changes device state must record an undo command before reporting success; UI must expose rollback when a ledger entry exists.
- VirusTotal remains optional and hash-first; never upload an artifact automatically. Realtime file observation is advisory until the bounded artifact scanner analyzes the file.

## Delivery

GitHub Actions is the source of truth for `test`, `lintDebug`, and `assembleDebug`; a green local static smoke suite is required before requesting review. The user decides when the reviewed changes are pushed to GitHub.
