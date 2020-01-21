#include "diskinfo/smart/atasmart.h"
#include "diskinfo/gui/mainWindow.h"
#include <vector>
#include <gtkmm.h>

auto main(int argc, char** argv) -> int {
  DiskInfo::SMART::ATASMART smt("/dev/sda");
  auto app = Gtk::Application::create(argc, argv, "org.gtkmm.example");
  std::vector<DiskInfo::SMART::ATASMART> a;
  a.push_back(smt);
  DiskInfo::GUI::MainWindow mw(a);

  mw.show();
  app->run(mw);
}
