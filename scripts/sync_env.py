#!/usr/bin/env python3
"""把 .env.example 的键集合同步到 .env。

同步规则（用户约定）：
1. `.env` 中已存在的键：保留原行，**不覆盖**已有值；
2. `.env.example` 中有、`.env` 中没有的键：追加到 `.env` 末尾（带示例注释说明）；
3. `.env` 中有、`.env.example` 中没有的多余键：删除（连同紧邻上方的注释块）。

说明：
- 只有「未注释的 `KEY=VALUE` 行」才被识别为键；`# KEY=VALUE` 视为普通注释，不参与同步。
- `.env` 中原有的注释、空行、键顺序保持不变，只做「删多余键」和「末尾补缺失键」。
- 文件编码为 UTF-8，自动沿用目标文件已有的换行符（LF / CRLF）。

用法：
    python scripts/sync_env.py              # 直接改写 .env
    python scripts/sync_env.py --dry-run    # 只打印将要发生的变更（不写文件）
    python scripts/sync_env.py --check      # 只检查是否已同步；不同步时退出码为 1
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = PROJECT_ROOT / ".env.example"
DEFAULT_TARGET = PROJECT_ROOT / ".env"

# 匹配未注释的 KEY=VALUE 行（兼容 `export KEY=VALUE` 写法）
_KEY_RE = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_.]*)\s*=")


@dataclass(frozen=True)
class ExampleEntry:
    """`.env.example` 中的一个键及其紧邻上方的注释块。"""

    key: str
    line: str
    comments: tuple[str, ...]


@dataclass
class SyncOutcome:
    """同步结果。content 为最终应写入的文本。"""

    content: str
    added: list[str]
    removed: list[str]

    @property
    def changed(self) -> bool:
        return bool(self.added or self.removed)


def parse_key(line: str) -> str | None:
    """返回该行的键名；注释行 / 空行 / 非键值行返回 None。"""
    if line.lstrip().startswith("#"):
        return None
    match = _KEY_RE.match(line)
    return match.group(1) if match else None


def read_text(path: Path) -> tuple[list[str], str]:
    """读取文本文件，返回 (按行拆分的列表, 换行符)。"""
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    # utf-8-sig：兼容带 BOM 的文件（写回时不带 BOM）
    return raw.decode("utf-8-sig").splitlines(), newline


def load_example(path: Path) -> list[ExampleEntry]:
    """解析示例文件，按出现顺序返回键定义（重复键只取第一次）。"""
    lines, _ = read_text(path)
    entries: list[ExampleEntry] = []
    seen: set[str] = set()
    comments: list[str] = []

    for line in lines:
        if not line.strip():
            comments.clear()
            continue
        if line.lstrip().startswith("#"):
            comments.append(line)
            continue
        key = parse_key(line)
        if key is not None and key not in seen:
            seen.add(key)
            entries.append(ExampleEntry(key=key, line=line, comments=tuple(comments)))
        comments.clear()

    return entries


def _collapse_blank_lines(lines: list[str]) -> list[str]:
    """压缩连续空行为单个空行，并去掉首尾空行。"""
    out: list[str] = []
    for line in lines:
        if not line.strip():
            if not out or not out[-1].strip():
                continue
            out.append("")
        else:
            out.append(line)
    while out and not out[-1].strip():
        out.pop()
    while out and not out[0].strip():
        out.pop(0)
    return out


def sync(target_lines: list[str], example: list[ExampleEntry]) -> SyncOutcome:
    """按同步规则计算 .env 的新内容（纯函数，不读写文件）。"""
    example_keys = [entry.key for entry in example]
    example_key_set = set(example_keys)
    example_by_key = {entry.key: entry for entry in example}

    # ---- 规则 3：删除多余键（连同紧邻上方的注释块） ----
    kept: list[str] = []
    removed: list[str] = []
    for line in target_lines:
        key = parse_key(line)
        if key is not None and key not in example_key_set:
            removed.append(key)
            while kept and kept[-1].lstrip().startswith("#"):
                kept.pop()
            continue
        kept.append(line)

    # ---- 统计目标文件中已存在的键 ----
    present: set[str] = set()
    for line in kept:
        key = parse_key(line)
        if key is not None:
            present.add(key)

    # ---- 规则 1 + 2：已有键保留原值，缺失键追加到末尾 ----
    missing = [key for key in example_keys if key not in present]

    if missing:
        while kept and not kept[-1].strip():
            kept.pop()
        if kept:
            kept.append("")
        kept.append("# ===== 以下键由 sync_env.py 依据 .env.example 自动补齐 =====")
        for key in missing:
            entry = example_by_key[key]
            if entry.comments:
                kept.append("")
                kept.extend(entry.comments)
            kept.append(entry.line.rstrip())

    kept = _collapse_blank_lines(kept)
    content = "\n".join(kept) + "\n" if kept else ""
    return SyncOutcome(content=content, added=missing, removed=removed)


def _print_report(outcome: SyncOutcome, target_path: Path, old_content: str, dry_run: bool) -> None:
    if not outcome.changed:
        print(f"[OK] {target_path} 已与 .env.example 同步，无需变更。")
        return

    verb = "将" if dry_run else "已"
    if outcome.added:
        print(f"{verb}新增 {len(outcome.added)} 个键：")
        for key in outcome.added:
            print(f"  + {key}")
    if outcome.removed:
        print(f"{verb}删除 {len(outcome.removed)} 个多余键：")
        for key in outcome.removed:
            print(f"  - {key}")

    if dry_run:
        print("\n----- 预览变更 -----")
        diff = difflib.unified_diff(
            old_content.splitlines(),
            outcome.content.splitlines(),
            fromfile=str(target_path),
            tofile=f"{target_path} (同步后)",
            lineterm="",
        )
        print("\n".join(diff))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="按 .env.example 同步 .env：已有键不覆盖、缺失键追加、多余键删除。",
    )
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="示例文件（默认 .env.example）")
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET, help="目标文件（默认 .env）")
    parser.add_argument("--dry-run", action="store_true", help="只打印将要发生的变更，不写文件")
    parser.add_argument("--check", action="store_true", help="只检查是否已同步；不同步时退出码为 1")
    args = parser.parse_args(argv)

    source: Path = args.source
    target: Path = args.target

    if not source.is_file():
        print(f"[ERROR] 找不到示例文件：{source}", file=sys.stderr)
        return 2
    if not target.is_file():
        print(f"[ERROR] 找不到目标文件：{target}", file=sys.stderr)
        return 2

    example = load_example(source)
    if not example:
        print(f"[ERROR] 示例文件中未解析到任何键：{source}", file=sys.stderr)
        return 2

    target_lines, newline = read_text(target)
    old_content = newline.join(target_lines) + (newline if target_lines else "")
    outcome = sync(target_lines, example)

    if args.check:
        if outcome.changed:
            print(f"[MISMATCH] {target} 与 {source} 不一致：需新增 {len(outcome.added)} 个、删除 {len(outcome.removed)} 个键。")
            return 1
        print(f"[OK] {target} 已同步。")
        return 0

    _print_report(outcome, target, old_content, args.dry_run)

    if outcome.changed and not args.dry_run:
        if newline != "\n":
            content = outcome.content.replace("\n", newline)
        else:
            content = outcome.content
        target.write_bytes(content.encode("utf-8"))
        print(f"[DONE] 已写入 {target}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
