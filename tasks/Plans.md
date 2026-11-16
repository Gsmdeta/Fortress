# Fortress hardening and completion plan

## Goal
Turn the current Kotlin source delivery into a trustworthy, rooted Android lab security console with honest capability reporting, stable Compose UI, defensive-only privileged actions, and repeatable CI smoke checks.

## Scope

1. **Runtime reliability**
   - Make JNI loading fail soft on unsupported ABIs.
   - Ensure root mode actually reads privileged `/proc` and `/data/adb` state.
   - Make scanner cancellation, persistent sessions, and service lifecycles deterministic.
2. **Security correctness**
   - Correct the native syscall-table bounds model.
   - Guard shell input, sentinel parsing, and stale package rules.
   - Keep privileged actions explicit and defensive; never add redirect/exfiltration behavior.
3. **Network and guard behavior**
   - Correct VPN service binding/lifecycle and prevent duplicate tap readers.
   - Make the root task path and rooted module monitoring real.
   - Keep the VPN capture-mode connectivity caveat visible in the UI.
4. **CVE and root workflows**
   - Remove false local-patch claims and require root before mitigation actions.
   - Preserve install/reboot flows without silently claiming success.
5. **UI and accessibility**
   - Initialize scan state at the shell level so restored tabs work.
   - Improve narrow-phone layouts, overflow handling, labels, and runtime notification permission.
6. **QA / delivery**
   - Add a source-level smoke suite that runs before Gradle build in GitHub Actions.
   - Update README and project instructions with the real architecture and validation commands.
   - Produce dashboard and shield concept previews for review; do not push until the user approves.

## Rollback
All edits remain on the local working tree until explicit approval. Revert the working tree or the review branch to the current `main` commit `ef801e3` if the user rejects the batch.

## Validation
- `python3 tools/qa_smoke.py`
- `python3 -m json.tool app/src/main/assets/cve-db.json`
- `git diff --check`
- `./gradlew test lintDebug assembleDebug --stacktrace --no-daemon` in GitHub Actions (the current sandbox cannot download Gradle from services.gradle.org).
