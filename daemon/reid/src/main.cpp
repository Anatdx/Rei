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

static bool is_apd_invocation(const std::string& path) {
    if (path.empty()) return false;
    size_t last = path.rfind('/');
    std::string base = (last != std::string::npos) ? path.substr(last + 1) : path;
    return base == "apd";
}

int main(int argc, char* argv[]) {
    std::string exe = get_self_path();
    if (!exe.empty() && is_apd_invocation(exe)) {
        apd::InitLog();
        return apd::RunCli(argc, argv);
    }
    return ksud::cli_run(argc, argv);
}
