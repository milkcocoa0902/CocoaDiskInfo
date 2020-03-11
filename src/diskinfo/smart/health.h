#ifndef COCOA_DISK_INFO_SMART_HEALTH_H_
#define COCOA_DISK_INFO_SMART_HEALTH_H_

#include <stdint.h>
#include <string>
#include <diskinfo/util/enum2str.h>

namespace DiskInfo {
namespace SMART {
class Health {
public:
  enum class STATE : uint32_t { GOOD, WARN, BAD };

private:
  enumStr<STATE> stateStr;
  std::string state_;

public:
  Health();
  void state(const STATE _state);
  std::string state(void) const;
};
} // namespace SMART
} // namespace DiskInfo
#endif
