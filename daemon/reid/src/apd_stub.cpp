// Stub when building for non-Android: apd (KernelPatch backend) is only used on device.
#include <cstdio>

namespace apd {

void InitLog() {}

int RunCli(int /* argc */, char** /* argv */) {
  std::fprintf(stderr, "apd (KernelPatch backend) is only available on Android.\n");
  return 1;
}

}  // namespace apd
