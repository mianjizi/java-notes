#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Java/ 目录自检脚本（只读，不改任何文件）。

用途：每次改动 Java/ 下的笔记后跑一遍，四类问题一次查完。
    1. 内链完整性：所有 [[目标]] 能不能在本库里解析到文件；同名文件要写全路径式内链
    2. 外部链接白名单：只允许 docs.oracle.com / openjdk.org / dev.java / jcp.org /
       adoptium.net / mitre.org / cwe.mitre.org / nvd.nist.gov / owasp.org / cve.org /
       wikipedia.org / github.com（github 仅官方仓库页）
    3. 禁用写法：版本行情式断言（「截至 20xx」「目前主流」…）
    4. 每篇结构四件套：练习 / 自我检查清单 / 出处 / 依据说明

用法：
    py -3.11 Java/code/audit.py            # 在库根跑
    py -3.11 audit.py --vault D:/Vault     # 显式指定库根
"""

import argparse
import re
import sys
from pathlib import Path

URL_WHITELIST = {
    "docs.oracle.com", "openjdk.org", "dev.java", "jcp.org", "adoptium.net",
    "mitre.org", "attack.mitre.org", "cwe.mitre.org", "nvd.nist.gov",
    "owasp.org", "cve.org", "wikipedia.org", "zh.wikipedia.org", "en.wikipedia.org",
    "github.com", "www.oracle.com", "junit.org", "site.mockito.org",
    "maven.apache.org", "docs.gradle.org", "logging.apache.org", "h2database.com",
    "jakarta.ee", "spring.io", "javadoc.io", "www.runoob.com", "inside.java",
    "wiki.openjdk.org", "zh.cppreference.com", "liaoxuefeng.com",
    "www.baeldung.com", "baeldung.com", "jenkov.com", "www.h2database.com",
    "apache.org", "logging.apache.org", "cve.org", "nvd.nist.gov", "wikipedia.org",
}

# XML 命名空间标识：长得像 URL，但**不是**可访问链接，是规范标识符（写 pom / jar 描述符必备），
# 自查时一律跳过——删掉它们 Maven 会报 not well-formed。
NAMESPACE_MARKERS = (
    "/POM/4.0.0", "/2001/XMLSchema", "/2001/XMLSchema-instance",
    "/1999/XSL/Transform", "/2001/xml-events", "/ns/javaee", "/ns/jakartaee",
    "xmlns.jcp.org", "java.sun.com/xml/ns",
)

IMAGE_EXT = (".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp")

# 版本行情式断言：这些写法在整套材料里一律不用（版本相关一律给 JEP 编号或写明「本机这套上是这样」）
BANNED_PATTERNS = [
    r"截至\s*20\d\d",
    r"目前主流",
    r"最新版本是",
    r"建议安装\s*JDK\s*\d",
    r"业界公认",
    r"一定是最好",
]

STRUCTURE_KEYS = ["## 练习", "## 自我检查清单", "## 出处", "## 依据说明"]



def domain_ok(dom: str) -> bool:
    """根域或任意子域都在白名单内：docs.oracle.com / services.nvd.nist.gov / cheatsheetseries.owasp.org 都算过。"""
    dom = dom.split(":")[0].lower()
    if dom in URL_WHITELIST:
        return True
    return any(dom.endswith("." + root) for root in URL_WHITELIST if root.count(".") >= 1)


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    # 默认库根按脚本自身位置推导（本文件在 <库根>/Java/code/ 下），避免把本机绝对路径写死进脚本
    ap.add_argument("--vault", default=str(Path(__file__).resolve().parents[2]), help="库根目录")
    args = ap.parse_args()
    vault = Path(args.vault)
    java = vault / "Java"
    if not java.is_dir():
        print(f"找不到 {java}")
        return 2

    mds = sorted(java.glob("*.md"))
    problems: list[str] = []

    # 全库文件名索引（用来判断内链目标是否存在、是否歧义）
    index: dict[str, list[Path]] = {}
    for f in vault.rglob("*.md"):
        index.setdefault(f.stem, []).append(f)

    print(f"=== Java/ 自检（{len(mds)} 篇） ===")
    for f in mds:
        text = read(f)
        lines = text.split("\n")
        name = f.name

        # 1) 内链（区分笔记内链与图片嵌入：图片查文件存在性）
        for m in re.finditer(r"(!?)\[\[([^\]\|]+)(?:\|[^\]]+)?\]\]", text):
            is_embed, target = m.group(1) == "!", m.group(2).strip()
            if target.lower().endswith(IMAGE_EXT):
                if not (java / target).exists() and not (vault / target).exists():
                    problems.append(f"[缺图片] {name}: {target} 文件不存在")
                continue
            base = re.sub(r"\.md$", "", target.split("/")[-1])
            if base not in index:
                problems.append(f"[断链] {name}: [[{target}]] 在库内找不到同名文件")
            elif len(index[base]) > 1 and "/" not in target:
                others = [str(x.relative_to(vault)) for x in index[base] if x.parent != java]
                if others:
                    problems.append(
                        f"[歧义内链] {name}: [[{target}]] 全库有 {len(index[base])} 个同名文件"
                        f"（另有 {others[0]}），本区应写路径式内链"
                    )

        # 2) 外链白名单（跳过 XML 命名空间标识；按「根域 + 子域」匹配）
        for u in set(re.findall(r"https?://[^\s\)\|<>\"'`,，。；;]+", text)):
            if any(mk in u for mk in NAMESPACE_MARKERS):
                continue
            dom = re.sub(r"^https?://", "", u).split("/")[0].lower()
            if not domain_ok(dom):
                problems.append(f"[越界外链] {name}: {u}")

        # 3) 禁用写法
        for pat in BANNED_PATTERNS:
            for i, ln in enumerate(lines, 1):
                if re.search(pat, ln):
                    problems.append(f"[禁用写法] {name}:{i} 命中 /{pat}/ → {ln.strip()[:70]}")

        # 4) 结构四件套（00 索引与速查篇按需豁免）
        if not name.startswith("00-"):
            for key in STRUCTURE_KEYS:
                if key not in text:
                    problems.append(f"[缺结构] {name}: 没有 `{key}`")

        print(f"  {name:60s} {len(lines):>4} 行")

    print(f"\n=== 结论：{len(mds)} 篇 / {sum(len(read(f).split(chr(10))) for f in mds)} 行 ===")
    if problems:
        print(f"\n发现 {len(problems)} 处问题：")
        for p in problems:
            print("  - " + p)
        return 1
    print("未发现问题：内链全通、外链均在白名单内、无禁用写法、结构四件套齐。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
