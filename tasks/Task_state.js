module.exports = {
  updatedAt: "2026-11-16T06:32:00Z",
  activePlan: "tasks/Plans.md",
  currentTask: {
    id: "F-001",
    type: "enhancement",
    status: "review",
    summary: "Complete the Fortress rooted Android security console hardening pass",
    files: [
      "app/src/main/AndroidManifest.xml",
      "app/src/main/cpp/fortress-native.cpp",
      "app/src/main/java/dev/fortress/",
      "tools/qa_smoke.py",
      "AGENTS.md"
    ],
    blockedBy: null,
    notes: "Hardening pass implemented locally; source smoke, JSON, Python syntax, and diff checks pass. Gradle validation is queued for GitHub Actions because services.gradle.org is unreachable from this sandbox. GitHub push waits for user approval."
  },
  queue: [
    { id: "F-003", summary: "Wait for user approval before any remote write" }
  ],
  done: []
};
