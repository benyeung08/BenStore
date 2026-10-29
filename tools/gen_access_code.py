#!/usr/bin/env python3
"""
BenStore 存取碼產生器

產生一組隨機存取碼，並算出它的 SHA-256 —— 你要放進 repo.json 的是**雜湊**，
不是碼本身。碼本身另外傳給使用者（用任何安全管道都行）。

用法：
    python3 tools/gen_access_code.py                  # 產生一組，分級 beta
    python3 tools/gen_access_code.py --tier vip       # 指定分級
    python3 tools/gen_access_code.py --label "VIP 組"  # 指定顯示名稱
    python3 tools/gen_access_code.py --json           # 直接印出可貼進 repo.json 的片段

輸出兩樣東西：
    CODE    → 給使用者的存取碼（只出現這一次，請另外記下來）
    HASH    → 放進 repo.json 的 tiers.<分級>.hashes 裡
"""

import argparse
import hashlib
import json
import secrets
import string

# 去掉容易看錯的字元（0/O、1/l/I）
ALPHABET = "".join(c for c in string.ascii_uppercase + string.digits
                   if c not in "O0I1L")


def make_code(length: int = 16, group: int = 4) -> str:
    raw = "".join(secrets.choice(ALPHABET) for _ in range(length))
    return "-".join(raw[i:i + group] for i in range(0, len(raw), group))


def sha256(s: str) -> str:
    return hashlib.sha256(s.strip().encode("utf-8")).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", default="beta", help="分級 key，預設 beta")
    ap.add_argument("--label", default="", help="分級顯示名稱，預設用 tier")
    ap.add_argument("--length", type=int, default=16, help="碼長度，預設 16")
    ap.add_argument("--json", action="store_true", help="印出可貼進 repo.json 的片段")
    args = ap.parse_args()

    code = make_code(args.length)
    h = sha256(code)
    label = args.label or args.tier

    if args.json:
        print(json.dumps({
            "tiers": {
                args.tier: {"label": label, "hashes": [h]}
            }
        }, ensure_ascii=False, indent=2))
        print()
        print("# 把上面這段合併進 repo.json 的頂層 tiers")
        print("# 存取碼（只出現這一次，請另外交給使用者）：")
        print(f"#   {code}")
        return

    print("=" * 52)
    print(f"分級 tier : {args.tier}")
    print(f"顯示名稱  : {label}")
    print("-" * 52)
    print(f"存取碼 CODE : {code}")
    print(f"雜湊   HASH : {h}")
    print("=" * 52)
    print()
    print("1. 把 HASH 放進 repo.json：")
    print(f'   "tiers": {{ "{args.tier}": {{ "label": "{label}", "hashes": ["{h}"] }} }}')
    print()
    print("2. 在需要限定的應用上加：")
    print(f'   "requiredTiers": ["{args.tier}"]')
    print()
    print(f"3. 把 CODE 交給使用者，他在 BenStore 設定頁輸入即可解鎖。")
    print("   注意：CODE 只出現這一次，程式不會保存它。")


if __name__ == "__main__":
    main()
