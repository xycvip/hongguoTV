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
    # One concise change per line; use real newlines in release notes and update manifest.
    return "\n".join([
        "- 登录入口仅保留二维码登录，移除手机号登录。",
        "- 优化「红果账户」页面，集中展示历史、收藏和预约状态。",
        "- 调整手机端二维码卡片尺寸与居中布局，减少页面裁切。",
        "- 缩小系统时间字号并优化手机、电视端位置。",
        "- 本地备份新增明文账户凭据恢复；云端观影资料不写入备份，恢复后可刷新同步。",
        "- 保留原有本机收藏、观看记录、设置及备份合并逻辑。"
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
