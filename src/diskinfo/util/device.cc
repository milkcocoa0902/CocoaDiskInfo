#include "device.h"

namespace DiskInfo {
namespace Util {
Device::Device() {
  auto dir = opendir("/sys/block");

  while (auto elm = readdir(dir)) {
    if (std::string(".") != std::string(elm->d_name) &&
        std::string("..") != std::string(elm->d_name) &&
        std::string("ram") != std::string(elm->d_name).substr(0, 3) &&
        std::string("loop") != std::string(elm->d_name).substr(0, 4)) {
      SkDisk* skdisk;
      SkBool b;
      int f = sk_disk_open((std::string("/dev/") + std::string(elm->d_name)).c_str(), &skdisk);
      if (f < 0) {
        continue;
      }
      int smart_ret = sk_disk_smart_is_available(skdisk, &b);
      sk_disk_free(skdisk);
      if (smart_ret < 0) {
        continue;
      }
      if (b) {
        device_.push_back(std::string("/dev/") + std::string(elm->d_name));
      }
    }
  }
  std::sort(device_.begin(), device_.end(),
            [](std::string const& _lhs, std::string const& _rhs) { return _lhs < _rhs; });
}

std::vector<std::string> Device::device() const {
  return device_;
}
} // namespace Util
} // namespace DiskInfo
