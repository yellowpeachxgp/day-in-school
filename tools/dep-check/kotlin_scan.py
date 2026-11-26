#!/usr/bin/env python3
# kotlin_scan.py — Kotlin/Gradle 源码的词法级扫描器（W0-07a，dep-check 后端）
#
# 用小型状态机识别行注释、嵌套块注释、字符串与字符字面量：
#   - 注释内容一律空白化（换行保留，行号不变）；
#   - 记录字符串/字符字面量区间，用于排除字符串内部的伪匹配。
# 因此 project( ":x" )、跨行 project(...) 都能命中；注释掉或字符串里的
# project()/import 不会误报；import 的 as 别名与行尾注释不影响判定。
#
# 用法：
#   kotlin_scan.py deps <build.gradle.kts>   每行输出一个 project(":a:b") 依赖路径
#   kotlin_scan.py ui-imports <file.kt>      每行输出一条违规 import：行号:类别:原文
import re
import sys


def scan_tokens(text):
    """返回 (comment_stripped_text, string_intervals)。
    comment_stripped_text 与 text 等长，注释内容替换为空格（换行保留）；
    string_intervals 为 (start, end) 半开区间列表，覆盖字符串与字符字面量。"""
    out = list(text)
    intervals = []
    i = 0
    n = len(text)
    block_depth = 0
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if block_depth == 0 and ch == "/" and nxt == "/":
            j = i
            while j < n and text[j] != "\n":
                out[j] = " "
                j += 1
            i = j
            continue
        if ch == "/" and nxt == "*":
            block_depth += 1
            out[i] = " "
            out[i + 1] = " "
            i += 2
            continue
        if block_depth > 0:
            if ch == "*" and nxt == "/":
                block_depth -= 1
                out[i] = " "
                out[i + 1] = " "
                i += 2
            else:
                if ch != "\n":
                    out[i] = " "
                i += 1
            continue
        if ch == '"' and text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            intervals.append((i, end))
            i = end
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    break
                j += 1
            end = min(j + 1, n)
            intervals.append((i, end))
            i = end
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    break
                j += 1
            end = min(j + 1, n)
            intervals.append((i, end))
            i = end
            continue
        i += 1
    return "".join(out), intervals


def inside_intervals(pos, intervals):
    # 扫描器按位置顺序生成区间，线性检查对构建文件规模足够快
    for start, end in intervals:
        if start <= pos < end:
            return True
    return False


DEP_RE = re.compile(
    r'project\s*\(\s*(?:path\s*=\s*)?\s*"((?::[A-Za-z0-9_-]+)+)"\s*\)'
)
IMPORT_RE = re.compile(
    r'^\s*import\s+([A-Za-z0-9_.]+?)(?:\s+as\s+[A-Za-z0-9_]+)?\s*$'
)


def scan_deps(path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    cleaned, intervals = scan_tokens(text)
    for match in DEP_RE.finditer(cleaned):
        # project( 关键字本身落在字符串里时是伪匹配（例如文档字符串中的示例）
        if inside_intervals(match.start(), intervals):
            continue
        print(match.group(1))


def scan_ui_imports(path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    cleaned, intervals = scan_tokens(text)
    offset = 0
    for lineno, line in enumerate(cleaned.split("\n"), start=1):
        line_start = offset
        offset += len(line) + 1
        match = IMPORT_RE.match(line)
        if not match:
            continue
        # import 行落在字符串内部（如测试夹具文本）时不算
        stripped = line.lstrip()
        lead = len(line) - len(stripped)
        if inside_intervals(line_start + lead, intervals):
            continue
        dotted = match.group(1)
        segments = dotted.split(".")
        category = None
        # 任一大写开头段以 Repository/ViewModel 结尾即违规：覆盖
        # import a.b.FooRepository 与嵌套形式 import a.b.FooViewModel.Factory。
        # 小写包名段（如 viewmodel.compose）不算；星号导入无法判定（文档说明）。
        for seg in segments:
            if seg and seg[0].isupper():
                if seg.endswith("Repository"):
                    category = "Repository"
                elif seg.endswith("ViewModel"):
                    category = "ViewModel"
        if category:
            print(f"{lineno}:{category}:import {dotted}")


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("deps", "ui-imports"):
        print("usage: kotlin_scan.py deps|ui-imports <file>", file=sys.stderr)
        return 64
    if sys.argv[1] == "deps":
        scan_deps(sys.argv[2])
    else:
        scan_ui_imports(sys.argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
