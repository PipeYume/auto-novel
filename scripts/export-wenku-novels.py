#!/usr/bin/env python3
"""导出所有文库小说的日文标题、中文标题及其日文 EPUB/TXT 文件名。

基本用法：
  试运行（只导出前 10 本）：./scripts/export-wenku-novels.py --dry-run -o wenku-preview.csv
  完整导出：./scripts/export-wenku-novels.py -o wenku-novels.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import tempfile
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import Any


MONGO_RESULT_MARKER = "__WENKU_EXPORT__"
MONGO_QUERY_TEMPLATE = """
const cursor = db.getCollection("wenku-metadata")
  .find({{}}, {{ title: 1, titleZh: 1 }})
  .sort({{ _id: 1 }})
  {limit};
cursor.forEach(novel => {{
  print("{marker}" + JSON.stringify({{
    id: novel._id.toString(),
    titleJa: novel.title ?? "",
    titleZh: novel.titleZh ?? ""
  }}));
}});
""".strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "从 MongoDB 导出所有文库小说的中日文标题，以及各小说目录下的日文 "
            "EPUB/TXT 文件名。每个文件占一行；没有日文文件的小说也会保留一行。"
        )
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("wenku-novels.csv"),
        help="输出 CSV 路径（默认：wenku-novels.csv）",
    )
    parser.add_argument(
        "--files-dir",
        type=Path,
        default=Path("data/files-wenku"),
        help="文库文件根目录（默认：data/files-wenku）",
    )
    parser.add_argument(
        "--mongo-service",
        default="mongo",
        help="docker compose 中的 MongoDB 服务名（默认：mongo）",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="试运行：只导出按 ID 排序后的前 10 本小说",
    )
    return parser.parse_args()


def stream_novels(
    mongo_service: str, *, limit: int | None = None
) -> Iterator[dict[str, Any]]:
    mongo_query = MONGO_QUERY_TEMPLATE.format(
        limit=f".limit({limit})" if limit is not None else "",
        marker=MONGO_RESULT_MARKER,
    )
    command = [
        "docker",
        "compose",
        "exec",
        "-T",
        mongo_service,
        "mongosh",
        "--quiet",
        "main",
        "--eval",
        mongo_query,
    ]
    # stderr 使用临时文件，避免 PIPE 缓冲区写满后 mongosh 与父进程互相等待。
    stderr_file = tempfile.TemporaryFile(mode="w+", encoding="utf-8")
    try:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=stderr_file,
            text=True,
        )
    except FileNotFoundError as error:
        stderr_file.close()
        raise RuntimeError("找不到 docker，请先安装 Docker 并确保它在 PATH 中") from error
    except OSError:
        stderr_file.close()
        raise

    assert process.stdout is not None
    try:
        for line in process.stdout:
            if not line.startswith(MONGO_RESULT_MARKER):
                continue
            try:
                novel = json.loads(line[len(MONGO_RESULT_MARKER) :])
            except json.JSONDecodeError as error:
                process.terminate()
                raise RuntimeError("mongosh 返回了无效的 JSON") from error
            if not isinstance(novel, dict):
                process.terminate()
                raise RuntimeError("mongosh 返回的数据不是小说对象")
            yield novel

        return_code = process.wait()
        if return_code != 0:
            stderr_file.seek(0)
            detail = stderr_file.read().strip() or f"mongosh 退出码 {return_code}"
            raise RuntimeError(f"读取 MongoDB 失败：{detail}")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait()
        stderr_file.close()


def list_japanese_files(files_dir: Path, novel_id: str) -> list[str]:
    novel_dir = files_dir / novel_id
    if not novel_dir.is_dir():
        return []

    filenames = []
    for path in novel_dir.iterdir():
        if not path.is_file() or path.suffix.lower() not in {".epub", ".txt"}:
            continue
        # 上传为日文源文件时，服务端会生成“原文件名.unpack”目录。
        if (novel_dir / f"{path.name}.unpack").is_dir():
            filenames.append(path.name)
    return sorted(filenames, key=lambda name: (name.casefold(), name))


def write_csv(
    output: Path, files_dir: Path, novels: Iterable[dict[str, Any]]
) -> tuple[int, int]:
    output.parent.mkdir(parents=True, exist_ok=True)
    novel_count = 0
    file_count = 0
    with output.open("w", encoding="utf-8-sig", newline="") as csv_file:
        writer = csv.writer(csv_file)
        writer.writerow(["novel_id", "title_ja", "title_zh", "filename_ja"])
        for novel in novels:
            novel_count += 1
            novel_id = str(novel.get("id", ""))
            base_columns = [
                novel_id,
                str(novel.get("titleJa", "")),
                str(novel.get("titleZh", "")),
            ]
            filenames = list_japanese_files(files_dir, novel_id)
            if filenames:
                for filename in filenames:
                    writer.writerow([*base_columns, filename])
                    file_count += 1
            else:
                writer.writerow([*base_columns, ""])
    return novel_count, file_count


def main() -> int:
    args = parse_args()
    try:
        novels = stream_novels(args.mongo_service, limit=10 if args.dry_run else None)
        novel_count, file_count = write_csv(args.output, args.files_dir, novels)
    except (OSError, RuntimeError) as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1

    mode = "试运行完成，已导出" if args.dry_run else "已导出"
    print(
        f"{mode} {novel_count} 部小说、{file_count} 个日文文件到 {args.output}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
