#include "attribute.h"

namespace DiskInfo {
namespace SMART {
Attribute::Attribute() {
  stateStr.addKeys({{STATE::GOOD, "GOOD"}, {STATE::WARN, "WARN"}, {STATE::BAD, "BAD"}});

  state_     = stateStr(STATE::GOOD);
  id_        = 0;
  name_      = "";
  current_   = 0;
  worst_     = 0;
  threshold_ = 0;
  raw_       = 0;
}

void Attribute::state(const STATE _state) {
  state_ = stateStr(_state);
}

void Attribute::id(const uint8_t _id) {
  id_ = _id;
}

void Attribute::name(const std::string _name) {
  name_ = _name;
}

void Attribute::current(const uint8_t _current) {
  current_ = _current;
}

void Attribute::worst(const uint8_t _worst) {
  worst_ = _worst;
}

void Attribute::threshold(const uint8_t _threshold) {
  threshold_ = _threshold;
}

void Attribute::raw(const uint64_t _raw) {
  raw_ = _raw;
}

std::string Attribute::state(void) const {
  return state_;
}

uint8_t Attribute::id(void) const {
  return id_;
}

std::string Attribute::name(void) const {
  return name_;
}

uint8_t Attribute::current(void) const {
  return current_;
}

uint8_t Attribute::worst(void) const {
  return worst_;
}

uint8_t Attribute::threshold(void) const {
  return threshold_;
}

uint64_t Attribute::raw() const {
  return raw_;
}
} // namespace SMART
} // namespace DiskInfo
