#ifndef COCOA_DISK_INFO_UTIL_ENUM2STR_H_
#define COCOA_DISK_INFO_UTIL_ENUM2STR_H_

#include <string>
#include <unordered_map>
#include <vector>

template <class Key>
class enumStr {
public:
  explicit enumStr() {}
  enumStr(const enumStr&) = default;
  /// @brief enumのmapにkeyと文字列を追加する
  /// @param _key enumの列挙定数
  /// @param _str 連想したい文字列
  /// @return none
  void addKey(Key _key, std::string _str) {
    // 指定した列挙定数が登録されていなければ登録する
    if (e2s.count(_key) == 0) e2s[_key] = _str;
    if (s2e.count(_str) == 0) s2e[_str] = _key;
  }
  /// @brief enumのmaoにkeyと文字列を複数登録する
  /// @param _pairs 列挙定数と連想文字列をペアにしたもの
  /// @return none
  void addKeys(std::vector<std::pair<Key, std::string>> _pairs) {
    for (auto pair : _pairs) {
      addKey(pair.first, pair.second);
    }
  }
  /// @brief 列挙定数を元に文字列を返すやつ
  /// @param _key 列挙定数
  /// @return 対応した文字列
  std::string operator()(Key _key) {
    // 指定した列挙定数が登録されていればその文字列を返す
    if (e2s.count(_key) != 0) return e2s.at(_key);
    return "";
  }
  /// @brief 文字列を元に列挙定数を返すやつ
  /// @param _str 列挙定数を表す文字列
  /// @return 列挙定数, 登録されていなければ`0`
  Key operator()(std::string _str) {
    if (s2e.count(_str) != 0) return s2e.at(_str);
    return static_cast<Key>(0);
  }

private:
  std::unordered_map<Key, std::string> e2s;
  std::unordered_map<std::string, Key> s2e;
};

#endif
