#include <vector>
#include <gtkmm.h>

#include "diskinfo/gui/mainWindow.h"

auto main(int argc, char** argv) -> int {
  auto app = Gtk::Application::create(argc, argv, "CocoaDiskInfo.milkcocoa.info");
  DiskInfo::GUI::MainWindow mw;

  mw.show();
  app->run(mw);
}
