module.exports = {
  updatedAt: "2026-11-16T14:38:00Z",
  activePlan: "tasks/Plans.md",
  currentTask: {
    id: "F-001",
    type: "enhancement",
    status: "done",
    summary: "Release Fortress 1.6.0 production hardening pass",
    files: [
      "app/src/main/AndroidManifest.xml",
      "app/src/main/cpp/fortress-native.cpp",
      "app/src/main/java/dev/fortress/",
      "tools/qa_smoke.py",
      "AGENTS.md"
    ],
    blockedBy: null,
    notes: "Phase 3 rollback ledger, VirusTotal hash lookup, realtime Download observer, task risk scoring, and release metadata complete. Main commit a4941e4f44f3594d33e2aa2b3e6b7f9bc8368303 passed both Android workflows, including strict release APK generation and artifact upload."
  },
  queue: [],
  done: [
    { id: "F-001", summary: "Release Fortress 1.6.0 production hardening and cyber Material UI", date: "2026-11-16", commit: "a4941e4f44f3594d33e2aa2b3e6b7f9bc8368303" }
  ]
};
