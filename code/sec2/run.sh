#!/usr/bin/env bash
# 深度靶场（安全专题续卷用）：四个「没做到底」的实验，全部只用本机 JDK。
#   1) 运行期附加：目标 JVM 已经在跑，外部进程 attach 进去换实现（jdk.attach + agentmain）
#   2) 进程内改字节码：读原类字节 → Class-File API 改方法体 → redefineClasses（不写第二份源码）
#   3) 反序列化过滤器进阶：maxdepth / maxrefs / maxarray / 进程级只能设一次
#   4) JNDI：真走到 Reference 解码那一步（带/不带 --add-exports 三档对照）
# 只在本机自建靶子上跑；HTTP 只绑 127.0.0.1；不下载、不落地、不对外。
# 输出**追加**到 本机实测输出.txt（与 run.sh / checkup.sh 同一份风格：历史批次都留着）
set -u
JH_WIN="C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
JH="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/本机实测输出.txt"
BASE="$LOCALAPPDATA/Temp/javasec2"
OUT_WIN="$BASE/classes"; PATCH_WIN="$BASE/patch"; AG_WIN="$BASE/agent"; CB_WIN="$BASE/codebase"
rm -rf "$BASE"; mkdir -p "$OUT_WIN" "$PATCH_WIN" "$AG_WIN" "$CB_WIN"
cd "$HERE" || exit 1

# 编译失败也要留痕：失败时把 javac 输出写进日志，别让"没有日志"变成唯一的线索
must_javac() {   # $1=输出目录，其余=源文件
  local out="$1"; shift
  if ! "$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$out" "$@" >> "$LOG" 2>&1; then
    echo "########## 编译失败（下面这几行是 javac 原话）##########" >> "$LOG"
    exit 1
  fi
}

must_javac "$OUT_WIN" *.java
must_javac "$PATCH_WIN" patch/VictimA.java
must_javac "$CB_WIN" codebase-src/EvilFactory.java

# 动态附加用的 agent jar（Agent-Class = 运行期入口）
printf 'Agent-Class: SecAttachAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n' \
    > "$AG_WIN/attach-manifest.txt"
"$JH/bin/jar" --create --file "$AG_WIN/attach-agent.jar" --manifest "$AG_WIN/attach-manifest.txt" \
    -C "$OUT_WIN" SecAttachAgent.class || exit 1
# 启动期改写用的 agent jar（Premain-Class = 启动期入口）
printf 'Premain-Class: SecRewriteAgent\nAgent-Class: SecRewriteAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n' \
    > "$AG_WIN/rewrite-manifest.txt"
"$JH/bin/jar" --create --file "$AG_WIN/rewrite-agent.jar" --manifest "$AG_WIN/rewrite-manifest.txt" \
    -C "$OUT_WIN" SecRewriteAgent.class || exit 1

J() { "$JH/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@"; }

{
  echo ""
  echo "=================================================================="
  echo "=== 【深度靶场批次】$(date '+%Y-%m-%d %H:%M:%S')  JDK: $("$JH/bin/java" -version 2>&1 | head -1)"
  echo "=== 四个实验：运行期附加 / 进程内改字节码 / 过滤器进阶 / JNDI 解码路径"
  echo "=== 提醒：只在本机自建靶子上跑，HTTP 只绑 127.0.0.1；不下载、不落地、不对外"
  echo "=================================================================="

  echo ""
  echo "########## 实验 1：运行期附加（目标 JVM 不重启） ##########"
  rm -f "$BASE/target.jfr" "$BASE/target.out"
  J -XX:StartFlightRecording=filename="$BASE/target.jfr",duration=40s -cp "$OUT_WIN" VictimA 26 \
      > "$BASE/target.out" 2>&1 &
  TPID=$!
  sleep 3
  PID=$(grep -oE 'pid = [0-9]+' "$BASE/target.out" | head -1 | grep -oE '[0-9]+')
  echo "-- 目标 pid=$PID；它启动时**没有**任何 agent 参数 --"
  J -cp "$OUT_WIN" SecAttach "$PID" "$AG_WIN/attach-agent.jar" "$PATCH_WIN/VictimA.class"
  sleep 2
  echo "-- 附加之后，目标的启动参数（jcmd VM.command_line）--"
  "$JH/bin/jcmd" "$PID" VM.command_line 2>&1 || true
  echo "-- 附加之后，进程里的类加载器（jcmd VM.class_loaders，前 8 行）--"
  "$JH/bin/jcmd" "$PID" VM.class_loaders 2>&1 | head -8 || true
  wait $TPID
  echo "-- 目标的完整输出（status() 从第几次开始变，就是植入发生的时刻）--"
  cat "$BASE/target.out"
  echo "-- JFR：jdk.JavaAgent 事件（dynamic 字段 = 启动期 false / 运行期 true）--"
  "$JH/bin/jfr" print --events jdk.JavaAgent "$BASE/target.jfr" 2>&1
  echo "-- JFR：相关事件计数 --"
  "$JH/bin/jfr" summary "$BASE/target.jfr" 2>&1 \
      | grep -iE 'JavaAgent|RedefineClasses|ClassRedefinition|ClassDefine|NativeAgent'

  echo ""
  echo "########## 实验 2：进程内改字节码（Class-File API / JEP 484） ##########"
  echo "-- 对照：不带 agent 跑 4 次 --"
  J -cp "$OUT_WIN" VictimA 4
  echo "-- 带 -javaagent：premain 里读**磁盘上原类的字节** → 改写 status() 方法体 → redefineClasses --"
  J "-javaagent:$AG_WIN/rewrite-agent.jar" "-Dvictim.class.file=$OUT_WIN/VictimA.class" \
      -cp "$OUT_WIN" VictimA 4
  echo "-- 磁盘上那两份 class 的 md5（原类与补丁类；运行前后都不应变化）--"
  ( cd "$OUT_WIN" && md5sum VictimA.class )
  ( cd "$PATCH_WIN" && md5sum VictimA.class )

  echo ""
  echo "########## 实验 3：反序列化过滤器进阶 ##########"
  J -cp "$OUT_WIN" SecFilterAdv
  echo ""
  J -cp "$OUT_WIN" SecFilterArray

  echo ""
  echo "########## 实验 4：JNDI —— 走到 Reference 解码那一步 ##########"
  echo "-- 模式 A：不带 --add-exports --"
  J "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi2
  echo "-- 模式 B0：模块名写错（java.naming）—— 报错信息本身会指出正确的模块 --"
  J --add-exports java.naming/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi2
  echo "-- 模式 B：模块名写对（jdk.naming.rmi）--"
  J --add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi2
  echo "-- 模式 C：模式 B 再加 -Dcom.sun.jndi.rmi.object.trustURLCodebase=true（老属性，已不被这条路读取）--"
  J --add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Dcom.sun.jndi.rmi.object.trustURLCodebase=true" \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi2

  echo ""
  echo "-- 模式 D：注册 ObjectFactoryBuilder 之后走 predicate 那条路（默认 filter）--"
  J --add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi3
  echo "-- 模式 E：模式 D 再显式把 RMI 的工厂过滤器放开成 *（等于自己拆掉那道闸）--"
  J --add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Djdk.jndi.rmi.object.factoriesFilter=*" \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi3
  echo "-- 模式 F：模式 E 再放开全局那个（jdk.jndi.object.factoriesFilter）--"
  J --add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
      "-Djdk.jndi.object.factoriesFilter=*" "-Djdk.jndi.rmi.object.factoriesFilter=*" \
      "-Dcodebase.dir=$CB_WIN" -cp "$OUT_WIN" SecJndi3
} | tee -a "$LOG"

rm -rf "$BASE"
