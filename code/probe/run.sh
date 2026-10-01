#!/usr/bin/env bash
# 本机 JDK 探针重跑脚本（2026-10-01 首跑）
# 用法：在 git-bash 里  bash run.sh
# 产物：编译到临时目录，输出汇总到 本机实测输出.txt；不往库里丢 .class
set -u
JH_WIN="C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
JH="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
OUT_WIN="$LOCALAPPDATA/Temp/javaprobe-classes"
OUT="$LOCALAPPDATA/Temp/javaprobe-classes"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/本机实测输出.txt"

mkdir -p "$OUT"
# 注意：这里是 MSYS bash，路径不会自动转成 Windows 形式给原生 javac 用 —— 先在目录里 cd，再传相对文件名
cd "$HERE" || exit 1
"$JH/bin/javac" -encoding UTF-8 -d "$OUT_WIN" ProbeEnv.java ProbeTypes.java ProbeOop.java ProbeCollections.java ProbeConcurrent.java ProbeEncoding.java ProbeExtra.java ProbeDefaultEncoding.java || exit 1

: >> "$LOG"
{
  echo ""
  echo "================================================================"
  echo "=== 本次运行：$(date '+%Y-%m-%d %H:%M:%S')  （日志是追加的，历史批次都留着）"
  echo "=== 提醒：并发段（i++ 竞态、虚拟线程耗时）的数字逐轮会变，这是特性不是 bug"
  echo "================================================================"
} | tee -a "$LOG"
{
  echo ""
  echo "########## ProbeDefaultEncoding（故意不带任何 -D 编码开关，看平台默认是什么） ##########"
  "$JH/bin/java" -cp "$OUT_WIN" ProbeDefaultEncoding 2>&1
  echo ""
  echo "########## 下面各节都带了 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 ##########"
  echo "########## 所以本节里的 stdout.encoding 会报 UTF-8，与上面的默认值不同，不是矛盾 ##########"
  for c in ProbeEnv ProbeTypes ProbeOop ProbeCollections ProbeConcurrent ProbeEncoding ProbeExtra; do
    echo ""
    echo "########## $c ##########"
    "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" "$c" 2>&1
  done
} | tee -a "$LOG"

rm -rf "$OUT"
