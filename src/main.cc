#include "diskinfo/smart/atasmart.h"

#include <string>
#include <iostream>

#include <boost/format.hpp>

#include <stdio.h>

int main() {
  DiskInfo::SMART::ATASMART smt("/dev/sda1");
  std::cout << "PowerOnTime : " << smt.powerOnTime() << std::endl;
  std::cout << "PowerOnCount : " << smt.powerOnCount() << std::endl;
  std::cout << "Temperature : " << smt.temperature() << std::endl;

  auto attribute = smt.attr();
  for (auto attr : attribute) {
    printf("name : %s\n\tid : %d\n\tcurrnet : %d\n\tworst : %d\n\tthreshold : %d\n\traw : %X\n",
           attr.name().c_str(), attr.id(), attr.current(), attr.worst(), attr.threshold(),
           attr.raw());
  }
}
