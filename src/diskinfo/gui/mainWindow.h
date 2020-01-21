#ifndef COCOA_DISK_INFO_GUI_MAINWINDOW_H_
#define COCOA_DISK_INFO_GUI_MAINWINDOW_H_

#include <gtkmm.h>
#include "device.h"
#include "diskinfo/smart/atasmart.h"
#include <vector>
#include <array>

namespace DiskInfo {
namespace GUI {
class MainWindow : public Gtk::Window {
public:
  MainWindow()                  = default;
  MainWindow(const MainWindow&) = default;
  MainWindow(std::vector<DiskInfo::SMART::ATASMART> _device);

private:
  Gtk::Notebook note_;
  std::array<DiskInfo::GUI::Device, 32> device_;
};
} // namespace GUI
} // namespace DiskInfo

#endif
