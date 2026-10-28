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
    prev = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "kotlin-v*"],
                          capture_output=True, text=True)
    base = prev.stdout.strip() if prev.returncode == 0 else ""
    rng = [f"{base}..HEAD"] if base else ["-n", "20"]
    log = subprocess.run(["git", "log", "--no-merges", "--pretty=format:- %s"] + rng,
                         capture_output=True, text=True).stdout.strip()

    if not base:
        intro = (
            "红果TV 更新说明\n\n"
            "## 新增与调整\n"
            "- 发布下载入口统一指向 xycvip/hongguoTV 自有仓库。\n"
            "- 保留现有 Kotlin 原生客户端及已有自定义功能。\n"
            "- 移除应用启动时的安装次数统计上报调用。\n"
            "- 删除 InstallStatsPing、InstallStatsReporter 统计实现及对应单元测试。\n"
            "- 删除 deploy/scf-stats 下的匿名统计云函数和查询说明文件。\n"
            "- 从主说明文档和 Kotlin 说明文档中删除隐私/匿名统计章节。\n"
            "- 收藏、观看历史等本地数据按应用现有机制保存在设备上。\n\n"
            "## 数据与隐私说明\n"
            "- 本次移除的是安装次数匿名统计链路；应用其他功能的网络请求仍以实际功能和源码为准。\n"
            "- 收藏、观看历史等本地数据功能保持现有实现。\n\n"
            "## 相关提交"
        )
        return intro + ("\n" + log if log else "\n- 已完成仓库下载入口调整与匿名统计链路移除。")

    if not log:
        return f"红果TV {VERSION_NAME}：重新构建，无新增提交。"
    return f"红果TV {VERSION_NAME} 更新说明（自 {base} 起）：\n\n{log}"

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

subprocess.run(["gh", "release", "create", TAG, "--title", f"红果 TV {VERSION_NAME}", "--notes",
    f"红果 TV {VERSION_NAME}\n\n{notes}", "--latest"], check=True)
for asset in [apk_path, json_path, sha_path]:
    subprocess.run(["gh", "release", "upload", TAG, asset, "--clobber"], check=True)
print(f"Published {TAG}: {APK_NAME} ({size} bytes, sha256={sha256[:16]}...)")