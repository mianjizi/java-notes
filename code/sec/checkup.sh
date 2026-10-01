#!/usr/bin/env bash
# Java 安全向「体检」脚本（第 12 篇 §5 用）：只用 JDK 自带工具，对 code/sec 下的类做一次静态+运行期清点。
# 用法：在 git-bash 里  bash checkup.sh
# 说明：只读扫描与本地自建靶子；不发网络请求、不接触任何第三方系统。
# 输出**追加**到 本机实测输出.txt（与 run.sh 同一份日志，历史批次都留着）
set -u
JH_WIN="C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
JH="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/本机实测输出.txt"
OUT_WIN="$LOCALAPPDATA/Temp/javasec-checkup-classes"
OUT="$LOCALAPPDATA/Temp/javasec-checkup-classes"
PATCH_WIN="$LOCALAPPDATA/Temp/javasec-checkup-patch"
AG="$LOCALAPPDATA/Temp/javasec-checkup-agent"
rm -rf "$OUT" "$PATCH_WIN" "$AG"; mkdir -p "$OUT" "$PATCH_WIN" "$AG"
cd "$HERE" || exit 1

{
  echo ""
  echo "=================================================================="
  echo "=== 【体检批次】$(date '+%Y-%m-%d %H:%M:%S')  JDK: $("$JH/bin/java" -version 2>&1 | head -1)"
  echo "=== 工具：javac -Xlint:all / jdeprscan / jdeps / javap / jcmd / jfr   （脚本 checkup.sh）"
  echo "=== 提醒：只扫本目录自建靶子，只读；不下载、不落地、不对外"
  echo "=================================================================="

  echo ""
  echo "########## 体检 1：javac -Xlint:all（编译期能替你抓到的可疑模式） ##########"
  "$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -Xlint:all -d "$OUT_WIN" *.java 2>&1

  echo ""
  echo "########## 体检 2：jdeprscan --for-removal（扫「已标记待删除」的 API） ##########"
  "$JH/bin/jdeprscan" -J-Dstdout.encoding=UTF-8 --release 25 --for-removal --class-path "$OUT_WIN" "$OUT_WIN" 2>&1

  echo ""
  echo "########## 体检 3：jdeps --jdk-internals（查有没有静态引用 JDK 内部 API） ##########"
  "$JH/bin/jdeps" -J-Dstdout.encoding=UTF-8 --jdk-internals -cp "$OUT_WIN" \
      "$OUT_WIN/SecBoundary.class" "$OUT_WIN/SecJndi.class" \
      "$OUT_WIN/SecClassLoader.class" "$OUT_WIN/SecAgent.class" 2>&1

  echo ""
  echo "########## 体检 4：jdeps -summary（每个类依赖哪些模块——攻击面就是这些面） ##########"
  "$JH/bin/jdeps" -J-Dstdout.encoding=UTF-8 -summary "$OUT_WIN"/*.class 2>&1 | sort -u

  echo ""
  echo "########## 体检 5：javap 找「反序列化钩子」（readObject/readResolve/writeReplace） ##########"
  for c in 'SecDeserBasic$WithHook' 'SecDeserBasic$Cmp' 'SecDeserBasic$Key'; do
    echo "-- $c --"
    "$JH/bin/javap" -J-Dstdout.encoding=UTF-8 -p "$OUT_WIN/$c.class" 2>&1
  done

  echo ""
  echo "########## 体检 6：jcmd 看一个运行中的 JVM（启动参数 / 属性 / 类直方图） ##########"
  mkdir -p "$OUT"
  cat > "$OUT/Sleeper.java" <<'JAVA'
public class Sleeper {
  public static void main(String[] a) throws Exception {
    System.out.println("pid=" + ProcessHandle.current().pid());
    Thread.sleep(20000);
  }
}
JAVA
  "$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$OUT_WIN" "$OUT/Sleeper.java" || exit 1
  "$JH/bin/java" -Dstdout.encoding=UTF-8 -Djdk.serialFilter='java.base/*;!*' \
      -cp "$OUT_WIN" Sleeper > "$OUT/sleeper.out" 2>&1 &
  SLEEP_PID=$!
  sleep 3
  JPID=$(grep -oE 'pid=[0-9]+' "$OUT/sleeper.out" | head -1 | cut -d= -f2)
  echo "-- jcmd -l --"
  "$JH/bin/jcmd" -l 2>&1
  echo "-- jcmd $JPID VM.command_line --"
  "$JH/bin/jcmd" "$JPID" VM.command_line 2>&1
  echo "-- jcmd $JPID VM.system_properties | grep 安全开关 --"
  "$JH/bin/jcmd" "$JPID" VM.system_properties 2>&1 | grep -iE 'serialFilter|trustURLCodebase|javaagent' || echo "(无匹配)"
  echo "-- jcmd $JPID GC.class_histogram（前 6 行）--"
  "$JH/bin/jcmd" "$JPID" GC.class_histogram 2>&1 | head -6
  kill "$SLEEP_PID" 2>/dev/null
  wait "$SLEEP_PID" 2>/dev/null

  echo ""
  echo "########## 体检 7：jfr 记录运行期改类（内存马/热部署会留下什么事件） ##########"
  "$JH/bin/javac" -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$PATCH_WIN" patch/Victim.java || exit 1
  printf 'Premain-Class: SecAgent\nAgent-Class: SecAgent\nCan-Redefine-Classes: true\nCan-Retransform-Classes: true\n' > "$AG/manifest.txt"
  "$JH/bin/jar" --create --file "$AG/agent.jar" --manifest "$AG/manifest.txt" -C "$OUT_WIN" SecAgent.class || exit 1
  rm -f "$OUT/agent.jfr"
  "$JH/bin/java" -Dstdout.encoding=UTF-8 \
      "-javaagent:$AG/agent.jar" "-Dpatched.class.file=$PATCH_WIN/Victim.class" \
      -XX:StartFlightRecording=filename="$OUT/agent.jfr",duration=12s -cp "$OUT_WIN" Victim 2>&1 | tail -4
  sleep 1
  echo "-- jfr print --events jdk.JavaAgent --"
  "$JH/bin/jfr" print --events jdk.JavaAgent "$OUT/agent.jfr" 2>&1
  echo "-- 相关事件计数 --"
  "$JH/bin/jfr" summary "$OUT/agent.jfr" 2>&1 | grep -iE 'JavaAgent|ClassRedefinition|RedefineClasses|ClassDefine|ModuleExport'
  echo "-- 本 JDK 里与 Java 安全相关的 JFR 事件类型 --"
  "$JH/bin/jfr" metadata "$OUT/agent.jfr" 2>&1 \
    | grep -oE '@Name\("jdk\.[A-Za-z0-9_]+"' | sed 's/@Name("//;s/"//' | sort -u \
    | grep -iE 'Deserialization|JavaAgent|NativeAgent|ClassDefine|ClassLoad|Redefinition|RedefineClasses|RetransformClasses|InitialSecurityProperty|SecurityPropertyModification|ModuleExport|ModuleRequire|InitialEnvironmentVariable'

  echo ""
  echo "########## 体检 8：jlink 的最小化思路（能裁掉哪些模块 = 少掉哪些攻击面） ##########"
  echo "-- 本机 JDK 的模块总数 --"
  "$JH/bin/java" --list-modules 2>&1 | wc -l
  echo "-- 只含 java.base 的最小镜像（体积在下面一行）--"
  rm -rf "$OUT/rt-min"
  "$JH/bin/jlink" --add-modules java.base --output "$OUT/rt-min" 2>&1 && du -sh "$OUT/rt-min" 2>/dev/null | cut -f1
  echo "-- 被裁掉的诊断模块举例（要留就得显式 --add-modules）--"
  "$JH/bin/jlink" --list-modules 2>/dev/null | grep -E '^jdk\.(jcmd|jfr|jconsole)$' || echo "(本机 jlink --list-modules 无此类输出)"
} | tee -a "$LOG"

rm -rf "$OUT" "$PATCH_WIN" "$AG"
