#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提交前密钥/隐私扫描。

用法：
    python tools/check-secrets.py            # 扫描 git 暂存区（pre-commit 用）
    python tools/check-secrets.py --all      # 扫描工作区全部受版本控制的文件

命中任意一条即以退出码 1 中止提交。

设计取舍：本类项目（Forge mod）最容易泄露的不是 API Key，而是
  (1) 发布到 CurseForge / Modrinth / Maven 的用户名与 token，
  (2) 本机绝对路径（含 Windows 用户名），
所以「按字段名扫」比「按值的格式扫」更重要 —— 后者的前缀规则很容易漏。
"""

import argparse
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- 模式表
# (名称, 正则, 说明)
PATTERNS = [
    # ---- 通用云服务 / AI key ----
    ("OpenAI / 类 OpenAI", r"\bsk-[A-Za-z0-9_\-]{18,}"),
    ("讯飞 WebSocket", r"\bsk-ws-[A-Za-z0-9._\-]{18,}"),
    ("MiniMax", r"\bch-[a-z]{3}-[A-Za-z0-9_\-]{24,}"),
    ("智谱 GLM", r"\b[0-9a-f]{32}\.[A-Za-z0-9]{16}\b"),
    ("GitHub token", r"\bgh[pousr]_[A-Za-z0-9]{30,}"),
    ("Google API key", r"\bAIza[0-9A-Za-z_\-]{30,}"),
    ("AWS Access Key", r"\bAKIA[0-9A-Z]{16}\b"),
    ("Slack token", r"\bxox[baprs]-[A-Za-z0-9\-]{10,}"),
    ("JWT", r"\beyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\."),
    ("PEM 私钥", r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),

    # ---- 敏感字段名（值非空即报）----
    # 覆盖：api_key / secret_key / app_key / client_secret / password / token ...
    ("非空凭据字段", r"""(?ix)
        (?:api[_-]?key|secret[_-]?(?:key|id)|app[_-]?(?:key|secret|id)|
           access[_-]?token|auth[_-]?token|refresh[_-]?token|
           client[_-]?(?:secret|id)|password|passwd|private[_-]?key|
           (?:curseforge|modrinth|maven|nexus|artifactory)[_-]?(?:token|key|password|user)
        )\s*[:=]\s*
        (?!["']?\s*(?:""|''|null|none|changeme|your[_-]?|xxx|<))
        ["']?[A-Za-z0-9_\-\.\+/]{12,}["']?
    """),

    # ---- 本机路径（泄露用户名 + 对他人无用）----
    ("本机用户目录", r"[A-Za-z]:[\\/]{1,2}Users[\\/]{1,2}[\w.\-]+"),
    ("类 Unix 用户目录", r"/home/[\w.\-]+/|/Users/[\w.\-]+/"),
]

# 逐行跳过：这些是自带占位符的文件，不必报
SKIP_FILES = {
    ".gitignore",
    "tools/check-secrets.py",
    ".githooks/pre-commit",
    "LICENSE.txt",
    "CREDITS.txt",
    "changelog.txt",
    "gradlew",
    "gradlew.bat",
}

BINARY_EXT = {
    ".jar", ".zip", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".ogg",
    ".wav", ".mp3", ".class", ".ttf", ".otf", ".bin", ".nbt", ".dat",
}

MAX_BYTES = 2 * 1024 * 1024  # 单文件超过 2MB 不扫（都不是文本配置）


def git(*args):
    out = subprocess.run(
        ["git"] + list(args),
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False,
    )
    return out.stdout.decode("utf-8", "replace")


def staged_files():
    names = git("diff", "--cached", "--name-only", "--diff-filter=ACM")
    return [n for n in names.splitlines() if n.strip()]


def tracked_files():
    names = git("ls-files")
    return [n for n in names.splitlines() if n.strip()]


def repo_root():
    return git("rev-parse", "--show-toplevel").strip() or os.getcwd()


def scan(paths, root):
    hits = []
    for rel in paths:
        rel_norm = rel.replace("\\", "/")
        if rel_norm in SKIP_FILES:
            continue
        ext = os.path.splitext(rel_norm)[1].lower()
        if ext in BINARY_EXT:
            continue
        full = os.path.join(root, rel)
        try:
            size = os.path.getsize(full)
        except OSError:
            continue
        if size > MAX_BYTES or size == 0:
            continue
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()
        except OSError:
            continue

        for lineno, line in enumerate(lines, 1):
            # 跳过明显的注释行里的示例值
            for name, pattern in PATTERNS:
                for m in re.finditer(pattern, line):
                    snippet = m.group(0)
                    # 脱敏显示：只露出前 6 位
                    shown = snippet if len(snippet) <= 8 else snippet[:6] + "…"
                    hits.append((rel_norm, lineno, name, shown))
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true",
                    help="扫描全部受版本控制的文件（默认只扫暂存区）")
    args = ap.parse_args()

    root = repo_root()
    files = tracked_files() if args.all else staged_files()

    if not files:
        print("[check-secrets] 没有待检查的文件。")
        return 0

    hits = scan(files, root)

    if not hits:
        print(f"[check-secrets] 已检查 {len(files)} 个文件，未发现密钥或本机路径。")
        return 0

    print("=" * 66)
    print("[check-secrets] 发现疑似敏感内容，已中止：")
    print("=" * 66)
    for rel, lineno, name, shown in hits:
        print(f"  {rel}:{lineno}  [{name}]  {shown}")
    print()
    print("如果确实是误报（例如文档里的占位符），请改写该行后再提交；")
    print("如果确实是密钥，请立刻把它从文件里移除 —— 并且，"
          "一旦已经推到 GitHub，删文件是没用的，必须去平台把该密钥作废重发。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
