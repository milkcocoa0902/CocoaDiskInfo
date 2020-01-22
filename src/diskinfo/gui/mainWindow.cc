#include "mainWindow.h"

namespace DiskInfo {
namespace GUI {
MainWindow::MainWindow(std::vector<DiskInfo::SMART::ATASMART> _device) {
  set_border_width(10);
  set_default_size(800, 500);
  set_title("CocoaDiskInfo");

  for (auto dev : _device) {
			auto ptr = std::make_unique<DiskInfo::GUI::Device>(dev);
			device_.push_back(std::move(ptr));
    note_.append_page(device_.back()->Build());
  }

  add(note_);
  show_all_children();
}
} // namespace GUI
} // namespace DiskInfo
