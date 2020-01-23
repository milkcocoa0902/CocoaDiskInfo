#ifndef COCOA_DISK_INFO_GUI_MAINWINDOW_H_
#define COCOA_DISK_INFO_GUI_MAINWINDOW_H_

#include <gtkmm.h>
#include "device.h"
#include "diskinfo/smart/atasmart.h"
#include "diskinfo/util/device.h"
#include <vector>
#include <array>
#include <memory>

namespace DiskInfo {
namespace GUI {
class MainWindow : public Gtk::Window {
public:
  MainWindow();
  MainWindow(const MainWindow&) = default;

private:
  Gtk::Notebook note_;
  std::vector<std::unique_ptr<DiskInfo::GUI::Device>> device_;
};
} // namespace GUI
} // namespace DiskInfo

#endif
