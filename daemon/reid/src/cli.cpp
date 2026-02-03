#include "cli.hpp"
#include "defs.hpp"
#include "init_event.hpp"
#include "log.hpp"
#include "utils.hpp"

#include <unistd.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <vector>

namespace ksud {

void CliParser::add_option(const CliOption& opt) {
    options_.push_back(opt);
}

bool CliParser::parse(int argc, char* argv[]) {
    for (int i = 1; i < argc; i++) {
        std::string arg = argv[i];

        if (arg.empty())
            continue;

        // Check if it's an option
        if (arg[0] == '-') {
            bool found = false;
            std::string opt_name;
            std::string opt_value;

            // Long option
            if (arg.size() > 1 && arg[1] == '-') {
                std::string long_opt = arg.substr(2);
                size_t eq_pos = long_opt.find('=');
                if (eq_pos != std::string::npos) {
                    opt_name = long_opt.substr(0, eq_pos);
                    opt_value = long_opt.substr(eq_pos + 1);
                } else {
                    opt_name = long_opt;
                }

                for (const auto& opt : options_) {
                    if (opt.long_name == opt_name) {
                        found = true;
                        if (opt.takes_value && opt_value.empty() && i + 1 < argc) {
                            opt_value = argv[++i];
                        }
                        parsed_options_[opt_name] = opt_value.empty() ? "true" : opt_value;
                        break;
                    }
                }
            }
            // Short option
            else {
                char short_opt = arg[1];
                for (const auto& opt : options_) {
                    if (opt.short_name == short_opt) {
                        found = true;
                        opt_name = opt.long_name;
                        if (opt.takes_value && i + 1 < argc) {
                            opt_value = argv[++i];
                        }
                        parsed_options_[opt_name] = opt_value.empty() ? "true" : opt_value;
                        break;
                    }
                }
            }

            if (!found) {
                LOGE("Unknown option: %s", arg.c_str());
            }
        }
        // Positional argument
        else {
            if (subcommand_.empty()) {
                subcommand_ = arg;
            } else {
                positional_args_.push_back(arg);
            }
        }
    }

    return true;
}

std::optional<std::string> CliParser::get_option(const std::string& name) const {
    auto it = parsed_options_.find(name);
    if (it != parsed_options_.end()) {
        return it->second;
    }

    // Return default value if exists
    for (const auto& opt : options_) {
        if (opt.long_name == name && !opt.default_value.empty()) {
            return opt.default_value;
        }
    }

    return std::nullopt;
}

bool CliParser::has_option(const std::string& name) const {
    return parsed_options_.find(name) != parsed_options_.end();
}

static void print_reid_usage() {
    printf("Rei userspace daemon (reid 本体)\n\n");
    printf("USAGE: reid <COMMAND>\n\n");
    printf("COMMANDS:\n");
    printf("  daemon              Run as daemon (Binder service, Murasaki)\n");
    printf("  set-root-impl <ksu|apatch>  Root 实现二选一：ksu 仅创建 ksud，apatch 仅创建 apd\n");
    printf("  version             Show version\n");
    printf("  help                Show this help\n");
}

void print_version() {
    printf("ksud version %s (code: %s)\n", VERSION_NAME, VERSION_CODE);
}

int reid_cli_run(int argc, char* argv[]) {
    log_init("Rei");

    if (argc < 2) {
        print_reid_usage();
        return 0;
    }

    std::string cmd = argv[1];
    std::vector<std::string> args;
    for (int i = 2; i < argc; i++) {
        args.push_back(argv[i]);
    }

    LOGI("reid command: %s", cmd.c_str());

    if (cmd == "help" || cmd == "-h" || cmd == "--help") {
        print_reid_usage();
        return 0;
    }
    if (cmd == "version" || cmd == "-v" || cmd == "--version") {
        print_version();
        return 0;
    }
    if (cmd == "daemon") {
        return run_daemon();
    }
    if (cmd == "set-root-impl") {
        if (args.size() < 1) {
            printf("USAGE: reid set-root-impl <ksu|apatch>\n");
            return 1;
        }
        const std::string& impl = args[0];
        if (impl != "ksu" && impl != "apatch") {
            printf("Invalid root impl: %s (use ksu or apatch)\n", impl.c_str());
            return 1;
        }
        return set_root_impl(impl);
    }

    printf("Unknown command: %s\n", cmd.c_str());
    print_reid_usage();
    return 1;
}


}  // namespace ksud
