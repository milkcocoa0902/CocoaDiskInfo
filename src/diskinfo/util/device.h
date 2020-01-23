#ifndef COCOA_DISK_INFO_UTIL_DEVICE_H_
#define COCOA_DISK_INFO_UTIL_DEVICE_H_

#include <algorithm>
#include <vector>
#include <algorithm>
#include <string>
#include <sys/signal.h>
#include <dirent.h>

extern "C" {
#include <atasmart.h>
}

namespace DiskInfo {
namespace Util {
class Device {
  std::vector<std::string> device_;

public:
  Device();
  std::vector<std::string> device() const;
};
} // namespace Util
} // namespace DiskInfo

#endif
