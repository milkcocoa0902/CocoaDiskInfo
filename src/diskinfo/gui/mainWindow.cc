#include "mainWindow.h"
#include <iostream>

namespace DiskInfo {
namespace GUI {
MainWindow::MainWindow() {
  set_border_width(10);
  set_default_size(800, 500);
  set_title("CocoaDiskInfo");

  std::vector<DiskInfo::SMART::ATASMART> dev;
  for (auto d : (new DiskInfo::Util::Device())->device()) {
    dev.push_back(DiskInfo::SMART::ATASMART(d));
  }

  for (auto d : dev) {
    auto ptr = std::make_unique<DiskInfo::GUI::Device>(d);
    device_.push_back(std::move(ptr));
    note_.append_page(device_.back()->Build(), d.device());
  }

  add(note_);
  show_all_children();
}
} // namespace GUI
} // namespace DiskInfo
