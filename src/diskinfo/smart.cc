#include "smart.h"
#include <string>
#include <string.h>
#include <errno.h>
#include <exception>
#include <stdexcept>
#include <iostream>


namespace DiskInfo{
		SMART::SMART(const char* _device){
				SkBool smart_available = 0;
				if (sk_disk_open(_device, &disk_) < 0) {
						fprintf(stderr, "Failed to open disk %s: %s(%d)\n", _device, strerror(errno), errno);
						 throw std::runtime_error("error1!");
				}
					
				if (sk_disk_smart_is_available(disk_, &smart_available) < 0) {
						fprintf(stderr, "Failed to query whether SMART is available %s: %s(%d)\n", _device, strerror(errno), errno);
						 throw std::runtime_error("error2!");
				}
					
				if (!smart_available) {
						fprintf(stderr, "%s is not support SMART\n", _device);
						 throw std::runtime_error("error3!");
				}

				if (sk_disk_smart_read_data(disk_) < 0) {
						fprintf(stderr, "Failed to read SMART data %s: %s(%d)\n", _device, strerror(errno), errno);
						 throw std::runtime_error("error4!");
				}
		}

		uint64_t SMART::PowerOnTime(){
				uint64_t tmp;
				if (sk_disk_smart_get_power_on(disk_, &tmp) == 0) {
						return tmp / 1000 / 3600;
				}
		}
}
