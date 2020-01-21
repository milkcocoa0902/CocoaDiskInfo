#ifndef COCOA_DISK_INFO_GUI_DEVICE_H_
#define COCOA_DISK_INFO_GUI_DEVICE_H_

#include <gtkmm.h>
#include <gtkmm/widget.h>
#include "diskinfo/smart/atasmart.h"

namespace DiskInfo{
namespace GUI{
class Device{
		SMART::ATASMART smart_;
		Gtk::Label label;
		Gtk::Box page_;
		public:
		Device() = default;
		Device(DiskInfo::SMART::ATASMART _smart);
		Gtk::Box& Build();
};
}
}

#endif
