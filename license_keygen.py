#!/usr/bin/env python3
"""
DeepSeekChat 激活码生成器（开发者工具）
=====================================
用法：python license_keygen.py <设备码> [数量]

示例：
  python license_keygen.py 857C52        # 生成1个
  python license_keygen.py 857C52 5      # 生成5个

用户App设置页会自动显示设备码，发给开发者即可。
"""

import hmac
import hashlib
import sys

SECRET = b"DsK#9xPq2@mZ7w_LvN8!rT5&yA3cF6jH"


def generate_key(device_id: str) -> str:
    device_id = device_id.strip().upper()
    if len(device_id) != 6:
        raise ValueError(f"设备码应为6位: {device_id}")

    mac = hmac.new(SECRET, device_id.encode(), hashlib.sha256).hexdigest()
    checksum = mac[:12].upper()
    return f"DEEPSEEK-{device_id}-{checksum}"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法：python license_keygen.py <设备码> [数量]")
        print("示例：python license_keygen.py 857C52")
        sys.exit(1)

    device_id = sys.argv[1]
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 1

    print(f"\n为设备 {device_id} 生成 {count} 个激活码：\n")
    for i in range(count):
        print(f"  {i+1}. {generate_key(device_id)}")
    print(f"\n每个激活码仅限设备 {device_id} 使用。\n")
