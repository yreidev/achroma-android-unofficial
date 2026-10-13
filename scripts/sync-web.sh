#!/usr/bin/env bash
# 把线上网页版的游戏文件拉到 app/src/main/assets/ach/，打包新版 APK 前跑一次。
# 按 index.html 里实际引用的本站文件下载（规则与应用内后台更新 GameFiles.kt 一致），
# 任何一个文件下载失败就整体放弃，不留半套文件。
set -euo pipefail

BASE="${ACH_BASE:-https://game.unsnow.online/ach/}"
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
DEST="$ROOT/app/src/main/assets/ach"
TMP="$(mktemp -d "$ROOT/app/src/main/assets/.sync.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
REAL_TMP="$(realpath "$TMP")" # 和下面 realpath 的结果比，路径里有符号链接时也对得上

fetch() { curl -fsSL --retry 3 --max-time 60 -o "$2" "$1"; }

fetch "$BASE" "$TMP/index.html"
# 去掉 <!-- --> 注释再找引用：注释掉的文件线上可能已经删了；没闭合的注释一直算到文件末尾
html="$(<"$TMP/index.html")"
while [[ $html == *'<!--'* ]]; do
  rest="${html#*'<!--'}"
  [[ $rest == *'-->'* ]] && rest="${rest#*'-->'}" || rest=''
  html="${html%%'<!--'*}$rest"
done
# src="…" / href="…" 里不带协议、锚点的相对路径；以 / 开头的绝对路径和 api/ 不算
refs="$(grep -oE '(src|href)="[^"#:]+"' <<<"$html" | sed -E 's/^(src|href)="//; s/"$//' | grep -vE '^(/|api/)' | sort -u || true)"
[ -n "$refs" ] || { echo "index.html 里没找到本站引用的文件，网页结构可能变了" >&2; exit 1; }
count=0
for ref in $refs; do
  file="$TMP/${ref%%\?*}" # 本地文件名去掉 ?v= 参数；index.html 原样保留，资源加载器匹配时会忽略查询串
  case "$(realpath -m "$file")" in "$REAL_TMP"/*) ;; *) echo "引用路径跑出了目录：$ref" >&2; exit 1 ;; esac
  mkdir -p "$(dirname "$file")"
  fetch "$BASE$ref" "$file"
  count=$((count + 1))
done

ver="$(grep -oE '\?v=[0-9]+' <<<"$html" | cut -d= -f2 | sort -n | tail -1 || true)"
find "$TMP" -type d -exec chmod 755 {} +
rm -rf "$DEST"
mv "$TMP" "$DEST"
echo "已同步网页版 v${ver:-?}：$count 个文件 → app/src/main/assets/ach/"
