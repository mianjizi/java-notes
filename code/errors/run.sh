#!/usr/bin/env bash
# 编译期 / 运行期报错实测脚本（2026-10-01 首跑）
# 用法：bash run.sh   → 输出汇总到 本机实测输出.txt
# 本机编码实况（实测结论）：
#   javac 默认按 native.encoding=GBK 输出诊断 -> 提示是 GBK 字节，而源码是 UTF-8，
#   于是「报错行回显的源码」里的中文变成乱码。修法：-J-Dstderr.encoding=UTF-8。
#   （本机 git-bash 没有 iconv，只能让 JVM 自己换编码）
set -u
JH="C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
JH_POSIX="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
OUT_WIN="$LOCALAPPDATA/Temp/javaerr-classes"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/本机实测输出.txt"

mkdir -p "$OUT_WIN"
cd "$HERE" || exit 1

: > "$LOG"
echo "=== 一、可运行的部分（先编译出来）===" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -d "$OUT_WIN" RunErrors.java RunUncaught.java

echo "" >> "$LOG"
echo "=== 二、编译期错误：默认输出（诊断 GBK、源码回显里的中文乱码）===" >> "$LOG"
echo "########## ErrCannotFindSymbol.java ##########" >> "$LOG"
RAW_WIN="${LOCALAPPDATA//\\//}/Temp/javaerr-gbk.raw"
"$JH_POSIX/bin/javac" -encoding UTF-8 -d "$OUT_WIN" ErrCannotFindSymbol.java > "$RAW_WIN" 2>&1
# 本机 git-bash 没有 iconv：借 PowerShell 按代码页 936 解码，再以 UTF-8 追加进日志
powershell -NoProfile -Command "\$t=[Text.Encoding]::GetEncoding(936).GetString([IO.File]::ReadAllBytes('$RAW_WIN')); [Console]::OutputEncoding=[Text.Encoding]::UTF8; Write-Output \$t" >> "$LOG"
rm -f "$RAW_WIN"
echo "（本机 javac 写的是 GBK 字节：同一串字节若按 UTF-8 解读，提示与源码回显会全部变乱码。" >> "$LOG"
echo "  这里借 PowerShell 按代码页 936 解码后才显示正常；对照下面第三节——那是 javac 自己按 UTF-8 写的。）" >> "$LOG"

echo "" >> "$LOG"
echo "=== 三、同一批代码，加 -J-Dstderr.encoding=UTF-8 之后（本套笔记推荐口径）===" >> "$LOG"
for f in ErrMissingReturn ErrCannotFindSymbol ErrIncompatibleTypes ErrNoSemicolon ErrUnreachable ErrCheckedException ErrStaticContext ErrBadFileName; do
  echo "" >> "$LOG"
  echo "########## $f.java ##########" >> "$LOG"
  "$JH_POSIX/bin/javac" -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -d "$OUT_WIN" "$f.java" >> "$LOG" 2>&1
done

echo "" >> "$LOG"
echo "=== 四、换成英文提示（-J-Duser.language=en；编码要一起加，否则回显还是乱码）===" >> "$LOG"
"$JH_POSIX/bin/javac" -J-Duser.language=en -J-Duser.country=US -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -d "$OUT_WIN" ErrCannotFindSymbol.java >> "$LOG" 2>&1

echo "" >> "$LOG"
echo "=== 五、运行期异常（捕获后打印）与未捕获异常（JVM 默认输出）===" >> "$LOG"
"$JH_POSIX/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" RunErrors >> "$LOG" 2>&1
echo "" >> "$LOG"
echo "########## RunUncaught（不捕获，看默认栈）##########" >> "$LOG"
"$JH_POSIX/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" RunUncaught >> "$LOG" 2>&1
echo "（RunUncaught 的退出码：$?）" >> "$LOG"

echo "" >> "$LOG"
echo "=== 六、NPE 提示里的变量名靠 -g（对照上一节的 <local0>）===" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -g -J-Dstderr.encoding=UTF-8 -d "$OUT_WIN" RunUncaught.java
echo "--- 重新用 -g 编译（目标文件里带上 LocalVariableTable）---" >> "$LOG"
"$JH_POSIX/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" RunUncaught >> "$LOG" 2>&1

echo "" >> "$LOG"
echo "########## RunChained（未捕获的异常链，看 Caused by）##########" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -d "$OUT_WIN" RunChained.java
"$JH_POSIX/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT_WIN" RunChained >> "$LOG" 2>&1

echo "" >> "$LOG"
echo "=== 七、警告：默认基本不显示，要 -Xlint 才打开（WarnCases.java，两段都能编译通过）===" >> "$LOG"
echo "--- 默认 javac（注意：几乎什么都不说）---" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -d "$OUT_WIN" WarnCases.java >> "$LOG" 2>&1
echo "（默认编译的退出码：$?，.class 已产出）" >> "$LOG"
echo "--- 加 -Xlint:all ---" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -Xlint:all -d "$OUT_WIN" WarnCases.java >> "$LOG" 2>&1
echo "--- 加 -Werror（把警告当错误，看它会不会挡住构建）---" >> "$LOG"
"$JH_POSIX/bin/javac" -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -Xlint:all -Werror -d "$OUT_WIN" WarnCases.java >> "$LOG" 2>&1
echo "（-Werror 的退出码见上行输出）" >> "$LOG"

rm -rf "$OUT_WIN"
echo "日志已写入：$LOG"
