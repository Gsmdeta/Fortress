#!/usr/bin/env python3
"""Fast, dependency-free contract checks for the Fortress source tree.

This is intentionally separate from Gradle: it catches security regressions
before an Android toolchain is downloaded and gives GitHub Actions a cheap
fail-fast gate for manifest, JNI, root-shell, and CVE invariants.
"""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def main() -> None:
    manifest = read("app/src/main/AndroidManifest.xml")
    root_shell = read("app/src/main/java/dev/fortress/root/RootShell.kt")
    heuristics = read("app/src/main/java/dev/fortress/scanner/RootkitHeuristics.kt")
    native = read("app/src/main/cpp/fortress-native.cpp")
    vpn = read("app/src/main/java/dev/fortress/net/PacketVpnService.kt")
    scanner = read("app/src/main/java/dev/fortress/scanner/DeepScanner.kt")
    shield = read("app/src/main/java/dev/fortress/ui/ShieldTab.kt")
    console_activity = read("app/src/main/java/dev/fortress/ConsoleActivity.kt")
    console_theme = read("app/src/main/java/dev/fortress/ui/ConsoleTheme.kt")
    settings = read("app/src/main/java/dev/fortress/ui/SettingsTab.kt")
    artifact_scanner = read("app/src/main/java/dev/fortress/scanner/ArtifactScanner.kt")
    posture = read("app/src/main/java/dev/fortress/security/DevicePosture.kt")
    dashboard = read("app/src/main/java/dev/fortress/ui/DashboardTab.kt")
    quarantine = read("app/src/main/java/dev/fortress/scanner/QuarantineManager.kt")
    cve_repository = read("app/src/main/java/dev/fortress/cve/CveRepository.kt")
    cv = json.loads(read("app/src/main/assets/cve-db.json"))

    require('android.permission.BIND_VPN_SERVICE' in manifest, "VPN service must use BIND_VPN_SERVICE")
    require('android.service.VpnService' not in manifest, "legacy VPN permission string remains")
    require('System.loadLibrary("fortress-native")' in heuristics, "JNI library load path missing")
    require('getOrDefault(false)' in heuristics, "JNI load must fail soft")
    require('native heuristics unavailable for this ABI' in heuristics, "limited ABI result missing")
    require('_sdata' in native and '_srodata' in native, "syscall-table data bounds missing")
    require('sys_call_table outside kernel text range' not in native, "syscall-table heuristic still reports text bounds")
    require('commandSequence.incrementAndGet()' in root_shell, "root-shell sentinel is not unique")
    require("it == '\\u0000'" in root_shell and "it == '\\n'" in root_shell, "root-shell newline/NUL guard missing")
    require('tapJob?.cancel()' in vpn and 'FileInputStream(fd.fileDescriptor).use' in vpn, "VPN lifecycle guard missing")
    require('RootShell.exec("ls -1 /proc' in scanner, "root scanner still uses app-only process listing")
    require('snapshotRooted()' in shield, "Shield UI does not use the rooted task path")
    require('ConsoleNavigationBar' in console_activity and 'ConsoleNavigationRail' in console_activity, "responsive Material navigation missing")
    require('TopAppBar' in console_theme and 'NavigationBar' in console_theme and 'NavigationRail' in console_theme, "standard Android shell components missing")
    require('Button(' in console_theme and 'OutlinedButton(' in console_theme, "action controls are not Material buttons")
    require('heightIn(min = 48.dp)' in console_theme, "interactive controls must meet the 48dp touch target")
    require('EXTRA_ROOT_PROVIDER' in read("app/src/main/java/dev/fortress/MainActivity.kt"), "root provider detection missing")
    require('KernelSU' in read("app/src/main/java/dev/fortress/ConsoleRuntime.kt"), "KernelSU provider missing")
    require('exportModule' in read("app/src/main/java/dev/fortress/root/MagiskInstaller.kt"), "module export missing")
    require('deep artifact sweep' in settings.lower() and 'SettingsTab' in read("app/src/main/java/dev/fortress/ConsoleActivity.kt"), "settings screen missing")
    require('MAX_FILES' in artifact_scanner and 'sha256' in artifact_scanner, "bounded artifact hashing missing")
    require('lookupByHash' in artifact_scanner and 'VirusTotalClient' in artifact_scanner, "VirusTotal hash lookup is not wired into artifacts")
    require('artifact_limit' in settings and 'include_system_paths' in settings, "scan scope controls missing")
    require('DevicePosture.collect' in dashboard and 'DEVICE POSTURE' in dashboard, "live posture UI missing")
    require('ro.boot.verifiedbootstate' in posture and 'SECURITY_PATCH' in posture, "device integrity checks missing")
    require('allowed' in quarantine and 'chmod 000' in quarantine and 'automatic quarantine' in settings.lower(), "explicit quarantine boundary missing")
    require('rollbackSoftwarePatch' in cve_repository and 'fortress_patch_ledger' in cve_repository, "CVE rollback ledger missing")

    # Every locally actionable catalog entry must have a concrete mitigation
    # branch. This prevents a UI button that can only report "no mitigation".
    cve_source = read("app/src/main/java/dev/fortress/cve/CveRepository.kt")
    mitigation_ids = set(re.findall(r'"(CVE-[0-9-]+)"\s*->\s*listOf', cve_source))
    for entry in cv:
        if entry["softwarePatchAvailable"] and not entry["patched"]:
            require(entry["id"] in mitigation_ids, f"{entry['id']} advertises a missing local mitigation")

    # Defensive shell boundary: reject obvious network redirection or destructive
    # filesystem commands if they are accidentally introduced into RootShell calls.
    forbidden = ("iptables -t nat", "REDIRECT", "DNAT", "TPROXY", "curl ", "wget ", "nc ", "mkfs")
    for token in forbidden:
        require(token not in root_shell and token not in cve_source, f"forbidden privileged operation: {token}")

    print("PASS: Fortress source smoke contracts")


if __name__ == "__main__":
    main()
