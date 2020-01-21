#include "mainWindow.h"

namespace DiskInfo {
namespace GUI {
MainWindow::MainWindow(std::vector<DiskInfo::SMART::ATASMART> _device) {
  set_border_width(10);
  set_default_size(800, 500);
  set_title("CocoaDiskInfo");

  auto i = 0;
  for (auto dev : _device) {
    device_[i] = DiskInfo::GUI::Device(dev);
    note_.append_page(device_[i].Build());
    i++;
  }

  add(note_);
  show_all_children();
}
} // namespace GUI
} // namespace DiskInfo
