#include "atasmart.h"
#include <string>
#include <string.h>
#include <errno.h>
#include <exception>
#include <stdexcept>
#include <iostream>
#include <stdio.h>

namespace DiskInfo {
namespace SMART {
ATASMART::ATASMART(const std::string _device) {
  SkDisk* disk;
  SkBool smart_available = 0;
  device_                = _device;

  if (sk_disk_open(device_.c_str(), &disk) < 0) {
    char error[255];
    sprintf(error, "Failed to open disk %s: %s(%d)\n", device_.c_str(), strerror(errno), errno);
    throw std::runtime_error(error);
  }

  if (sk_disk_smart_is_available(disk, &smart_available) < 0) {
    char error[255];
    sprintf(error, "Failed to query whether SMART is available %s: %s(%d)\n", device_.c_str(),
            strerror(errno), errno);
    throw std::runtime_error(error);
  }

  if (!smart_available) {
    char error[255];
    sprintf(error, "%s is not support SMART\n", device_.c_str());
    throw std::runtime_error(error);
  }

  if (sk_disk_smart_read_data(disk) < 0) {
    char error[255];
    sprintf(error, "Failed to read SMART data %s: %s(%d)\n", device_.c_str(), strerror(errno),
            errno);
    throw std::runtime_error(error);
  }

  const SkIdentifyParsedData* data;
  sk_disk_identify_parse(disk, &data);
  model_    = data->model;
  firmware_ = data->firmware;
  serial_   = data->serial;

  uint64_t value;
  if (!sk_disk_get_size(disk, &value)) {
    std::get<0>(capacity_) = true;
    std::get<1>(capacity_) = value;
  } else {
    std::get<0>(capacity_) = false;
  }

  if (!sk_disk_smart_get_power_cycle(disk, &value)) {
    std::get<0>(powerOnCount_) = true;
    std::get<1>(powerOnCount_) = value;
  } else {
    std::get<0>(powerOnCount_) = false;
  }

  if (!sk_disk_smart_get_power_on(disk, &value)) {
    std::get<0>(powerOnTime_) = true;
    std::get<1>(powerOnTime_) = value / (1000llu * 60llu * 60llu);
  } else {
    std::get<0>(powerOnTime_) = false;
  }

  if (!sk_disk_smart_get_temperature(disk, &value)) {
    std::get<0>(temperature_) = true;
    std::get<1>(temperature_) = static_cast<double>(value - 273150llu) / 1000.0;

    if (temperature() < 50)
      tempeHealth_.state(Health::STATE::GOOD);
    else if (temperature() < 55)
      tempeHealth_.state(Health::STATE::WARN);
    else
      tempeHealth_.state(Health::STATE::BAD);
  } else {
    std::get<0>(temperature_) = false;
  }

  sk_disk_smart_parse_attributes(
      disk,
      [](SkDisk* _, SkSmartAttributeParsedData const* _data, void* _userdata) {
        auto attribute = reinterpret_cast<std::vector<Attribute>*>(_userdata);
        Attribute attr;
        attr.id(_data->id);
        attr.name(_data->name);
        attr.current(_data->current_value);
        attr.worst(_data->worst_value);
        attr.threshold(_data->threshold);
        for (auto i = 0; i < 6; ++i) {
          attr.raw(attr.raw() + (_data->raw[i] << (8 * i)));
        }

        if ((attr.threshold() != 0) && (attr.current() < attr.threshold()))
          attr.health(Health::STATE::BAD);
        else if (((attr.id() == 0x05) || (attr.id() == 0xC5) || (attr.id() == 0xC6)) &&
                 (attr.raw() != 0))
          attr.health(Health::STATE::WARN);
        else
          attr.health(Health::STATE::GOOD);
        attribute->push_back(attr);
      },
      &attr_);
  sk_disk_free(disk);
}

uint64_t ATASMART::powerOnTime() const {
  if (std::get<0>(powerOnTime_))
    return std::get<1>(powerOnTime_);
  else
    return 0xFFFFFFFF;
}

uint64_t ATASMART::temperature() const {
  if (std::get<0>(temperature_))
    return std::get<1>(temperature_);
  else
    return 0xFFFFFFFF;
}

uint64_t ATASMART::powerOnCount() const {
  if (std::get<0>(powerOnCount_))
    return std::get<1>(powerOnCount_);
  else
    return 0xFFFFFFFF;
}

uint64_t ATASMART::capacity() const {
  if (std::get<0>(capacity_))
    return std::get<1>(capacity_);
  else
    return 0xFFFFFFFF;
}

const std::vector<Attribute> ATASMART::attr() const {
  return attr_;
}

std::string ATASMART::model() const {
  return model_;
}

std::string ATASMART::serial() const {
  return serial_;
}

std::string ATASMART::firmware() const {
  return firmware_;
}

std::string ATASMART::device() const {
  return device_;
}
} // namespace SMART
} // namespace DiskInfo
