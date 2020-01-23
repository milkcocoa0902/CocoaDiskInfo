#include "diskinfo/smart/atasmart.h"
#include "diskinfo/gui/mainWindow.h"
#include <vector>
#include <gtkmm.h>

auto main(int argc, char** argv) -> int {
  DiskInfo::SMART::ATASMART sm1("/dev/sda");
  DiskInfo::SMART::ATASMART sm2("/dev/sdb");
  DiskInfo::SMART::ATASMART sm3("/dev/sdc");
  auto app = Gtk::Application::create(argc, argv, "org.gtkmm.example");
  DiskInfo::GUI::MainWindow mw;

  mw.show();
  app->run(mw);
}
