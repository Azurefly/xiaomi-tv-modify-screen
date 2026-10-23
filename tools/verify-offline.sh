#!/usr/bin/env bash
# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only
#
# 离线精简校验：不安装 Android SDK，只取「能判定能不能编过」的四样东西
#   android.jar（框架 API） + aapt2（资源编译/链接） + 依赖 jar（media3 等） + d8（字节码→dex）
# 相对完整 SDK（约 1.2 GB）只下载约 55 MB，缓存在临时目录，不污染仓库。
#
# 用法：
#   bash tools/verify-offline.sh
#   VERIFY_WORK=~/cache/xtms bash tools/verify-offline.sh   # 指定缓存目录，第二次跑可秒过
# 前提：JDK 17 或 21（javac / java 在 PATH 上）、curl、unzip。
#
# 它校验什么：资源能否编译、引用是否闭合（@string/@color/@style/@id）、
# Java 类型是否正确（含 R.id 与控件类型）、能否打成 min-API 23 的 dex。
# 它不校验什么：lint、R8 压缩、签名打包、真机行为 —— 这些仍需 `bash build-local.sh`。

set -eu
cd "$(dirname "$0")/.."
ROOT=$PWD
WORK=${VERIFY_WORK:-${TMPDIR:-/tmp}/xtms-verify}
API=36
AAPT2_VER=8.11.1-12782657
MEDIA3_VER=1.9.4
R8_VER=8.11.32
GUAVA_VER=33.4.0-jre
GOOGLE=https://dl.google.com/dl/android/maven2
CENTRAL=https://repo1.maven.org/maven2

# ---- 从构建脚本读取真实的包名与版本，避免脚本和工程脱节 ----
PKG=$(sed -n "s/.*namespace[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" app/build.gradle | head -1)
VC=$(sed -n "s/.*versionCode[[:space:]]*\([0-9]*\).*/\1/p" app/build.gradle | head -1)
VN=$(sed -n "s/.*versionName[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" app/build.gradle | head -1)
MIN_SDK=$(sed -n "s/.*minSdk[[:space:]]*\([0-9]*\).*/\1/p" app/build.gradle | head -1)
: "${PKG:?无法从 app/build.gradle 读出 namespace}" "${VC:?versionCode}" "${VN:?versionName}" "${MIN_SDK:?minSdk}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) AAPT2_OS=windows; EXE=.exe ;;
  Darwin)               AAPT2_OS=osx;     EXE= ;;
  *)                    AAPT2_OS=linux;   EXE= ;;
esac

mkdir -p "$WORK"/{dl,sdk,libs,gen,classes,dex}
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
fetch() { # fetch <url> <out>；已存在则跳过
  [ -s "$2" ] && return 0
  curl -fsSL --retry 2 --max-time 300 -o "$2" "$1" || { echo "下载失败: $1" >&2; exit 1; }
}

say "1/5 准备平台与工具（缓存于 $WORK）"
if [ ! -s "$WORK/sdk/android.jar" ]; then
  fetch "https://dl.google.com/android/repository/platform-${API}_r01.zip" "$WORK/dl/platform.zip"
  unzip -o -q -j "$WORK/dl/platform.zip" "*/android.jar" -d "$WORK/sdk"
  rm -f "$WORK/dl/platform.zip"          # 只留 android.jar，其余 40 MB 不留
fi
[ -s "$WORK/bin/aapt2$EXE" ] || {
  fetch "$GOOGLE/com/android/tools/build/aapt2/$AAPT2_VER/aapt2-$AAPT2_VER-$AAPT2_OS.jar" "$WORK/dl/aapt2.jar"
  mkdir -p "$WORK/bin"; unzip -o -q -j "$WORK/dl/aapt2.jar" "aapt2$EXE" -d "$WORK/bin"
}
AAPT2="$WORK/bin/aapt2$EXE"
fetch "$GOOGLE/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar" "$WORK/dl/r8.jar"
echo "android.jar API $API · aapt2 $( "$AAPT2" version 2>&1 | sed 's/^Android Asset Packaging Tool (aapt) //' )"

say "2/5 准备依赖 jar（media3 + 注解 + guava）"
for a in media3-common media3-datasource media3-decoder media3-container media3-extractor \
         media3-exoplayer media3-exoplayer-hls media3-exoplayer-dash media3-ui; do
  [ -s "$WORK/libs/$a.jar" ] && continue
  fetch "$GOOGLE/androidx/media3/$a/$MEDIA3_VER/$a-$MEDIA3_VER.aar" "$WORK/dl/$a.aar"
  ( mkdir -p "$WORK/dl/x_$a" && cd "$WORK/dl/x_$a" \
    && unzip -o -q "../$a.aar" classes.jar && mv classes.jar "$WORK/libs/$a.jar" )
done
# OptIn 注解在 annotation-experimental 里（不在 annotation 1.9.1），media3 的公开签名还会露出 guava
[ -s "$WORK/libs/androidx-annotation-experimental.jar" ] || {
  fetch "$GOOGLE/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.aar" "$WORK/dl/ann-exp.aar"
  ( cd "$WORK/dl" && mkdir -p x_ann && cd x_ann \
    && unzip -o -q ../ann-exp.aar classes.jar && mv classes.jar "$WORK/libs/androidx-annotation-experimental.jar" )
}
fetch "$CENTRAL/com/google/guava/guava/$GUAVA_VER/guava-$GUAVA_VER.jar" "$WORK/libs/guava.jar"
# media3-ui 的 attrs（use_controller / keep_content_on_player_reset）在 aar 的 res 里，链接时要合并
[ -d "$WORK/dl/x_media3-ui/res" ] || {
  fetch "$GOOGLE/androidx/media3/media3-ui/$MEDIA3_VER/media3-ui-$MEDIA3_VER.aar" "$WORK/dl/media3-ui.aar"
  mkdir -p "$WORK/dl/x_media3-ui"
  ( cd "$WORK/dl/x_media3-ui" && unzip -o -q ../media3-ui.aar -d . )
}

say "3/5 aapt2 compile —— 资源可编译"
rm -f "$WORK/res.zip"
"$AAPT2" compile --dir app/src/main/res -o "$WORK/res.zip"

say "4/5 aapt2 link —— 引用闭合，产出 R.java"
# AGP 8 把 package 放在 build.gradle 的 namespace 里，aapt2 仍需清单内的 package，故注入副本
if grep -q 'package="' app/src/main/AndroidManifest.xml; then
  cp app/src/main/AndroidManifest.xml "$WORK/AndroidManifest.xml"
else
  sed "s|<manifest |<manifest package=\"$PKG\" |" app/src/main/AndroidManifest.xml > "$WORK/AndroidManifest.xml"
fi
LIBRES=()
if [ -d "$WORK/dl/x_media3-ui/res" ]; then   # media3-ui 的 attrs（use_controller 等），Gradle 会自动合并
  rm -f "$WORK/libres-ui.zip"
  "$AAPT2" compile --dir "$WORK/dl/x_media3-ui/res" -o "$WORK/libres-ui.zip"
  LIBRES=(--auto-add-overlay "$WORK/libres-ui.zip")
fi
rm -rf "$WORK/gen"; mkdir -p "$WORK/gen"
"$AAPT2" link -o "$WORK/app.apk" -I "$WORK/sdk/android.jar" \
  --manifest "$WORK/AndroidManifest.xml" --java "$WORK/gen" \
  --min-sdk-version "$MIN_SDK" --version-code "$VC" --version-name "$VN" \
  ${LIBRES[@]+"${LIBRES[@]}"} "$WORK/res.zip"

say "5/5 javac + d8 —— Java 类型正确、可打成 dex"
CP="$WORK/sdk/android.jar"
for j in "$WORK"/libs/*.jar; do CP="$CP:$j"; done
rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"
javac -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -nowarn -proc:none --release 17 -classpath "$CP:$WORK/gen" \
  -d "$WORK/classes" $(find app/src/main/java -name '*.java')

CPARGS=""
for j in "$WORK"/libs/*.jar; do CPARGS="$CPARGS --classpath $j"; done
rm -rf "$WORK/dex"; mkdir -p "$WORK/dex"
java -cp "$WORK/dl/r8.jar" com.android.tools.r8.D8 --min-api "$MIN_SDK" \
  --lib "$WORK/sdk/android.jar" $CPARGS --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class')

printf '\n\033[32m全部通过\033[0m · %s %s · class %s 个 · dex %s KB · apk %s KB\n' \
  "$PKG" "$VN" \
  "$(find "$WORK/classes" -name '*.class' | wc -l | tr -d ' ')" \
  "$(( $(wc -c < "$WORK/dex/classes.dex") / 1024 ))" \
  "$(( $(wc -c < "$WORK/app.apk") / 1024 ))"
echo "（未覆盖：lint、R8 压缩、签名、真机 —— 有 SDK 时请跑 bash build-local.sh）"
