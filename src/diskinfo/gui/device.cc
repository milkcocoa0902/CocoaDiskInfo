#include "device.h"
#include <utility>
#include <string>
#include <sstream>
#include <iomanip>

namespace DiskInfo {
namespace GUI {
Device::Device(DiskInfo::SMART::ATASMART _smart) {
  smart_ = _smart;
}

Gtk::HBox& Device::Build() {
  CreateInfo();
  dev_.pack_start(info_, Gtk::PACK_SHRINK, 4);
  dev_.pack_start(state_, Gtk::PACK_SHRINK, 4);
  CreateTree();

  page_.pack_start(dev_, Gtk::PACK_EXPAND_WIDGET, 10);
  page_.pack_start(tree_, Gtk::PACK_EXPAND_WIDGET, 10);
  return page_;
}

void Device::CreateInfo() {
  info_.set_label("Device Info");

  devModel_       = labeldText("model", smart_.model());
  devSerial_      = labeldText("Serial", smart_.serial());
  devPowerOn_     = labeldText("PowerOnHour", std::to_string(smart_.powerOnTime()));
  devTemperature_ = labeldText("Temperature", std::to_string(smart_.temperature()));

  infoBox_.set_border_width(4);
  infoBox_.pack_start(devModel_.Build(), Gtk::PACK_SHRINK, 4);
  infoBox_.pack_start(devSerial_.Build(), Gtk::PACK_SHRINK, 4);
  info_.add(infoBox_);

  stateBox_.set_border_width(4);
  stateBox_.pack_start(devPowerOn_.Build(), Gtk::PACK_SHRINK, 4);
  stateBox_.pack_start(devTemperature_.Build(), Gtk::PACK_SHRINK, 4);
  state_.add(stateBox_);
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
  tree_.get_column(6)->set_alignment(1);

  for (auto attr : smart_.attr()) {
    std::stringstream ss;
    ss << std::setw(8) << std::setfill('0') << std::hex;
    auto row                = *(treeStore_->append());
    row[model_.state()]     = "";
    row[model_.id()]        = attr.id();
    row[model_.name()]      = attr.name();
    row[model_.current()]   = attr.current();
    row[model_.worst()]     = attr.worst();
    row[model_.threshold()] = attr.threshold();
    ss << attr.raw();
    row[model_.raw()] = "0x" + ss.str();
  }

  tree_.expand_all();
}

} // namespace GUI
} // namespace DiskInfo
