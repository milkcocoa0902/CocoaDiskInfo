#ifndef COCOA_DISK_INFO_GUI_DEVICE_H_
#define COCOA_DISK_INFO_GUI_DEVICE_H_

#include <gtkmm.h>
#include <gtkmm/widget.h>
#include "diskinfo/smart/atasmart.h"

namespace DiskInfo {
namespace GUI {
class Device {
  SMART::ATASMART smart_;

  class treeModel : public Gtk::TreeModel::ColumnRecord {
  public:
    treeModel() {
      add(state_);
      add(id_);
      add(name_);
      add(current_);
      add(worst_);
      add(threshold_);
      add(raw_);
    }

    Gtk::TreeModelColumn<Glib::ustring> state() const {
      return state_;
    };
    Gtk::TreeModelColumn<uint64_t> id() const {
      return id_;
    };
    Gtk::TreeModelColumn<Glib::ustring> name() const {
      return name_;
    };
    Gtk::TreeModelColumn<uint64_t> current() const {
      return current_;
    };
    Gtk::TreeModelColumn<uint64_t> worst() const {
      return worst_;
    };
    Gtk::TreeModelColumn<uint64_t> threshold() const {
      return threshold_;
    };
    Gtk::TreeModelColumn<uint64_t> raw() const {
      return raw_;
    };

  private:
    Gtk::TreeModelColumn<Glib::ustring> state_;
    Gtk::TreeModelColumn<uint64_t> id_;
    Gtk::TreeModelColumn<Glib::ustring> name_;
    Gtk::TreeModelColumn<uint64_t> current_;
    Gtk::TreeModelColumn<uint64_t> worst_;
    Gtk::TreeModelColumn<uint64_t> threshold_;
    Gtk::TreeModelColumn<uint64_t> raw_;
  };

  treeModel model_;
  Gtk::TreeView tree_;
  Glib::RefPtr<Gtk::TreeStore> treeStore_;
  Gtk::Label label;
  Gtk::Box page_;

public:
  Device() = default;
  Device(DiskInfo::SMART::ATASMART _smart);
  Gtk::Box& Build();
  void CreateTree();
};
} // namespace GUI
} // namespace DiskInfo

#endif
