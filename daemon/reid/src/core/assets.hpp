#pragma once

#include <string>

namespace ksud {

// Ensure binary assets are extracted to bin_dir (e.g. KSU_BIN_DIR or AP_BIN_DIR)
int ensure_binaries(const char* bin_dir, bool ignore_if_exist);

}  // namespace ksud
