#!/usr/bin/env bash
# Java 安全向探针重跑脚本（2026-10-01 首跑）
# 用法：在 git-bash 里  bash run.sh
# 说明：全部是本地自建靶子与只读探测；日志按批次**追加**写入，历史批次都留着
set -u
JH_WIN="C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
JH="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
OUT_WIN="$LOCALAPPDATA/Temp/javasec-classes"
OUT="$LOCALAPPDATA/Temp/javasec-classes"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/本机实测输出.txt"

mkdir -p "$OUT"
cd "$HERE" || exit 1
# MSYS bash 不会把路径转成 Windows 形式给原生 javac —— 先 cd，再传相对文件名
"$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$OUT_WIN" *.java || exit 1

# 同名类的「补丁版本」编译到另一个目录，供 agent 做运行期替换
PATCH_WIN="$LOCALAPPDATA/Temp/javasec-patch"
mkdir -p "$PATCH_WIN"
"$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$PATCH_WIN" patch/Victim.java || exit 1

# agent 需要一个带 Premain-Class 的 manifest
AGENT_WIN="$LOCALAPPDATA/Temp/javasec-agent"
mkdir -p "$AGENT_WIN"
printf 'Premain-Class: SecAgent\nAgent-Class: SecAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n' > "$AGENT_WIN/manifest.txt"
"$JH/bin/jar" --create --file "$AGENT_WIN/agent.jar" --manifest "$AGENT_WIN/manifest.txt" -C "$OUT_WIN" SecAgent.class || exit 1

: >> "$LOG"
{
  echo ""
  echo "================================================================"
  echo "=== 本次运行：$(date '+%Y-%m-%d %H:%M:%S')  JDK: $("$JH/bin/java" -version 2>&1 | head -1)  （日志追加写入）"
  echo "=== 提醒：本目录所有程序只在本地自建靶子上运行，不下载、不落地、不对外"
  echo "================================================================"
} | tee -a "$LOG"

run() {   # run <节标题> <主类> [程序参数...]
  local title="$1"; shift
  local cls="$1"; shift
  {
    echo ""
    echo "########## $title ##########"
    "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" "$cls" "$@" 2>&1
  } | tee -a "$LOG"
}

runj() {  # runj <节标题> <JVM 参数(可含空格)> <主类> [程序参数...]
  local title="$1"; shift
  local jvmflags="$1"; shift
  local cls="$1"; shift
  {
    echo ""
    echo "########## $title ##########"
    # shellcheck disable=SC2086
    "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $jvmflags -cp "$OUT_WIN" "$cls" "$@" 2>&1
  } | tee -a "$LOG"
}

run "SecBoundary（本机 JDK 25 安全画像）" SecBoundary
runj "SecBoundary（对照：用 --add-opens 松动模块封装后再跑一次）" \
     "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.misc=ALL-UNNAMED" \
     SecBoundary
run "SecDeserBasic（反序列化会执行代码：最小复现）" SecDeserBasic
run "SecDeserFilter（无进程级过滤器）" SecDeserFilter
runj "SecDeserFilter（带 -Djdk.serialFilter 的进程级过滤器）" \
     "-Djdk.serialFilter=!SecDeserFilter\$Dangerous;java.base/*;!*" \
     SecDeserFilter
run "SecClassLoader（内存马原理：从字节数组定义类）" SecClassLoader "$OUT_WIN/Payload.class"
run "SecDangerApis（常见攻击面 API 的默认行为）" SecDangerApis
run "SecJndi（JNDI 注入：默认会不会去下载远程类）" SecJndi

{
  echo ""
  echo "########## javaagent（运行期替换已加载的类：内存马/热部署的同一套 API） ##########"
  "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
      "-javaagent:$AGENT_WIN/agent.jar" "-Dpatched.class.file=$PATCH_WIN/Victim.class" \
      -cp "$OUT_WIN" Victim 2>&1
  echo ""
  echo "--- 对照：不带 -javaagent 跑同一个类（磁盘实现没被改过）---"
  "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" Victim 2>&1
} | tee -a "$LOG"

rm -rf "$OUT" "$PATCH_WIN" "$AGENT_WIN"
