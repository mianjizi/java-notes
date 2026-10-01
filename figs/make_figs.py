# -*- coding: utf-8 -*-
"""Java/figs 插图生成脚本（2026-10-01）
用法：<装了 matplotlib 的 python> make_figs.py
输出：fig1-时间轴.png、fig2-分层.png、fig3-生态脉络.png（dpi=170）
注意：不要用 Microsoft YaHei 缺失的字形（实测 U+2194 ↔ 会印成豆腐块），改写成「与」。
"""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import os

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
plt.rcParams["axes.unicode_minus"] = False

HERE = os.path.dirname(os.path.abspath(__file__))
DPI = 170

INK = "#1f2937"
MUTED = "#6b7280"
BLUE = "#2563eb"
AMBER = "#b45309"
GREEN = "#047857"
LINE = "#9ca3af"


def box(ax, x, y, w, h, text, fc="#ffffff", ec=BLUE, fs=11, tc=INK, bold=False, radius=0.02):
    ax.add_patch(FancyBboxPatch((x, y), w, h,
                                boxstyle=f"round,pad=0.006,rounding_size={radius}",
                                linewidth=1.2, edgecolor=ec, facecolor=fc, zorder=2))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs,
            color=tc, zorder=3, fontweight="bold" if bold else "normal", linespacing=1.5)


def arrow(ax, p1, p2, color=MUTED, style="-|>", lw=1.2, rad=0.0):
    ax.add_patch(FancyArrowPatch(p1, p2, arrowstyle=style, mutation_scale=14,
                                 linewidth=lw, color=color,
                                 connectionstyle=f"arc3,rad={rad}", zorder=1))


# ---------------------------------------------------------------- fig1 时间轴
def fig1():
    fig, ax = plt.subplots(figsize=(15, 7.2))
    ax.set_xlim(0, 1); ax.set_ylim(0, 1); ax.axis("off")
    ax.text(0.5, 0.955, "Java 三十来年：从机顶盒到虚拟线程", ha="center", va="top",
            fontsize=17, color=INK, fontweight="bold")
    ax.text(0.5, 0.905, "节点为年份与事件；「本机」= 我这台机器上装的这一版（JDK 25.0.4.1 LTS，2026-10-01 实测）",
            ha="center", va="top", fontsize=10, color=MUTED)

    y = 0.5
    ax.add_patch(FancyArrowPatch((0.035, y), (0.975, y), arrowstyle="-|>",
                                 mutation_scale=18, linewidth=1.6, color=LINE, zorder=1))

    # (年份, 事件, 上方/下方)
    marks = [
        ("1991", "Green 项目起步\n（为消费电子做的一门语言）", 1),
        ("1995", "SunWorld 公开\nHotJava 浏览器演示", -1),
        ("1996", "JDK 1.0 发布", 1),
        ("1998", "Java 2 品牌启用\nSE / EE / ME 陆续分家", -1),
        ("2004", "Java 5\n泛型·注解·并发包", 1),
        ("2006", "以 GPL 开源\nOpenJDK 起步", -1),
        ("2010", "Oracle 收购 Sun", 1),
        ("2011", "Java 7\ninvokedynamic", -1),
        ("2014", "Java 8\nLambda 与 Stream", 1),
        ("2017", "Java 9\n模块化·半年一版", -1),
        ("2018", "Java 11 LTS", 1),
        ("2021", "Java 17 LTS\nrecord·密封类", -1),
        ("2023", "Java 21 LTS\n虚拟线程正式", 1),
        ("2025", "Java 25 LTS\n本机这一版", -1),
    ]
    n = len(marks)
    xs = [0.045 + i * (0.925 - 0.045) / (n - 1) for i in range(n)]
    for (year, label, up), x in zip(marks, xs):
        last = year == "2025"
        c = AMBER if last else BLUE
        ax.plot([x], [y], marker="o", markersize=8 if last else 6,
                color=c, zorder=4)
        ax.plot([x, x], [y, y + 0.045 * up], linewidth=1.0, color=LINE, zorder=1)
        ty = y + 0.062 if up > 0 else y - 0.062
        ax.text(x, ty, year, ha="center", va="bottom" if up > 0 else "top",
                fontsize=12.5, color=c, fontweight="bold")
        ax.text(x, ty + (0.035 if up > 0 else -0.035), label,
                ha="center", va="bottom" if up > 0 else "top",
                fontsize=9.2, color=INK if not last else AMBER, linespacing=1.45)

    # 两个阶段分段
    ax.add_patch(FancyBboxPatch((0.04, 0.075), 0.42, 0.075,
                                boxstyle="round,pad=0.006,rounding_size=0.02",
                                linewidth=1.1, edgecolor=MUTED, facecolor="#f9fafb"))
    ax.text(0.25, 0.113, "1991–2017：大版本节奏（2～5 年一版，中间几次延期）",
            ha="center", va="center", fontsize=10.5, color=INK)
    ax.add_patch(FancyBboxPatch((0.54, 0.075), 0.42, 0.075,
                                boxstyle="round,pad=0.006,rounding_size=0.02",
                                linewidth=1.1, edgecolor=GREEN, facecolor="#f9fafb"))
    ax.text(0.75, 0.113, "2017 起：半年一版，每两三年一个 LTS",
            ha="center", va="center", fontsize=10.5, color=GREEN)

    fig.savefig(os.path.join(HERE, "fig1-时间轴.png"), dpi=DPI,
                bbox_inches="tight", facecolor="white")
    plt.close(fig)


# ---------------------------------------------------------------- fig2 分层
def fig2():
    fig, ax = plt.subplots(figsize=(12, 7.4))
    ax.set_xlim(0, 1); ax.set_ylim(0, 1); ax.axis("off")
    ax.text(0.5, 0.965, "Java 平台的分层：一层解决一个问题", ha="center", va="top",
            fontsize=17, color=INK, fontweight="bold")

    layers = [
        (0.775, "你的 .java 源码", "编译单元：类、方法、包声明", BLUE, "#eff6ff"),
        (0.615, "javac 编译 → .class 字节码", "class 文件主版本 69（本机实测）；换 JDK 大版本这个数字就变", AMBER, "#fffbeb"),
        (0.455, "标准类库（模块）", "java.base 导出 58 个包；整机 70 个模块（本机实测）", GREEN, "#ecfdf5"),
        (0.295, "Java 虚拟机（本机：HotSpot，Temurin 25.0.4.1）", "解释 + JIT 编译、GC、线程调度、安全校验", BLUE, "#eff6ff"),
        (0.135, "操作系统与硬件（Windows 11 / amd64）", "JVM 对上层屏蔽差异 —— 「一次编写到处运行」落在这里", MUTED, "#f9fafb"),
    ]
    for y, title, sub, ec, fc in layers:
        box(ax, 0.12, y, 0.76, 0.115, f"{title}\n{sub}", fc=fc, ec=ec, fs=11.5)
    for y1, y2 in zip([0.775, 0.615, 0.455, 0.295], [0.615, 0.455, 0.295, 0.135]):
        arrow(ax, (0.5, y1), (0.5, y2 + 0.115))
    ax.text(0.895, 0.35, "跨平台就落在这里\nJVM 屏蔽下层的差异", ha="left", va="center",
            fontsize=10, color=MUTED, linespacing=1.5)

    fig.savefig(os.path.join(HERE, "fig2-分层.png"), dpi=DPI,
                bbox_inches="tight", facecolor="white")
    plt.close(fig)


# ---------------------------------------------------------------- fig3 生态
def fig3():
    fig, ax = plt.subplots(figsize=(12.5, 8.4))
    ax.set_xlim(0, 1); ax.set_ylim(0, 1); ax.axis("off")
    ax.text(0.5, 0.97, "JVM 写成了什么：一门语言与一个运行时平台", ha="center", va="top",
            fontsize=17, color=INK, fontweight="bold")
    ax.text(0.5, 0.925, "字节码是公共接口 —— 能编译到字节码的语言都能进这个生态",
            ha="center", va="top", fontsize=10.5, color=MUTED)

    box(ax, 0.36, 0.44, 0.28, 0.13, "JVM（HotSpot）\n字节码的执行者", fc="#eff6ff",
        ec=BLUE, fs=13, bold=True)

    branches = [
        (0.055, 0.72, "其它 JVM 语言", "Kotlin · Scala\nGroovy · Clojure", GREEN),
        (0.375, 0.72, "Java 语言本身", "本套笔记学的就是它", BLUE),
        (0.695, 0.72, "构建与工具", "Maven · Gradle · IDE\n测试与静态分析", AMBER),
        (0.055, 0.13, "服务端与企业系统", "Web 后端、消息队列\n搜索引擎", BLUE),
        (0.375, 0.13, "大数据与数据工程", "Hadoop · Spark · Kafka\n（多有 JVM 语言实现）", GREEN),
        (0.695, 0.13, "移动与桌面", "Android 用 ART 跑\nJava 派生的 API", AMBER),
    ]
    for x, y, title, sub, ec in branches:
        box(ax, x, y, 0.25, 0.135, f"{title}\n{sub}", fc="#ffffff", ec=ec, fs=10.8)

    for x in [0.18, 0.5, 0.82]:
        arrow(ax, (x, 0.72), (x, 0.575))
        arrow(ax, (x, 0.265), (x, 0.435))

    fig.savefig(os.path.join(HERE, "fig3-生态脉络.png"), dpi=DPI,
                bbox_inches="tight", facecolor="white")
    plt.close(fig)


if __name__ == "__main__":
    fig1(); fig2(); fig3()
    print("done:", sorted(f for f in os.listdir(HERE) if f.endswith(".png")))
