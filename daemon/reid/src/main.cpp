#include "cli.hpp"
#include "apd_entry.hpp"

#include <limits.h>
#include <string>
#include <unistd.h>

#if defined(__linux__)
static std::string get_self_path() {
    char buf[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return {};
    buf[n] = '\0';
    return buf;
}
#else
static std::string get_self_path() {
    return {};
}
#endif

static std::string get_exe_basename(const std::string& path) {
    if (path.empty()) return {};
    size_t last = path.rfind('/');
    return (last != std::string::npos) ? path.substr(last + 1) : path;
}

int main(int argc, char* argv[]) {
    // Avoid cwd being /data/adb/ksud when invoked via su -c from App; prevents temp files (e.g. apd_*) in ksud dir
    (void)chdir("/");

    // Use argv[0] so that when reid is a symlink to ksud, we still run reid_cli_run (allowlist etc.)
    std::string invoked_as = (argc > 0 && argv[0]) ? argv[0] : get_self_path();
    std::string base = get_exe_basename(invoked_as);

    if (base == "apd") {
        apd::InitLog();
        return apd::RunCli(argc, argv);
    }
    if (base == "reid") {
        return ksud::reid_cli_run(argc, argv);
    }
    return ksud::ksud_cli_run(argc, argv);
}
