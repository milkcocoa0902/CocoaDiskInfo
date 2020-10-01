#!/bin/bash

. "$(dirname "$0")/../isTopLevel.sh"

RETVAL=0

for f in $(git ls-files | grep -E '.*\.(cc|h)$'); do
  if ! clang-format "${f}" | diff "${f}" - > /dev/null 2>&1; then
    echo "Error: Invalid code formatting in ${f}" >&2
    RETVAL=1
  fi
done

exit $RETVAL
