#!/usr/bin/env python3
"""
BenStore 資料源產生器

從 source.json（你手寫的應用描述）產生 repo.json，
自動補上每個 APK 的 size 與 sha256 —— 這兩欄手填最容易錯。

用法：
    python3 tools/build_repo.py                     # 讀 repo/source.json → 寫 repo/repo.json
    python3 tools/build_repo.py my.json out.json    # 指定輸入輸出

source.json 格式（與 repo.json 相同，但 size / sha256 可省略）：
{
  "meta": { "name": "BenStore" },
  "apps": [
    {
      "packageName": "com.example.app",
      "name": "範例",
      "icon": "https://.../icon.png",
      "versions": [
        { "versionName": "1.0", "versionCode": 10, "url": "https://.../app.apk" }
      ]
    }
  ]
}

url 可以是：
  - https://...    → 腳本會下載後計算 size 與 sha256
  - 本機檔案路徑    → 直接讀檔計算（適合先放好 APK 再一起上傳）
"""

import hashlib
import json
import os
import sys
import urllib.request

CHUNK = 1 << 20


def sha256_and_size_of_file(path: str):
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
            size += len(b)
    return h.hexdigest(), size


def sha256_and_size_of_url(url: str):
    h = hashlib.sha256()
    size = 0
    req = urllib.request.Request(url, headers={"User-Agent": "BenStore-repo-builder"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        while True:
            b = resp.read(CHUNK)
            if not b:
                break
            h.update(b)
            size += len(b)
    return h.hexdigest(), size


def resolve(url: str, base_dir: str, cwd: str):
    """回傳 (sha256, size, 最終使用的 url)"""
    if url.startswith("http://") or url.startswith("https://"):
        s, n = sha256_and_size_of_url(url)
        return s, n, url
    # 本機檔案：先試工作目錄，再試 source.json 所在目錄
    cands = [url if os.path.isabs(url) else os.path.join(cwd, url)]
    if not os.path.isabs(url):
        cands.append(os.path.join(base_dir, url))
    p = next((c for c in cands if os.path.exists(c)), None)
    if p is None:
        raise SystemExit(f"找不到檔案：{url}（試過 {cands}）")
    s, n = sha256_and_size_of_file(p)
    return s, n, url


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join("repo", "source.json")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join("repo", "repo.json")
    # 本機路徑優先用工作目錄解析，找不到再退回 source.json 所在目錄
    cwd = os.path.abspath(os.getcwd())
    base_dir = os.path.dirname(os.path.abspath(src))

    with open(src, encoding="utf-8") as f:
        data = json.load(f)

    import time
    meta = data.setdefault("meta", {})
    meta.setdefault("name", "BenStore")
    meta["updated"] = int(time.time() * 1000)

    total = 0
    for app in data.get("apps", []):
        if not app.get("packageName"):
            raise SystemExit(f"有應用缺少 packageName：{app.get('name', '?')}")
        for v in app.get("versions", []):
            url = v.get("url")
            if not url:
                raise SystemExit(f"{app['packageName']} 的某個版本缺少 url")
            # 沒填或填 0 才重算，讓你可以手動指定
            if not v.get("sha256") or not v.get("size"):
                print(f"  計算 {app['packageName']} {v.get('versionName', '')} …")
                s, n, _ = resolve(url, base_dir, cwd)
                v["sha256"] = s
                v["size"] = n
            v.setdefault("minSdk", 26)
            total += 1

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"完成：{out}")
    print(f"  應用 {len(data.get('apps', []))} 個、版本 {total} 個")


if __name__ == "__main__":
    main()
