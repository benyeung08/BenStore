#!/bin/sh
# BenStore 沒有 gradle-wrapper.jar，這個檔案不是標準 Gradle Wrapper。
# 它只是轉發到 PATH 上的 Gradle，請先自行安裝 Gradle 8.7：
#   https://gradle.org/releases/
# CI 不使用這個檔案（workflow 已明確安裝並呼叫 Gradle 8.7）。
set -e

GRADLE_BIN="$(command -v gradle 2>/dev/null || true)"
if [ -z "$GRADLE_BIN" ]; then
  echo "error: gradle not found. 請安裝 Gradle 8.7 並加入 PATH。" >&2
  exit 1
fi

VER="$("$GRADLE_BIN" --version | sed -n 's/^Gradle \([0-9.]*\)$/\1/p' | head -1)"
case "$VER" in
  8.7|8.7.*) ;;
  *) echo "warning: 目前 Gradle 版本為 ${VER:-未知}，AGP 8.5.2 建議使用 8.7" >&2 ;;
esac

exec "$GRADLE_BIN" "$@"
