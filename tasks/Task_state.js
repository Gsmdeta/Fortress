module.exports = {
  updatedAt: "2026-11-16T03:55:00Z",
  activePlan: "tasks/Plans.md",
  currentTask: {
    id: "F-001",
    type: "enhancement",
    status: "done",
    summary: "Complete the Fortress rooted Android security console hardening pass",
    files: [
      "app/src/main/AndroidManifest.xml",
      "app/src/main/cpp/fortress-native.cpp",
      "app/src/main/java/dev/fortress/",
      "tools/qa_smoke.py",
      "AGENTS.md"
    ],
    blockedBy: null,
    notes: "Hardening and Material 3 cyber UI pass complete. GitHub Actions run 43800463607 passed source smoke, test, lintDebug, assembleDebug, native build, and APK packaging."
  },
  queue: [],
  done: [
    { id: "F-001", summary: "Complete rooted security console and cyber Material UI", date: "2026-11-16", commit: "35deca41527ce3c3878dba1982ed8a08d953d80a" }
  ]
};
