#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib, json, os, subprocess

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
APK_NAME = f"HongguoTV-{VERSION_NAME}.apk"
OUTPUTS = "outputs"
os.makedirs(OUTPUTS, exist_ok=True)
apk_path = os.path.join(OUTPUTS, APK_NAME)
subprocess.run(["cp", APK_SRC, apk_path], check=True)
with open(apk_path, "rb") as apk_file:
    sha256 = hashlib.sha256(apk_file.read()).hexdigest()
size = os.path.getsize(apk_path)

def changelog():
    # Keep this release's notes focused on changes included in the current build.
    return "\\n".join([
        "- 修复「我的」页面切换时另开窗口的问题：账号页现在与首页、搜索、设置共用同一导航界面，切换更连贯。",
        "- 登录界面改为页面内居中卡片，不再使用二维码登录弹窗；二维码直接显示在卡片中，减少手机屏幕上二维码被裁切的情况。",
        "- 新增二维码登录 / 手机号登录切换入口；手机号登录提供区号、手机号、短信验证码和登录操作，刷新页面时尽量保留输入内容。",
        "- 账号登录状态下在「我的」页面集中展示云端收藏和观看历史，并提供刷新云端数据、退出登录入口。",
        "- 保留本机内容与原有首页、搜索、播放和设置功能；继续修正账号页布局的 Kotlin 编译问题。"
    ])

notes = os.environ.get("RELEASE_NOTES", "").strip() or changelog()
manifest = {
    "schema": 1,
    "packageName": "com.x.hongguotv",
    "versionCode": VERSION_CODE,
    "versionName": VERSION_NAME,
    "minSdk": 26,
    "tag": TAG,
    "apk": {"name": APK_NAME, "size": size, "sha256": sha256},
    "notes": notes,
}
json_path = os.path.join(OUTPUTS, "update.json")
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)

sha_path = os.path.join(OUTPUTS, "SHA256SUMS")
with open(sha_path, "w", encoding="utf-8") as f:
    f.write(f"{sha256}  {APK_NAME}\n")

subprocess.run([
    "gh", "release", "create", TAG,
    "--target", os.environ["GITHUB_SHA"],
    "--title", f"红果 TV {VERSION_NAME}",
    "--notes", f"红果 TV {VERSION_NAME}\n\n{notes}",
    "--latest"
], check=True)
for asset in [apk_path, json_path, sha_path]:
    subprocess.run(["gh", "release", "upload", TAG, asset, "--clobber"], check=True)
print(f"Published {TAG}: {APK_NAME} ({size} bytes, sha256={sha256[:16]}...)")
