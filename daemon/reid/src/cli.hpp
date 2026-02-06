#pragma once

#include <functional>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace ksud {

/** Rei main CLI: daemon/Murasaki, flash/boot-info, set-root-impl, allowlist, version, help */
int reid_cli_run(int argc, char* argv[]);
/** KernelSU compat: ksud sub-CLI, full KSU command set */
int ksud_cli_run(int argc, char* argv[]);
/** Partition: boot-info (used by reid, same as ksud) */
int ksud_cmd_boot_info(const std::vector<std::string>& args);
/** Partition: flash (used by reid, same as ksud) */
int ksud_cmd_flash(const std::vector<std::string>& args);

/** Print version (reid/ksud shared) */
void print_version();

// Command handler type
using CommandHandler = std::function<int(const std::vector<std::string>&)>;

// CLI argument parser helpers
struct CliOption {
    std::string long_name;
    char short_name;
    std::string description;
    bool takes_value;
    std::string default_value;
};

class CliParser {
public:
    void add_option(const CliOption& opt);
    bool parse(int argc, char* argv[]);

    std::optional<std::string> get_option(const std::string& name) const;
    bool has_option(const std::string& name) const;
    const std::vector<std::string>& positional() const { return positional_args_; }
    const std::string& subcommand() const { return subcommand_; }

private:
    std::vector<CliOption> options_;
    std::map<std::string, std::string> parsed_options_;
    std::vector<std::string> positional_args_;
    std::string subcommand_;
};

}  // namespace ksud
