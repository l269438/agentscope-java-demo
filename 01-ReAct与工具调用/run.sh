#!/usr/bin/env sh
set -eu
demo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec sh "$demo_root/scripts/运行示例.sh" "01" "$@"
