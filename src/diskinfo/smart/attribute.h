#ifndef COCOA_DISK_INFO_SMART_ATTRIBUTE_H_
#define COCOA_DISK_INFO_SMART_ATTRIBUTE_H_

#include <stdint.h>
#include <string>

namespace DiskInfo {
namespace SMART {
class Attribute {
  uint8_t id_;
  std::string name_;
  uint8_t current_;
  uint8_t worst_;
  uint8_t threshold_;
  uint64_t raw_;

public:
  Attribute();
  void id(const uint8_t _id);
  void name(const std::string _name);
  void current(const uint8_t _current);
  void worst(const uint8_t _worst);
  void threshold(const uint8_t _threshold);
  void raw(const uint64_t _raw);

  uint8_t id(void) const;
  std::string name(void) const;
  uint8_t current(void) const;
  uint8_t worst(void) const;
  uint8_t threshold(void) const;
  uint64_t raw(void) const;
};
} // namespace SMART
} // namespace DiskInfo
#endif
