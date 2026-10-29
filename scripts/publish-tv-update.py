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
    # 以本次实际功能改动为发布说明，版本 tag 仍使用语义版本供应用内更新识别。
    return (
        "- 重做电视搜索键盘：A-Z 与 0-9 常驻显示，字母数字按每行 4 个按键排列，不分页；增加删除、清空输入和直接搜索按键。\n"
        "- 加宽搜索历史栏、增大历史条目高度并允许两行显示；历史与键盘分开纵向滚动，历史条数按电视屏幕高度动态调整，键盘按可用高度分配行高以减少底部空白；修正遥控器焦点，使键盘左边缘可进入历史栏，且「清空历史」与历史条目可通过上下左右键访问。\n"
        "- 漫剧、短剧搜索分别获取最多 3 页，等待两路完成后统一按剧名匹配度评分、排序和去重，不按接口返回先后展示。\n"
        "- 首页「刷新热门」统一刷新短剧、漫剧和 AI 剧；处理刷新请求重叠，避免沿用旧的分区结果。\n"
        "- 保留首页刷新后焦点回到刷新按钮的行为；正式构建仍校验包名、版本号和签名。"
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
