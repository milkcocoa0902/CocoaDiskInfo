#include "device.h"
#include <utility>
#include <iostream>
namespace DiskInfo {
namespace GUI {
Device::Device(DiskInfo::SMART::ATASMART _smart) {
  smart_ = _smart;
  label  = Gtk::Label(smart_.model());
}

Gtk::Box& Device::Build() {
  page_.pack_start(label, Gtk::PACK_SHRINK, 4);
  CreateTree();
  page_.pack_start(tree_, Gtk::PACK_EXPAND_WIDGET, 10);
  return page_;
}

void Device::CreateTree() {
  treeStore_ = Gtk::TreeStore::create(model_);
  tree_.set_model(treeStore_);
  tree_.append_column_editable("state", model_.state());
  tree_.append_column("id", model_.id());
  tree_.append_column("name", model_.name());
  tree_.append_column("currnet", model_.current());
  tree_.append_column("worst", model_.worst());
  tree_.append_column("threshold", model_.threshold());
  tree_.append_column("raw", model_.raw());

  for (auto attr : smart_.attr()) {
    auto row                = *(treeStore_->append());
    row[model_.state()]     = "";
    row[model_.id()]        = attr.id();
    row[model_.name()]      = attr.name();
    row[model_.current()]   = attr.current();
    row[model_.worst()]     = attr.worst();
    row[model_.threshold()] = attr.threshold();
    row[model_.raw()]       = attr.raw();
  }

  tree_.expand_all();
}

} // namespace GUI
} // namespace DiskInfo
