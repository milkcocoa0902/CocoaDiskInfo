#!/bin/bash

. "$(dirname "$0")/../isTopLevel.sh"

RETVAL=0

for f in $(git ls-files | grep -E '^src\/.*\.h$'); do
  # ファイルパスから正しいインクルードガードの文字列を生成する
  s1="COCOA_DISK_INFO_$(echo "$f" | sed -r 's/^src\/diskinfo\///; s/[\/\.-]+/_/g; s/^.*$/\U&/')_"

	# ファイルからインクルードガードを読み込む
  s2=$(grep -Pzo '#ifndef\s+\K\b(\w+)\b(?=\s+#define\s+\b\1\b)' "$f" | tr '\0' '\n')

  if [ -z "$s2" ]; then
    echo "error: $f: include guard is missing or incorrect" >&2
    echo "       ($s1)" >&2
    RETVAL=1
  elif [ "$s2" != "$s1" ]; then
    echo "error: $f: include guard is incorrect" >&2
    echo "       ($s2 => $s1)" >&2
    RETVAL=1
  fi
done

exit $RETVAL
