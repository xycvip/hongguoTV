#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib, json, os, subprocess, sys

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
APK_NAME = f"HongguoTV-{VERSION_NAME}.apk"
OUTPUTS = "outputs"
os.makedirs(OUTPUTS, exist_ok=True)
apk_path = os.path.join(OUTPUTS, APK_NAME)
subprocess.run(["cp", APK_SRC, apk_path], check=True)
sha256 = hashlib.sha256(open(apk_path, "rb").read()).hexdigest()
size = os.path.getsize(apk_path)

def changelog():
    # Keep release notes focused on the compatibility fixes in this APK.
    return (
        "- 搜索结果按标题匹配度排序，并合并漫剧/短剧接口重复返回的同一部作品。\n"
        "- 搜索页重新调整历史、屏幕键盘、联想与结果区比例；历史移至最左侧，未搜索时展示热搜推荐。\n"
        "- 首页新增「热门发现 · AI剧」专区，位于短剧、漫剧专区之后，并保留遥控器焦点操作。\n"
        "- 刷新热门后直接更新首页并恢复刷新按钮焦点，取消「按确认查看」提示；题材内容页按返回键回首页。\n"
        "- 改进 Android 14–16 应用内更新的安装 Intent，明确调用系统安装动作并授予 APK 只读访问权限。\n"
        "- 保留原有 Android 9 兼容、包名/版本/签名校验与本机记录；版本号按 MAJOR.MINOR.PATCH 自动递增。"
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
