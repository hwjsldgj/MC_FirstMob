#!/usr/bin/env python3
"""下载冒烟测试用的 Carpet（假玩家支持），放进 run/mods。

只用于测试环境，不进入模组依赖，也不会被打包进发布 jar。

用法：
    python3 ci/fetch_carpet.py [--dest run/mods]
"""
import argparse
import json
import os
import sys
import urllib.request

API = (
    "https://api.modrinth.com/v2/project/carpet/version"
    "?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D"
)
USER_AGENT = "hush-ci/0.1 (smoke test harness)"


def http_json(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url: str, path: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=180) as response, open(path, "wb") as handle:
        handle.write(response.read())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dest", default="run/mods")
    args = parser.parse_args()

    versions = http_json(API)
    if not versions:
        print("carpet: no 1.21.1 build found", file=sys.stderr)
        return 1
    version = versions[0]
    file_info = version["files"][0]
    os.makedirs(args.dest, exist_ok=True)
    target = os.path.join(args.dest, file_info["filename"])
    if os.path.exists(target) and os.path.getsize(target) == file_info["size"]:
        print(f"carpet: cached {target}")
        return 0
    download(file_info["url"], target)
    size = os.path.getsize(target)
    if size != file_info["size"]:
        print(f"carpet: size mismatch {size} != {file_info['size']}", file=sys.stderr)
        return 1
    print(f"carpet: {version['version_number']} -> {target} ({size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
