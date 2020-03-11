#include "health.h"

namespace DiskInfo {
namespace SMART {
Health::Health() {
  stateStr.addKeys({{STATE::GOOD, "GOOD"}, {STATE::WARN, "WARN"}, {STATE::BAD, "BAD"}});
  state_ = stateStr(STATE::GOOD);
}

void Health::state(const STATE _state) {
  state_ = stateStr(_state);
}

std::string Health::state(void) const {
  return state_;
}
} // namespace SMART
} // namespace DiskInfo
