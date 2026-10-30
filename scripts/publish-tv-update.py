#!/usr/bin/env python3
"""Publish signed APK to GitHub Release and the central AppDownload repository."""
import hashlib
import json
import os
import re
import subprocess
import base64
import urllib.error
import urllib.request

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
APK_NAME = f"HongguoTV-{VERSION_NAME}.apk"
DISTRIBUTION_APK_NAME = APK_NAME
DISTRIBUTION_REPOSITORY = "xycvip/AppDownload"
DISTRIBUTION_DIRECTORY = "hongguoTV"
OUTPUTS = "outputs"
os.makedirs(OUTPUTS, exist_ok=True)
apk_path = os.path.join(OUTPUTS, APK_NAME)
subprocess.run(["cp", APK_SRC, apk_path], check=True)
with open(apk_path, "rb") as apk_file:
    sha256 = hashlib.sha256(apk_file.read()).hexdigest()
size = os.path.getsize(apk_path)


def run_text(command):
    """Run a command and return stripped stdout; return an empty string on failure."""
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else ""


def translate_note(line):
    """Translate known release-note phrases into concise Chinese."""
    original = re.sub(r"^[-*•]+\\s*", "", line).strip()
    normalized = re.sub(r"\\s+", " ", original).strip().lower()
    translations = {
        "add account api connectivity check": "新增账号接口连通性检测",
        "preserve refresh focus and show account api status": "刷新后保留焦点，并显示账号接口状态",
        "restore focus to selected ranking action after reload": "重新加载后恢复到已选排行榜操作项",
        "complete focus restoration for catalog refresh actions": "完善片单刷新操作后的焦点恢复",
        "restore focus after failed catalog refresh": "片单刷新失败后恢复焦点",
        "return focus to surviving filters after clearing genres": "清空分类后将焦点返回可用筛选项",
        "keep homepage refresh button focus after reload": "首页重新加载后保留刷新按钮焦点",
        "build latest custom apk": "构建最新定制版安装包",
    }
    if normalized in translations:
        return translations[normalized]
    # Chinese commit subjects are already suitable for display.
    if re.search(r"[\\u4e00-\\u9fff]", original):
        return original
    # Avoid exposing untranslated English commit subjects to users.
    return "其他功能优化（详情见本次版本提交记录）"


def translate_notes(notes_text):
    translated = []
    seen = set()
    for raw in notes_text.splitlines():
        item = raw.strip()
        if not item:
            continue
        chinese = translate_note(item)
        if chinese not in seen:
            seen.add(chinese)
            translated.append(f"- {chinese}")
    return "\\n".join(translated) or "- 本次版本更新（具体变更见提交记录）。"


def changelog():
    """Build concise Chinese release notes from commits since the previous release."""
    current_commit = os.environ.get("GITHUB_SHA", "HEAD")
    previous_tag = run_text(
        ["gh", "release", "view", "--json", "tagName", "--jq", ".tagName"]
    )
    if previous_tag:
        subjects = run_text([
            "git", "log", "--no-merges", "--reverse", "--format=%s",
            f"{previous_tag}..{current_commit}",
        ]).splitlines()
    else:
        subjects = run_text([
            "git", "log", "-1", "--no-merges", "--format=%s", current_commit
        ]).splitlines()

    cleaned = []
    for subject in subjects:
        line = subject.strip()
        if not line:
            continue
        line = re.sub(r"\\[(?:release|skip-tests)\\]", "", line, flags=re.I).strip()
        line = re.sub(
            r"^(?:feat|fix|perf|refactor|docs|style|test|build|ci|chore)(?:\\([^)]*\\))?:\\s*",
            "", line, flags=re.I
        )
        cleaned.append(line)
    return translate_notes("\\n".join(cleaned))

notes = translate_notes(os.environ.get("RELEASE_NOTES", "").strip()) if os.environ.get("RELEASE_NOTES", "").strip() else changelog()
manifest = {
    "schema": 1,
    "packageName": "com.x.hongguotv",
    "versionCode": VERSION_CODE,
    "versionName": VERSION_NAME,
    "minSdk": 26,
    "tag": TAG,
    "apk": {"name": DISTRIBUTION_APK_NAME, "size": size, "sha256": sha256},
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

# Publish the same verified APK and manifest to the central download repository.
# The APK is replaced first; latest.json is updated last so clients never see new
# metadata pointing at an APK that has not finished uploading.
token = os.environ.get("APPDOWNLOAD_TOKEN", "").strip()
if not token:
    raise SystemExit("缺少 APPDOWNLOAD_TOKEN：请在 hongguoTV 仓库 Actions Secrets 中配置。")

def github_contents_request(path, method="GET", payload=None):
    url = f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/contents/{path}"
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "HongguoTV-AppDownload-Publisher",
        **({"Content-Type": "application/json"} if data is not None else {}),
    })
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            body = response.read()
            return json.loads(body.decode("utf-8")) if body else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1000]
        if method == "GET" and exc.code == 404:
            return {}
        raise RuntimeError(f"AppDownload 上传失败（HTTP {exc.code}）：{detail}") from exc

def upload_file(path, local_path, message):
    # Contents API requires the current blob SHA when replacing an existing file.
    try:
        current = github_contents_request(path)
        current_sha = current.get("sha")
    except urllib.error.HTTPError:
        current_sha = None
    with open(local_path, "rb") as source:
        encoded = base64.b64encode(source.read()).decode("ascii")
    payload = {"message": message, "content": encoded, "branch": "main"}
    if current_sha:
        payload["sha"] = current_sha
    result = github_contents_request(path, "PUT", payload)
    print(f"Uploaded {path}: {result.get('commit', {}).get('sha', 'committed')}")

def upload_text(path, content, message):
    try:
        current = github_contents_request(path)
        current_sha = current.get("sha")
    except urllib.error.HTTPError:
        current_sha = None
    payload = {"message": message, "content": base64.b64encode(content.encode("utf-8")).decode("ascii"), "branch": "main"}
    if current_sha:
        payload["sha"] = current_sha
    result = github_contents_request(path, "PUT", payload)
    print(f"Uploaded {path}: {result.get('commit', {}).get('sha', 'committed')}")

upload_file(f"{DISTRIBUTION_DIRECTORY}/{DISTRIBUTION_APK_NAME}", apk_path,
            f"发布红果TV {VERSION_NAME} APK")

# Remove older versioned APKs only after the new APK is safely uploaded.
DIRECTORY_ITEMS = github_contents_request(DISTRIBUTION_DIRECTORY)
if isinstance(DIRECTORY_ITEMS, list):
    for item in DIRECTORY_ITEMS:
        old_name = item.get("name", "")
        if item.get("type") == "file" and old_name.endswith(".apk") and old_name != DISTRIBUTION_APK_NAME:
            github_contents_request(f"{DISTRIBUTION_DIRECTORY}/{old_name}", "DELETE",
                {"message": f"清理旧版红果TV安装包（{VERSION_NAME}）", "sha": item["sha"], "branch": "main"})
            print(f"Removed old APK: {old_name}")

with open(json_path, "r", encoding="utf-8") as manifest_file:
    manifest_text = manifest_file.read() + "\n"
upload_text(f"{DISTRIBUTION_DIRECTORY}/latest.json", manifest_text,
            f"更新红果TV {VERSION_NAME} 版本信息")

# Keep the directory README in sync with the versioned APK filename and manifest.
readme_text = f"""# 红果 TV 分发目录

当前版本：**{VERSION_NAME}**

- APK 安装包：[下载 {DISTRIBUTION_APK_NAME}](https://raw.githubusercontent.com/{DISTRIBUTION_REPOSITORY}/main/{DISTRIBUTION_DIRECTORY}/{DISTRIBUTION_APK_NAME})
- 应用内更新清单：[latest.json](https://raw.githubusercontent.com/{DISTRIBUTION_REPOSITORY}/main/{DISTRIBUTION_DIRECTORY}/latest.json)

## 本次更新说明

{notes}

APK 文件名带版本号。每次成功发布后，工作流会自动更新本说明、APK 和 latest.json。
"""
upload_text(f"{DISTRIBUTION_DIRECTORY}/README.md", readme_text,
            f"更新红果TV {VERSION_NAME} 下载说明")

# Update only the HongguoTV section on the AppDownload homepage.
# Content outside these markers belongs to other apps or shared repository guidance.
homepage_path = "README.md"
try:
    homepage_current = github_contents_request(homepage_path)
    homepage_text = base64.b64decode(homepage_current["content"]).decode("utf-8")
except (KeyError, ValueError):
    homepage_text = "# AppDownload\\n\\n应用安装包与版本更新信息的集中分发仓库。\\n"

section = f"""<!-- BEGIN APP: hongguoTV -->
### 红果 TV

当前版本：**{VERSION_NAME}**

- [下载最新版 {DISTRIBUTION_APK_NAME}](https://raw.githubusercontent.com/{DISTRIBUTION_REPOSITORY}/main/{DISTRIBUTION_DIRECTORY}/{DISTRIBUTION_APK_NAME})
- [应用内更新清单 latest.json](https://raw.githubusercontent.com/{DISTRIBUTION_REPOSITORY}/main/{DISTRIBUTION_DIRECTORY}/latest.json)
- [红果 TV 全部下载文件与说明](https://github.com/{DISTRIBUTION_REPOSITORY}/tree/main/{DISTRIBUTION_DIRECTORY})

**本次更新说明**

{notes}
<!-- END APP: hongguoTV -->"""

begin_marker = "<!-- BEGIN APP: hongguoTV -->"
end_marker = "<!-- END APP: hongguoTV -->"
if begin_marker in homepage_text and end_marker in homepage_text:
    before = homepage_text.split(begin_marker, 1)[0]
    after = homepage_text.split(end_marker, 1)[1]
    homepage_text = before + section + after
else:
    # Migrate the old single-app homepage section while retaining shared text.
    latest_heading = "## 最新版本"
    apps_heading = "## 应用目录"
    if latest_heading in homepage_text and apps_heading in homepage_text:
        before = homepage_text.split(latest_heading, 1)[0] + latest_heading + "\\n\\n"
        after = apps_heading + homepage_text.split(apps_heading, 1)[1]
        homepage_text = before + section + "\\n\\n" + after
    else:
        if "## 最新版本" not in homepage_text:
            homepage_text = homepage_text.rstrip() + "\\n\\n## 最新版本\\n\\n"
        homepage_text = homepage_text.rstrip() + "\\n\\n" + section + "\\n"

upload_text(homepage_path, homepage_text,
            f"更新 AppDownload 首页说明（红果TV {VERSION_NAME}）")
print(f"AppDownload 发布完成：{DISTRIBUTION_REPOSITORY}/{DISTRIBUTION_DIRECTORY}，中文更新说明与红果TV首页区域均已更新")
