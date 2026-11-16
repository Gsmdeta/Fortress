# Fortress debug log

| Date | Bug / risk | Root cause | Fix | Files |
| --- | --- | --- | --- | --- |
| 2026-11-16 | Initial audit | Project had no project-specific task/state surface; current instructions described an unrelated web app | Added Android-specific plan and state files; no code changes yet | `tasks/Plans.md`, `tasks/Task_state.js` |

| 2026-11-16 | Native scanner could crash on unsupported ABI | `System.loadLibrary` ran during singleton init | Lazy fail-soft JNI loading with visible limited-mode output | `app/src/main/java/dev/fortress/scanner/RootkitHeuristics.kt` |
| 2026-11-16 | False critical syscall-table findings | Data/rodata symbol was compared against `_stext`/`_etext` | Compare against kernel data/rodata bounds and degrade when symbols are hidden | `app/src/main/cpp/fortress-native.cpp` |
| 2026-11-16 | Rooted scan and shield paths were app-only | `/proc` and `/data/adb` reads used the app UID; rooted task path was never selected | Use bounded `su` listings, rooted task snapshots, and module polling | `DeepScanner.kt`, `ShieldTab.kt`, `RealtimeGuard.kt` |
| 2026-11-16 | VPN/service and install lifecycle defects | Wrong VPN binding permission, duplicate tap readers, leaked TUN stream, activity PendingIntent for install result | Correct manifest permission, guard jobs, close descriptors, and add a status receiver | `AndroidManifest.xml`, `PacketVpnService.kt`, `MagiskInstaller.kt` |
| 2026-11-16 | Narrow-phone action overflow and false CVE action | Three dashboard buttons forced into a narrow row; Zygote CVE advertised a missing mitigation | Responsive action layout, root-gated mitigation controls, honest catalog metadata | `DashboardTab.kt`, `CveTab.kt`, `cve-db.json` |
| 2026-11-16 | Console shell did not follow standard Android navigation patterns | Custom tab strip/rail and hand-built clickable buttons lacked Material semantics | Added Material 3 top app bar, bottom navigation, navigation rail, semantic destinations, and 48dp Button/OutlinedButton controls while retaining the cyber palette | `ConsoleActivity.kt`, `ConsoleTheme.kt`, `README.md` |

| 2026-11-16 | CI toolchain and compile validation | Android SDK action requested removed `tools` package; UI compile surfaced API and expression-body issues; lint rejected undocumented package visibility | Upgraded setup-android to v4, fixed Kotlin/Material API usage, added manifest lint justification, and reran successfully | `.github/workflows/*.yml`, `MainActivity.kt`, `ConsoleTheme.kt`, `AndroidManifest.xml`, `VirusTotalClient.kt` |
