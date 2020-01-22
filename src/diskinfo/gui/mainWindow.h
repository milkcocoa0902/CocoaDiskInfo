#ifndef COCOA_DISK_INFO_GUI_MAINWINDOW_H_
#define COCOA_DISK_INFO_GUI_MAINWINDOW_H_

#include <gtkmm.h>
#include "device.h"
#include "diskinfo/smart/atasmart.h"
#include <vector>
#include <array>
#include <memory>


namespace DiskInfo {
namespace GUI {
class MainWindow : public Gtk::Window {
public:
  MainWindow()                  = default;
  MainWindow(const MainWindow&) = default;
  MainWindow(std::vector<DiskInfo::SMART::ATASMART> _device);

private:
  Gtk::Notebook note_;
  std::vector<std::unique_ptr<DiskInfo::GUI::Device>> device_;
};
} // namespace GUI
} // namespace DiskInfo

#endif
