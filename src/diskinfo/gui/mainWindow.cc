#include "mainWindow.h"

namespace DiskInfo{
namespace GUI{
	MainWindow::MainWindow(std::vector<DiskInfo::SMART::ATASMART> _device){
			set_border_width(10);
			set_default_size(800, 500);
			set_title("CocoaDiskInfo");

			for(auto dev : _device){
					device_ = DiskInfo::GUI::Device(dev);
					note_.append_page(device_.Build());
			}

				add(note_);
		show_all_children();
	}
}
}
