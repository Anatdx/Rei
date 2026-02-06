#include "cli.hpp"
#include "apd_entry.hpp"

#include <cstring>
#include <limits.h>
#include <string>
#include <unistd.h>

// Single binary + hardlinks: reid/apd/ksud same inode, dispatch by argv[0] basename. Switch APatch/KernelSU needs reboot.
// When invoked as su (kernel su_path), use /proc/self/exe to choose apd vs ksud and avoid reid "Unknown command".
int main(int argc, char* argv[]) {
    (void)chdir("/");

    std::string arg0 = argv[0] ? argv[0] : "";
    size_t last = arg0.rfind('/');
    std::string basename = (last != std::string::npos) ? arg0.substr(last + 1) : arg0;

    if (basename == "apd") {
        return apd::RunCli(argc, argv);
    }
    if (basename == "ksud") {
        return ksud::ksud_cli_run(argc, argv);
    }
    if (basename == "su") {
        char self[PATH_MAX];
        ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
        if (n > 0) {
            self[n] = '\0';
            std::string exe(self);
            size_t exe_last = exe.rfind('/');
            std::string exe_base = (exe_last != std::string::npos) ? exe.substr(exe_last + 1) : exe;
            if (exe_base == "apd") {
                return apd::RunCli(argc, argv);
            }
        }
        return ksud::ksud_cli_run(argc, argv);
    }
    return ksud::reid_cli_run(argc, argv);
}
