#include "murasaki_dispatch.hpp"
#include "defs.hpp"
#include "utils.hpp"

#include <algorithm>
#include <sstream>
#include <sys/wait.h>
#include <unistd.h>

namespace ksud {

// Sui-style declaration: permission API* or meta V3_SUPPORT / io.murasaki.client.SUPPORT
// dumpsys package output contains "requested permissions:" and "metaData:" with these strings
#define MURASAKI_GREP_PATTERN "moe\\.shizuku|io\\.murasaki"

std::vector<std::string> get_packages_declaring_murasaki_shizuku(
    const std::vector<std::string>* candidate_packages) {
    std::vector<std::string> result;
    std::string list_path = std::string(REI_DIR) + "/.murasaki_scan_list";
    std::string out_path = std::string(REI_DIR) + "/.murasaki_scan_out";

    std::string list_content;
    if (candidate_packages && !candidate_packages->empty()) {
        for (const auto& p : *candidate_packages) {
            if (!p.empty()) list_content += p + '\n';
        }
    } else {
        // pm list packages -> one package per line (package:name)
        int pipefd[2];
        if (pipe(pipefd) != 0) return result;
        pid_t pid = fork();
        if (pid < 0) {
            close(pipefd[0]);
            close(pipefd[1]);
            return result;
        }
        if (pid == 0) {
            close(pipefd[0]);
            dup2(pipefd[1], STDOUT_FILENO);
            close(pipefd[1]);
            execlp("pm", "pm", "list", "packages", nullptr);
            _exit(127);
        }
        close(pipefd[1]);
        char buf[256];
        std::ostringstream oss;
        while (ssize_t n = read(pipefd[0], buf, sizeof(buf))) {
            if (n <= 0) break;
            oss.write(buf, static_cast<size_t>(n));
        }
        close(pipefd[0]);
        waitpid(pid, nullptr, 0);
        std::string line;
        std::istringstream iss(oss.str());
        while (std::getline(iss, line)) {
            size_t i = line.find("package:");
            if (i != std::string::npos) {
                std::string pkg = line.substr(i + 8);
                while (!pkg.empty() && (pkg.back() == '\r' || pkg.back() == '\n')) pkg.pop_back();
                if (!pkg.empty()) list_content += pkg + '\n';
            }
        }
    }

    if (list_content.empty()) return result;
    if (!write_file(list_path, list_content)) return result;

    // For each package: dumpsys package $pkg | grep -qE 'moe\.shizuku|io\.murasaki' && echo $pkg
    const char* script =
        "pf=\"$1\"; of=\"$2\"; "
        "while read -r p; do "
        "  [ -z \"$p\" ] && continue; "
        "  dumpsys package \"$p\" 2>/dev/null | grep -qE \"" MURASAKI_GREP_PATTERN "\" && echo \"$p\"; "
        "done < \"$pf\" > \"$of\"";
    pid_t pid = fork();
    if (pid == 0) {
        execlp("sh", "sh", "-c", script, "sh", list_path.c_str(), out_path.c_str(), nullptr);
        _exit(127);
    }
    if (pid > 0) {
        waitpid(pid, nullptr, 0);
        auto content = read_file(out_path);
        unlink(list_path.c_str());
        unlink(out_path.c_str());
        if (content) {
            std::istringstream iss(*content);
            std::string line;
            while (std::getline(iss, line)) {
                while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) line.pop_back();
                if (!line.empty()) result.push_back(line);
            }
        }
    }
    return result;
}

std::optional<std::string> dispatch_shizuku_binder_and_get_owner(
    const std::vector<AllowlistEntry>& entries,
    std::optional<uint32_t> manager_uid) {
    std::vector<std::string> packages;
    std::string manager_pkg;
    for (const auto& e : entries) {
        if (manager_uid && static_cast<uint32_t>(e.first) == *manager_uid) {
            manager_pkg = e.second;
        }
        if (std::find(packages.begin(), packages.end(), e.second) == packages.end()) {
            packages.push_back(e.second);
        }
    }
    if (packages.empty()) return std::nullopt;

    std::vector<std::string> declared = get_packages_declaring_murasaki_shizuku(&packages);
    if (declared.empty()) return std::nullopt;

    if (!manager_pkg.empty() &&
        std::find(declared.begin(), declared.end(), manager_pkg) != declared.end()) {
        declared.erase(std::remove(declared.begin(), declared.end(), manager_pkg), declared.end());
        declared.insert(declared.begin(), manager_pkg);
    }

    std::string pkgs_path = std::string(REI_DIR) + "/.shizuku_dispatch_pkgs";
    std::string owner_path = std::string(REI_DIR) + "/.shizuku_dispatch_owner";
    std::ostringstream oss;
    for (const auto& p : declared) oss << p << '\n';
    if (!write_file(pkgs_path, oss.str())) return std::nullopt;

    const char* run_script =
        "pf=\"$1\"; of=\"$2\"; "
        "while read -r p; do "
        "  [ -z \"$p\" ] && continue; "
        "  path=$(pm path \"$p\" 2>/dev/null | cut -d: -f2); "
        "  [ -z \"$path\" ] || [ ! -f \"$path\" ] && continue; "
        "  cls=\"${p}.ui.shizuku.BinderDispatcher\"; "
        "  if CLASSPATH=\"$path\" app_process /system/bin \"$cls\" 2>/dev/null; then "
        "    echo \"$p\" > \"$of\"; break; "
        "  fi; "
        "done < \"$pf\"";
    pid_t pid = fork();
    if (pid == 0) {
        execlp("sh", "sh", "-c", run_script, "sh", pkgs_path.c_str(), owner_path.c_str(), nullptr);
        _exit(0);
    }
    std::optional<std::string> owner;
    if (pid > 0) {
        waitpid(pid, nullptr, 0);
        auto content = read_file(owner_path);
        if (content) {
            std::string pkg = trim(*content);
            if (!pkg.empty()) owner = pkg;
        }
        unlink(pkgs_path.c_str());
        unlink(owner_path.c_str());
    }
    return owner;
}

}  // namespace ksud
