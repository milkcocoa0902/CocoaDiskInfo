#include "device.h"
#include <utility>
#include <iostream>
namespace DiskInfo{
namespace GUI{
Device::Device(DiskInfo::SMART::ATASMART _smart){
		smart_ = _smart;
		label = Gtk::Label(smart_.model());
}

Gtk::Box& Device::Build(){
	page_.pack_start(label, Gtk::PACK_SHRINK, 4);
	return page_;
}

}
}
