#include "diskinfo/smart.h"
#include <string>
#include <iostream>

int main(){
		DiskInfo::SMART smt("/dev/sda1");
		std::cout << "PowerOnTime : " << smt.PowerOnTime() << std::endl;
		std::cout << "PowerOnCount : " << smt.PowerOnCount() << std::endl;
		std::cout << "Temperature : " << smt.Temperature() << std::endl;
}
