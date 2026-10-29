#!/usr/bin/env python3
"""
BenStore 離線授權（License）簽發工具

用 RSA 簽名做到「無法偽造、可綁定身份、可設到期日」的授權，
而且**全程不需要後端、不需要 OAuth、不需要聯網驗證**。

原理：
  - 你生成一組 RSA-2048 金鑰對
  - 私鑰留在你手上（絕對不要進 repo）
  - 公鑰內嵌進 App（build.gradle.kts）
  - 要授權給誰，就用私鑰簽一張 license（JSON + 簽名）
  - App 用內嵌公鑰驗簽 → 驗過就解鎖對應分級

因為簽名無法偽造，這比「存取碼」強很多：
  存取碼是一串字串，拿到就能轉傳；
  license 雖然也能轉傳，但可以綁定 email 且能設到期日。

用法：
  # 1. 首次：生成金鑰對（只需做一次）
  python3 tools/license_key.py init

  # 2. 把印出的公鑰貼進 app/build.gradle.kts 的 LICENSE_PUBKEY
  #    （腳本會直接印出那一行，複製貼上即可）

  # 3. 簽發 license 給使用者
  python3 tools/license_key.py issue --email you@example.com --tiers beta --days 365

  # 4. 把 license 字串交給使用者，他在 BenStore 設定頁貼上
"""

import argparse
import base64
import json
import os
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

HERE = os.path.dirname(os.path.abspath(__file__))
PRIVATE_KEY = os.path.join(HERE, "license_private.pem")
PUBLIC_KEY = os.path.join(HERE, "license_public.pem")


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def b64url_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


def gen_keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    priv = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return priv, pub


def cmd_init():
    if os.path.exists(PRIVATE_KEY):
        print(f"私鑰已存在：{PRIVATE_KEY}")
        print("若確定要重新產生，請先手動刪掉它（舊的 license 會全部失效）。")
        if not sys.argv.__contains__("--force"):
            # 仍允許續用既有公鑰，直接印出來
            pub = open(PUBLIC_KEY, "rb").read()
            print()
            print("現有公鑰（Base64）：")
            print(b64url(pub))
            return

    priv, pub_der = gen_keypair()
    pub_pem = serialization.load_der_public_key(pub_der).public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    with open(PRIVATE_KEY, "wb") as f:
        f.write(priv)
    os.chmod(PRIVATE_KEY, 0o600)
    with open(PUBLIC_KEY, "wb") as f:
        f.write(pub_pem)

    print("=" * 60)
    print("已生成 RSA-2048 金鑰對")
    print(f"  私鑰：{PRIVATE_KEY}   ← 絕對不要上傳到 repo")
    print(f"  公鑰：{PUBLIC_KEY}    ← 這個要內嵌進 App")
    print("=" * 60)
    print()
    print("請把下面這行貼進 app/build.gradle.kts：")
    print()
    print(f'buildConfigField("String", "LICENSE_PUBKEY", "{b64url(pub_der)}")')
    print()
    print("提醒：確認 .gitignore 已包含 tools/license_private.pem")


def cmd_issue(email, tiers, days, note):
    if not os.path.exists(PRIVATE_KEY):
        raise SystemExit(f"找不到私鑰，請先跑：python3 tools/license_key.py init")

    key = serialization.load_pem_private_key(
        open(PRIVATE_KEY, "rb").read(), password=None
    )

    now = int(time.time())
    payload = {
        "email": email.strip(),
        "tiers": [t.strip().lower() for t in tiers if t.strip()],
        "iat": now,
        "exp": now + days * 86400,
    }
    if note:
        payload["note"] = note

    payload_bytes = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode()
    sig = key.sign(payload_bytes, padding.PKCS1v15(), hashes.SHA256())

    license_str = f"{b64url(payload_bytes)}.{b64url(sig)}"

    print("=" * 60)
    print("License 已簽發")
    print("=" * 60)
    print(f"對象 email : {payload['email'] or '(未指定)'}")
    print(f"分級 tiers : {', '.join(payload['tiers'])}")
    print(f"有效期限   : {days} 天（到 {time.strftime('%Y-%m-%d', time.localtime(payload['exp']))}）")
    print("-" * 60)
    print(license_str)
    print("-" * 60)
    print()
    print("把上面那串交給使用者，他在 BenStore → 設定 → 授權碼 貼上即可。")
    return license_str


def cmd_show(license_str):
    """解析一張 license，檢查內容（不含驗簽，方便你確認自己簽了什麼）"""
    try:
        p, s = license_str.strip().split(".")
    except ValueError:
        raise SystemExit("格式不對，應該是 payload.signature")
    payload = json.loads(b64url_decode(p))
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    exp = payload.get("exp", 0)
    left = exp - int(time.time())
    print()
    print("狀態:", "已過期" if left <= 0 else f"還有 {left // 86400} 天")
    print("簽名長度:", len(b64url_decode(s)), "bytes")


def main():
    ap = argparse.ArgumentParser(description="BenStore 離線授權簽發工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("init", help="生成金鑰對（只需一次）")

    p_issue = sub.add_parser("issue", help="簽發一張 license")
    p_issue.add_argument("--email", default="", help="綁定的 email（可留空）")
    p_issue.add_argument("--tiers", default="beta", help="分級，逗號分隔")
    p_issue.add_argument("--days", type=int, default=365, help="有效天數")
    p_issue.add_argument("--note", default="", help="備註")

    p_show = sub.add_parser("show", help="解析一張 license 的內容")
    p_show.add_argument("license")

    args = ap.parse_args()

    if args.cmd == "init":
        cmd_init()
    elif args.cmd == "issue":
        cmd_issue(args.email, args.tiers.split(","), args.days, args.note)
    elif args.cmd == "show":
        cmd_show(args.license)


if __name__ == "__main__":
    main()
