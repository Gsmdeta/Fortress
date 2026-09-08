// fortress-native.cpp — pure DETECTION heuristics for the Fortress scanner.
//
// ETHICAL SCOPE: this library only READS kernel-visible state and reports
// anomalies. It never patches memory, never unloads modules, never disables
// protections. All findings are surfaced to the Kotlin layer for human review.
//
// Read access notes (WHY the heuristics degrade gracefully):
//  * /proc/kallsyms shows 0000000000000000 for symbol addresses unless the
//    caller is root or kptr_restrict is relaxed. Fortress therefore runs the
//    deep scan through the RootShell path when available; without root we
//    still parse symbol NAMES (visible) and report a degraded-confidence scan.
//  * Android 10+ (and some OEM kernels earlier) hide /proc/<other-pid> for
//    non-shell UIDs, so hidden-thread checks only cover processes we can read.

#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <cstdlib>
#include <cstdio>
#include <dirent.h>

namespace {

// Small buffered file reader; returns "" when the path is unreadable
// (perm denied / not mounted) — callers treat empty as "cannot assess".
std::string readAll(const std::string &path) {
    std::ifstream in(path);
    if (!in.is_open()) return "";
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

bool parseHex(const std::string &hex, unsigned long long &out) {
    if (hex.empty()) return false;
    char *end = nullptr;
    out = std::strtoull(hex.c_str(), &end, 16);
    return end != hex.c_str();
}

struct Sym { unsigned long long addr; std::string name; };

// Pull "addr type name" triples out of a kallsyms dump.
std::vector<Sym> parseKallsyms(const std::string &text) {
    std::vector<Sym> out;
    std::istringstream ss(text);
    std::string line;
    while (std::getline(ss, line)) {
        unsigned long long addr = 0;
        if (line.size() < 18) continue;
        if (!parseHex(line.substr(0, 16), addr)) continue;
        size_t sp = line.find(' ', 17);
        if (sp == std::string::npos) continue;
        // name = everything after the second space (type column)
        size_t nameStart = line.find_first_not_of(' ', sp + 1);
        if (nameStart == std::string::npos) continue;
        out.push_back({addr, line.substr(nameStart)});
    }
    return out;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// scanSyscallTable: sanity-check the syscall table against kernel text bounds.
//
// Strategy (detection only):
//  1. Parse /proc/kallsyms to locate `sys_call_table` plus the _stext/_etext
//     kernel text range markers.
//  2. Cross-reference /proc/self/maps strings for anonymous r-x mappings
//     (rootkit trampolines often map RWX pages outside any file backing).
//  3. Report counts; the Kotlin layer renders the verdict. We never write.
//
// Returns a small report string, one finding per line, prefixed "!" when the
// observation is suspicious, "-" when the datum was unreadable.
//
// NOTE on the second parameter: the Kotlin declarations are `external fun` on
// the RootkitHeuristics OBJECT (a singleton), so JNI dispatches them as
// instance methods — the second arg is jobject, not jclass.
JNIEXPORT jstring JNICALL
Java_dev_fortress_scanner_RootkitHeuristics_scanSyscallTable(JNIEnv *env, jobject /*thiz*/) {
    std::string report;
    const std::string ksym = readAll("/proc/kallsyms");
    if (ksym.empty()) {
        report += "-kallsyms unreadable (no root or kptr_restrict=2)\n";
    } else {
        std::vector<Sym> syms = parseKallsyms(ksym);
        unsigned long long stext = 0, etext = 0, sct = 0;
        for (const Sym &s : syms) {
            if (s.name == "_stext") stext = s.addr;
            else if (s.name == "_etext") etext = s.addr;
            else if (s.name == "sys_call_table") sct = s.addr;
        }
        if (sct == 0 || stext == 0 || etext == 0) {
            report += "-sys_call_table/_stext/_etext not resolved\n";
        } else if (sct < stext || sct > etext) {
            report += "!sys_call_table outside kernel text range (patched map?)\n";
        } else {
            report += "+sys_call_table inside kernel text range\n";
        }
    }

    // /proc/self/maps: flag RWX anonymous pages (classic trampoline signature).
    const std::string maps = readAll("/proc/self/maps");
    int rwxAnon = 0;
    std::istringstream mss(maps);
    std::string mline;
    while (std::getline(mss, mline)) {
        bool anon = mline.find('/') == std::string::npos;
        if (anon && mline.find("rwx") != std::string::npos) rwxAnon++;
    }
    if (!maps.empty()) {
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%s%d anonymous RWX page(s) in self maps\n",
                      rwxAnon > 0 ? "!" : "+", rwxAnon);
        report += buf;
    } else {
        report += "-/proc/self/maps unreadable\n";
    }
    return env->NewStringUTF(report.c_str());
}

// ---------------------------------------------------------------------------
// checkInlineHooks: compare syscall handler pointers against the text range.
//
// An inline-hooked syscall usually gets redirected into a module or an
// allocated page — i.e. OUTSIDE [ _stext, _etext ]. We count outliers for a
// fixed watchlist of high-value syscalls. Read-only comparison, no patching.
JNIEXPORT jint JNICALL
Java_dev_fortress_scanner_RootkitHeuristics_checkInlineHooks(JNIEnv *env, jobject /*thiz*/) {
    static const char *kWatch[] = {
        "sys_read", "sys_write", "sys_openat",
        "sys_execve", "sys_clone", "sys_kill", "sys_ptrace",
    };
    const std::string ksym = readAll("/proc/kallsyms");
    if (ksym.empty()) return -1; // caller degrades to userspace-only heuristics

    std::vector<Sym> syms = parseKallsyms(ksym);
    unsigned long long stext = 0, etext = 0;
    int outliers = 0;
    for (const Sym &s : syms) {
        if (s.name == "_stext") stext = s.addr;
        else if (s.name == "_etext") etext = s.addr;
    }
    if (stext == 0 || etext == 0 || etext <= stext) return -1;

    for (const char *want : kWatch) {
        for (const Sym &s : syms) {
            if (s.name == want) {
                if (s.addr < stext || s.addr > etext) outliers++;
                break;
            }
        }
    }
    return outliers;
}

// ---------------------------------------------------------------------------
// detectHiddenThreads: walk /proc/<pid>/task and compare the directory count
// with the `Threads:` field of /proc/<pid>/status. Rootkits that unlink task
// directories to hide activity leave the two counts disagreeing.
//
// Returns the number of processes whose counts disagree (0 == clean), or -1
// when /proc is not traversable for this caller at all.
JNIEXPORT jint JNICALL
Java_dev_fortress_scanner_RootkitHeuristics_detectHiddenThreads(JNIEnv *env, jobject /*thiz*/) {
    DIR *proc = opendir("/proc");
    if (proc == nullptr) return -1;

    int mismatched = 0;
    struct dirent *pe;
    while ((pe = readdir(proc)) != nullptr) {
        if (pe->d_name[0] < '0' || pe->d_name[0] > '9') continue;
        std::string pid = pe->d_name;

        int dirCount = 0;
        std::string taskDir = "/proc/" + pid + "/task";
        DIR *tasks = opendir(taskDir.c_str());
        if (tasks == nullptr) continue; // hidden from us — skip, don't guess
        struct dirent *te;
        while ((te = readdir(tasks)) != nullptr) {
            if (te->d_name[0] >= '0' && te->d_name[0] <= '9') dirCount++;
        }
        closedir(tasks);

        const std::string status = readAll("/proc/" + pid + "/status");
        size_t p = status.find("Threads:");
        if (p == std::string::npos) continue;
        int stated = std::atoi(status.c_str() + p + 8);
        if (stated > 0 && dirCount > 0 && stated != dirCount) mismatched++;
    }
    closedir(proc);
    return mismatched;
}

} // extern "C"
