#ifndef COCOA_DISK_INFO_SMART_H_
#define COCOA_DISK_INFO_SMART_H_

#include <stdlib.h>
extern "C"{
#include <atasmart.h>
}
#include <string>
#include <memory>

namespace DiskInfo{
		class SMART{
			public:
					SMART(const std::string _device);
					uint64_t Temperature();
					uint64_t PowerOnTime();
			private:
					SkDisk *disk_;

		};
}

#endif
