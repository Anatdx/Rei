#include "cli.hpp"

#include "apd.hpp"
#include "defs.hpp"
#include "event.hpp"
#include "log.hpp"
#include "module.hpp"
#include "sepolicy.hpp"
#include "supercall.hpp"
#include "../defs.hpp"
#include "../utils.hpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace apd {

namespace {
bool EndsWith(const std::string& value, const std::string& suffix) {
  if (value.size() < suffix.size()) {
    return false;
  }
  return value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string Basename(const std::string& path) {
  size_t pos = path.find_last_of('/');
  if (pos == std::string::npos) {
    return path;
  }
  return path.substr(pos + 1);
}

void PrintUsage() {
  const char* usage =
      "Usage:\n"
      "  apd [--superkey KEY] <command>\n"
      "Commands:\n"
      "  module <install|uninstall|enable|disable|action|lua|list>\n"
      "  post-fs-data\n"
      "  services\n"
      "  boot-completed\n"
      "  uid-listener\n"
      "  sepolicy check <policy>\n"
      "  allowlist get | grant <uid> <pkg> | revoke <uid>\n";
  std::fprintf(stdout, "%s", usage);
}

}  // namespace

int RunCli(int argc, char** argv) {
  if (argc <= 0 || argv == nullptr) {
    return 1;
  }

  std::string arg0 = argv[0] ? argv[0] : "";
  if (EndsWith(arg0, "kp") || EndsWith(arg0, "su")) {
    return RootShell(argc, argv);
  }

  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg == "-h" || arg == "--help") {
      PrintUsage();
      return 0;
    }
    if (arg == "-V" || arg == "--version") {
      std::string name = Basename(argv[0] ? argv[0] : "apd");
      std::fprintf(stdout, "%s %s\n", name.c_str(), kVersionCode);
      return 0;
    }
  }

  std::string superkey;
  std::vector<std::string> args;
  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if ((arg == "-s" || arg == "--superkey") && i + 1 < argc) {
      superkey = argv[++i];
      continue;
    }
    args.push_back(arg);
  }
  // KernelPatch backend: ALL operations require superkey (CLI or Rei unified file)
  if (superkey.empty()) {
    auto key_opt = ksud::read_file(ksud::REI_SUPERKEY_PATH);
    if (key_opt)
      superkey = ksud::trim(*key_opt);
  }

  if (args.empty()) {
    PrintUsage();
    return 1;
  }

  if (superkey.empty()) {
    LOGE("KernelPatch backend requires superkey for all operations. Set -s/--superkey or configure %s", ksud::REI_SUPERKEY_PATH);
    return 1;
  }

  PrivilegeApdProfile(superkey);

  const std::string& cmd = args[0];
  bool ok = false;

  if (cmd == "post-fs-data") {
    ok = OnPostDataFs(superkey);
  } else if (cmd == "services") {
    ok = OnServices(superkey);
  } else if (cmd == "boot-completed") {
    ok = OnBootCompleted(superkey);
  } else if (cmd == "uid-listener") {
    ok = StartUidListener();
  } else if (cmd == "module") {
    if (args.size() < 2) {
      PrintUsage();
      return 1;
    }
    const std::string& sub = args[1];
    if (sub == "install" && args.size() >= 3) {
      ok = InstallModule(args[2]);
    } else if (sub == "uninstall" && args.size() >= 3) {
      ok = UninstallModule(args[2]);
    } else if (sub == "enable" && args.size() >= 3) {
      ok = EnableModule(args[2]);
    } else if (sub == "disable" && args.size() >= 3) {
      ok = DisableModule(args[2]);
    } else if (sub == "action" && args.size() >= 3) {
      ok = RunAction(args[2]);
    } else if (sub == "lua" && args.size() >= 4) {
      ok = RunLua(args[2], args[3], false, true);
    } else if (sub == "list") {
      ok = ListModules();
    } else {
      PrintUsage();
      return 1;
    }
  } else if (cmd == "sepolicy") {
    if (args.size() >= 3 && args[1] == "check") {
      ok = CheckSepolicyRule(args[2]);
    } else {
      PrintUsage();
      return 1;
    }
  } else if (cmd == "allowlist") {
    if (args.size() < 2) {
      std::fprintf(stderr, "USAGE: apd allowlist get | grant <uid> <pkg> | revoke <uid>\n");
      return 1;
    }
    const std::string& sub = args[1];
    if (sub == "get") {
      long num = ScSuUidNums(superkey);
      if (num < 0) {
        LOGE("allowlist get: ScSuUidNums failed %ld", num);
        return 1;
      }
      if (num == 0) {
        std::fprintf(stdout, "[]\n");
        return 0;
      }
      std::vector<int> uids(static_cast<size_t>(num), 0);
      long n = ScSuAllowUids(superkey, uids);
      if (n < 0) {
        LOGE("allowlist get: ScSuAllowUids failed %ld", n);
        return 1;
      }
      std::fprintf(stdout, "[\n");
      for (long i = 0; i < n; ++i) {
        std::fprintf(stdout, "  %d%s\n", uids[static_cast<size_t>(i)],
                     i + 1 < n ? "," : "");
      }
      std::fprintf(stdout, "]\n");
      return 0;
    }
    if (sub == "grant" && args.size() >= 4) {
      int uid = static_cast<int>(std::strtol(args[2].c_str(), nullptr, 10));
      SuProfile profile{};
      profile.uid = uid;
      profile.to_uid = 0;
      profile.scontext[0] = '\0';
      long rc = ScSuGrantUid(superkey, profile);
      if (rc != 0) {
        LOGE("allowlist grant failed: %ld", rc);
        return 1;
      }
      return 0;
    }
    if (sub == "revoke" && args.size() >= 3) {
      int uid = static_cast<int>(std::strtol(args[2].c_str(), nullptr, 10));
      long rc = ScSuRevokeUid(superkey, uid);
      if (rc != 0) {
        LOGE("allowlist revoke failed: %ld", rc);
        return 1;
      }
      return 0;
    }
    std::fprintf(stderr, "USAGE: apd allowlist get | grant <uid> <pkg> | revoke <uid>\n");
    return 1;
  } else {
    PrintUsage();
    return 1;
  }

  if (!ok) {
    LOGE("Command failed");
    return 1;
  }
  return 0;
}

}  // namespace apd
