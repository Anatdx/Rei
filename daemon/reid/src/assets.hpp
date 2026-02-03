#pragma once

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

namespace ksud {

// List all embedded asset names
const std::vector<std::string>& list_assets();

// Get asset data by name
bool get_asset(const std::string& name, const uint8_t*& data, size_t& size);

// Copy asset to file
bool copy_asset_to_file(const std::string& name, const std::string& dest_path);

// List supported KMI versions (extracted from embedded LKM names)
std::vector<std::string> list_supported_kmi();

// Ensure binary assets are extracted to bin_dir (e.g. KSU_BIN_DIR or AP_BIN_DIR)
int ensure_binaries(const char* bin_dir, bool ignore_if_exist);

}  // namespace ksud
