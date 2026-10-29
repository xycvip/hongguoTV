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
    return "\n".join([
        "- 修复 Android 14–16 更新安装时可能复用旧 APK/URI 的问题，按版本使用独立安装包路径，并显式传递安装器读取权限。",
        "- 搜索历史按可用屏幕高度动态显示最近记录，保留全部历史入口；联想栏根据屏幕可用高度动态分配词条空间，尽量填满栏位、减少空白，词条过多时保留滚动。",
        "- 首页快捷操作「刷新热门 / 题材 / 随机」移至接口状态指示器下方的横线之后，状态指示器与其他页面保持一致。",
        "- 首页、搜索、播放、联想四项接口在首次进入主页时自动检测；检测中蓝灯闪烁，失败重试时橙灯闪烁，成功绿灯常亮，失败红灯常亮，无法验证时黄灯常亮。每个失败接口最多重试 3 次，页面切换不重复检测。",
        "- 顶部导航和接口状态区针对手机/电视自适应布局，状态文字与灯光居中，手机窄屏可横向浏览导航，避免标题与状态栏互相挤压。",
        "- 美化首页热门分区的「查看全部」入口，并保留原有电视遥控器焦点导航、签名与版本校验。"
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
