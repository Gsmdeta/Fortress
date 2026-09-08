# Fortress — Android Console (Kotlin source delivery)

Defensive root security scanner for rooted Android lab devices, paired with the
Fortress web command center (`dev.fortress.console` APK). The tree ships a
Gradle wrapper (Gradle 8.10.2) and a GitHub Actions workflow, so it builds
out of the box — locally or on CI — with no Android Studio required.

- **Package:** `dev.fortress.console` · versionCode `12` · versionName `1.2.0`
- **SDK targets:** minSdk 26 (Android 8.0) · compileSdk/targetSdk 35
- **Language stack:** Kotlin 2.0 · AGP 8.7 · coroutines/Flow · NDK (C++17) for native heuristics
- **ABIs:** `arm64-v8a`, `armeabi-v7a` (root heuristics are meaningless on x86 emulator images)

---

## 0. Build with GitHub Actions (recommended)

The easiest way to get an APK: build it on GitHub's servers — no local SDK,
NDK, or Android Studio needed.

1. Create a **new GitHub repository**.
2. Copy the **CONTENTS of this `fortress-android-src/` folder** into the repo
   root — including the hidden **`.github/`** folder (that's where the CI
   workflow, `.github/workflows/android.yml`, lives). The root of the repo must
   contain `settings.gradle.kts`, `app/`, `gradlew` and `.github/` side by side.
3. Push (to `main` or `master`) and open **Actions** — the `android` workflow
   builds automatically. You can also trigger it manually via
   **Actions → android → Run workflow**.
4. When the run finishes, download the APK from the run's **Artifacts**
   section: it is published as **`fortress-debug-apk`** (kept 30 days).

The workflow uses non-deprecated actions (`actions/checkout@v5`,
`actions/setup-java@v5` with Temurin 17, `gradle/actions/setup-gradle@v5`,
`actions/upload-artifact@v4`), accepts the Android SDK licenses, installs the
pinned toolchain (`ndk;27.0.12077973`, `cmake;3.22.1`), and runs
`./gradlew assembleDebug --stacktrace --no-daemon`.

## 1. Local build

1. Install **JDK 17** (Temurin) and the **Android SDK (platform 35)** plus
   **NDK 27.0.12077973** and **CMake 3.22.1** — either via Android Studio
   (Ladybug 2024.2+, SDK Manager → SDK Tools) or the standalone `sdkmanager`:

   ```bash
   yes | sdkmanager --licenses
   sdkmanager --install "platforms;android-35" "build-tools;35.0.0" \
                "ndk;27.0.12077973" "cmake;3.22.1"
   ```

2. Build from this directory:

   ```bash
   ./gradlew assembleDebug      # Gradle wrapper (8.10.2) is shipped in this tree
   ```

3. Install on the device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

> **Gradle wrapper note:** `gradlew` (+x), `gradlew.bat` and
> `gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}` are part of
> this tree now — no `gradle wrapper` bootstrap step is needed.

## 2. Feature matrix

| Console tab | Class(es) | Root required? | Notes |
| --- | --- | --- | --- |
| Dashboard | `ConsoleActivity.DashboardFragment`, `ui.ProjectionView` | no | posture curve w/ tap + DPAD inspection, a11y readouts |
| Scanner | `scanner.DeepScanner`, `scanner.RootkitHeuristics`, `ui.ScannerFragment`, `cpp/fortress-native.cpp` | degrades without | 4 phases: /proc walk → memory maps → file audit → native kernel heuristics |
| Network | `net.PacketVpnService`, `net.TrafficMonitor` | attribution improves with | VpnService TUN tap (10.111.0.2/32, MTU 32768), per-app attribution, verdict feed |
| Shield | `firewall.FirewallManager`, `guard.RealtimeGuard`, `tasks.TaskManager` | root path optional | per-app wifi/data rules (VPN drop + iptables owner-match), module/package/clipboard watch, force-stop |
| CVE | `cve.CveRepository` + `assets/cve-db.json` | yes, for mitigation shell cmds | 8 bundled CVEs mirrored from the web console; local mitigations are sysctl/chmod/chcon only |
| Forensics | scan report export | no | markdown mirror of the web `sessionToMarkdown()` |

**Optional:** `vt.VirusTotalClient` — hash-first VirusTotal lookup; upload only
with explicit user consent, API key stored in EncryptedSharedPreferences,
hard rate limit 4 req/min.

## 3. Root requirements & detection model

- Root is **optional** but unlocks: full `/proc` visibility (Android 10+ hides
  other apps' procfs), `/data/adb` reads, iptables enforcement, `am force-stop`,
  CVE mitigation commands, Magisk module install.
- Root check: `su -c id` (`MainActivity`). Magisk detection: package scan +
  guarded `/data/adb/magisk` existence probe (SecurityException == normal on
  non-rooted devices).
- All privileged operations flow through `root.RootShell` — a single `su`
  session, command-serialized, sentinel-marked, timeout-guarded. The shell is
  used for **read-only inspection and defensive rules only**; nothing in the
  tree spawns offensive tooling.
- Native heuristics (`fortress-native.cpp`) are pure detection: kallsyms
  syscall-table bounds check, inline-hook pointer comparison, task-dir/status
  thread-count mismatch walk. No memory writes, no module unloading, ever.

## 4. Magisk module packaging note

`root.MagiskInstaller` ships the APK as a **systemless system app**:

```
fortress_console/
  module.prop                    # id, name, version v1.2.0 (versionCode 12)
  post-fs-data.sh                # no-op placeholder hook
  system/app/Fortress/Fortress.apk
```

`magisk --install-module fortress_console.zip` stages it; the overlay applies
after **reboot** (`svc power reboot` through RootShell, or manual). Uninstall =
remove the module in the Magisk app + reboot. Non-root fallback uses a standard
`PackageInstaller` session (user-confirmed).

## 5. Permission walkthrough

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | VirusTotal lookups, network posture |
| `POST_NOTIFICATIONS` | runtime-granted (API 33+) — scan/guard/tap notifications |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | `RealtimeGuard` + `PacketVpnService` run as specialUse FGS (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declared in manifest) |
| `RECEIVE_BOOT_COMPLETED` | `guard.BootReceiver` re-arms the guard only on user opt-in (`guard_auto_start` pref) |
| `QUERY_ALL_PACKAGES` | per-app traffic attribution + task list; Play-policy restricted, acceptable for sideload/lab distribution |
| `REQUEST_INSTALL_PACKAGES` | PackageInstaller fallback in `MagiskInstaller` |
| `android.permission="android.service.VpnService"` (service) | system-held gate so only the framework binds `PacketVpnService` |

**VPN behavior caveat:** the tap classifies and *drops* (firewall verdicts) but
does not re-inject allowed traffic — there is intentionally no userspace TCP
stack in this reference build. While the tap is up, connectivity pauses by
design; use the iptables path (root) for always-on enforcement. This is stated
in code (`PacketVpnService` header) so nobody mistakes it for a bug.

## 6. Layout of the tree

```
app/src/main/
  cpp/                    CMakeLists.txt · fortress-native.cpp (JNI heuristics)
  java/dev/fortress/      MainActivity · ConsoleActivity (+ 5 tab stubs)
    root/                 RootShell · MagiskInstaller
    scanner/              DeepScanner · RootkitHeuristics
    net/                  PacketVpnService · TrafficMonitor
    vt/                   VirusTotalClient
    guard/                RealtimeGuard (+ BootReceiver)
    cve/                  CveRepository
    firewall/             FirewallManager
    tasks/                TaskManager
    ui/                   ProjectionView · ScannerFragment
  res/                    values (strings · colors · FortressTheme) + adaptive launcher icons (drawable/mipmap)
  assets/cve-db.json      8-entry catalog mirroring the web CVE_CATALOG
```

No layout XML by design — every screen is programmatic Kotlin, keeping the
delivery diff-friendly.

## 7. Disclaimer

Fortress is **defensive tooling for devices you own**: it detects, logs,
and hardens. It does not patch binaries, write kernel memory, unload modules,
intercept or redirect traffic, or exfiltrate data. Distribution beyond your own
lab (Play Store or otherwise) requires re-review of `QUERY_ALL_PACKAGES` and
VPN disclosures. Use responsibly and in line with your local laws.
