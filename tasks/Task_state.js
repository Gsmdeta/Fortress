module.exports = {
  updatedAt: "2026-11-16T14:12:00Z",
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
    notes: "Phase 3 rollback ledger, VirusTotal hash lookup, realtime Download observer, task risk scoring, and release metadata complete. Main commit 637303dde2a37d56444676df295e2cbc065fc195 passed both Android workflows."
  },
  queue: [],
  done: [
    { id: "F-001", summary: "Release Fortress 1.6.0 production hardening and cyber Material UI", date: "2026-11-16", commit: "637303dde2a37d56444676df295e2cbc065fc195" }
  ]
};
