#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib, json, os, subprocess, sys

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
APK_NAME = f"hongguotv-kotlin-{VERSION_NAME}-android8.apk"
OUTPUTS = "outputs"
os.makedirs(OUTPUTS, exist_ok=True)
apk_path = os.path.join(OUTPUTS, APK_NAME)
subprocess.run(["cp", APK_SRC, apk_path], check=True)
sha256 = hashlib.sha256(open(apk_path, "rb").read()).hexdigest()
size = os.path.getsize(apk_path)

def changelog():
    # Keep release notes limited to the fixes in this APK.
    return (
        "- 修复屏蔽词、播放速度和清晰度弹窗的勾选标记显示，避免与遥控器焦点混淆。\n"
        "- 修复 Android 9 存储权限申请与拒绝后的处理。\n"
        "- 修复 Android 16 更新包版本号过低导致无法覆盖安装。\n"
        "- 时钟显示取消背景和阴影。"
    )

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
with open(json_path, "w") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)

sha_path = os.path.join(OUTPUTS, "SHA256SUMS")
with open(sha_path, "w") as f:
    f.write(f"{sha256}  {APK_NAME}\n")

subprocess.run(["gh", "release", "create", TAG, "--target", os.environ["GITHUB_SHA"], "--title", f"红果 TV {VERSION_NAME}", "--notes",
    f"红果 TV {VERSION_NAME}\n\n{notes}", "--latest"], check=True)
for asset in [apk_path, json_path, sha_path]:
    subprocess.run(["gh", "release", "upload", TAG, asset, "--clobber"], check=True)
print(f"Published {TAG}: {APK_NAME} ({size} bytes, sha256={sha256[:16]}...)")
