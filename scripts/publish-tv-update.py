#!/usr/bin/env python3
"""Publish one signed APK and update metadata in the central AppDownload repository."""
import hashlib
import json
import os
import re
import subprocess
import base64
import urllib.error
import urllib.request
import urllib.parse

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
    original = re.sub(r"^[-*•]+\s*", "", line).strip()
    normalized = re.sub(r"\s+", " ", original).strip().lower()
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

    # Recognize important user-facing features even when commit wording varies.
    if re.search(r"comment.{0,20}repl|repl.{0,20}comment", normalized):
        return "支持评论回复"
    if re.search(r"comment.{0,20}lik|lik.{0,20}comment", normalized):
        return "支持评论点赞"
    if re.search(r"send.{0,20}comment|post.{0,20}comment", normalized):
        return "支持发表评论"
    if re.search(r"official.{0,20}comment|comment.{0,20}panel|comment.{0,20}feature", normalized):
        return "新增官方评论功能"

    # Chinese commit subjects are already suitable for display.
    if re.search(r"[\u4e00-\u9fff]", original):
        return original

    # Never collapse unrelated English commits into one generic bullet.
    # Preserve unknown subjects so no feature silently disappears from release notes.
    return original


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
    return "\n".join(translated) or "- 本次版本更新（具体变更见提交记录）。"


def changelog():
    """Build concise Chinese release notes from commits since the previous AppDownload build."""
    current_commit = os.environ.get("GITHUB_SHA", "HEAD")
    previous_ref = ""
    # Prefer the app-scoped Release API asset; retain Raw as the fallback.
    previous_manifest = None
    api_headers = {
        "User-Agent": "HongguoTV-AppDownload-Publisher",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Cache-Control": "no-cache",
    }
    try:
        releases_url = (
            f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/releases"
            f"?per_page=100&cachebust={os.getpid()}"
        )
        request = urllib.request.Request(releases_url, headers=api_headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            releases = json.loads(response.read().decode("utf-8"))
        for release in releases:
            if release.get("draft") or not str(release.get("tag_name", "")).startswith("hongguotv-v"):
                continue
            for asset in release.get("assets", []):
                if asset.get("name") == "update.json" and asset.get("browser_download_url"):
                    request = urllib.request.Request(asset["browser_download_url"], headers={
                        "User-Agent": "HongguoTV-AppDownload-Publisher",
                        "Cache-Control": "no-cache",
                    })
                    with urllib.request.urlopen(request, timeout=30) as response:
                        previous_manifest = json.loads(response.read().decode("utf-8"))
                    break
            if previous_manifest is not None:
                break
    except Exception:
        previous_manifest = None

    # Fallback to the stable Raw manifest if the API/asset cannot be read.
    if previous_manifest is None:
        raw_url = (
            "https://raw.githubusercontent.com/xycvip/AppDownload/main/"
            "hongguoTV/latest.json?cachebust=" + str(os.getpid())
        )
        raw_candidates = [
            raw_url,
            "https://gh-proxy.com/" + raw_url,
            "https://ghfast.top/" + raw_url,
            "https://gh-proxy.net/" + raw_url,
        ]
        for manifest_url in raw_candidates:
            try:
                with urllib.request.urlopen(manifest_url, timeout=30) as response:
                    previous_manifest = json.loads(response.read().decode("utf-8"))
                break
            except Exception:
                continue

    if previous_manifest is not None:
        candidate = str(previous_manifest.get("sourceCommit", "")).strip()
        if re.fullmatch(r"[0-9a-fA-F]{40}", candidate):
            check = subprocess.run(
                ["git", "cat-file", "-e", f"{candidate}^{{commit}}"],
                check=False, capture_output=True
            )
            if check.returncode == 0:
                previous_ref = candidate

    # One-time migration fallback: older manifests have no sourceCommit field.
    if not previous_ref:
        previous_tag = run_text(
            ["gh", "release", "view", "--json", "tagName", "--jq", ".tagName"]
        )
        if previous_tag:
            previous_ref = previous_tag

    if previous_ref:
        subjects = run_text([
            "git", "log", "--no-merges", "--reverse", "--format=%s",
            f"{previous_ref}..{current_commit}",
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
        if re.match(r"^(?:test|ci|build)(?:\([^)]*\))?:", line, flags=re.I):
            continue
        line = re.sub(r"\[(?:release|skip-tests|source-release-sync)\]", "", line, flags=re.I).strip()
        if not line:
            continue
        line = re.sub(
            r"^(?:feat|fix|perf|refactor|docs|style|test|build|ci|chore)(?:\([^)]*\))?:\s*",
            "", line, flags=re.I
        )
        cleaned.append(line)
    # Compare the two complete source versions, rather than treating each commit
    # as an independent release-note item. Commit subjects are only supporting
    # clues; changed source paths and added/removed code are also inspected.
    if not previous_ref:
        return translate_notes("\n".join(cleaned))

    changed_paths = run_text([
        "git", "diff", "--name-only", "--diff-filter=ACMR",
        f"{previous_ref}..{current_commit}",
    ]).splitlines()
    source_paths = [
        path for path in changed_paths
        if path.startswith(("kotlin-tv/app/src/main/", "kotlin-tv/core/src/main/"))
        and path.endswith((".kt", ".java", ".xml"))
    ]
    diff_text = run_text([
        "git", "diff", "--no-ext-diff", "--unified=0",
        f"{previous_ref}..{current_commit}", "--",
        "kotlin-tv/app/src/main", "kotlin-tv/core/src/main",
    ])
    evidence = "\n".join(cleaned + source_paths + [diff_text]).lower()

    # One bullet per user-facing feature area. Multiple commits and files that
    # implement the same feature collapse into one release-note item.
    feature_rules = [
        (r"搜索|search|query|检索", "优化搜索功能与搜索结果展示"),
        (r"评论|comment|reply|回复|点赞", "完善评论、回复与互动功能"),
        (r"首页|home.?screen|homepage|热门发现|片单|推荐|recommend", "优化首页内容展示与推荐体验"),
        (r"播放|player|playback|倍速|清晰度|进度|全屏|弹幕|danmaku", "优化视频播放体验及播放设置"),
        (r"登录|login|账号|account|二维码|短信验证|phone.?login", "完善账号登录与账户功能"),
        (r"收藏|favorite|library|书架|历史记录|watch.?history", "完善收藏、书架与观看记录"),
        (r"设置|setting|preference|偏好", "优化设置选项与操作体验"),
        (r"更新|updater|update|版本检查|下载进度", "优化应用更新与版本管理"),
        (r"片源|source|cms|线路|仓库|配置导入", "完善片源管理与内容加载"),
        (r"焦点|focus|遥控器|remote.?control|导航|键盘操作", "优化电视遥控器焦点与导航操作"),
        (r"弹窗|dialog|二维码|布局|界面|ui|样式|主题|毛玻璃|动画", "优化界面布局与交互效果"),
        (r"备份|backup|恢复|restore|导入|导出|transfer", "完善本地数据备份与恢复"),
    ]
    notes = []
    matched_spans = set()
    for pattern, label in feature_rules:
        if re.search(pattern, evidence, flags=re.I):
            if label not in matched_spans:
                notes.append("- " + label)
                matched_spans.add(label)

    # If source code changed but no known feature keyword matched, still report
    # a real source-level update instead of falling back to one commit title.
    if not notes and source_paths:
        app_changed = any(path.startswith("kotlin-tv/app/src/main/") for path in source_paths)
        core_changed = any(path.startswith("kotlin-tv/core/src/main/") for path in source_paths)
        if app_changed and core_changed:
            notes.append("- 优化应用界面、功能及底层逻辑")
        elif app_changed:
            notes.append("- 优化应用界面与功能体验")
        else:
            notes.append("- 优化应用底层功能与运行逻辑")

    # Include non-source project changes only when there is no user-facing
    # source change, while excluding release/build housekeeping.
    if not notes:
        meaningful = [
            path for path in changed_paths
            if not path.startswith((".github/", "scripts/"))
            and not path.endswith(("version.properties", "README.md"))
        ]
        if meaningful:
            notes.append("- 完善项目配置与运行支持")
    return "\n".join(notes) or "- 本次版本更新（具体变更见提交记录）。"

notes = translate_notes(os.environ.get("RELEASE_NOTES", "").strip()) if os.environ.get("RELEASE_NOTES", "").strip() else changelog()
manifest = {
    "schema": 1,
    "packageName": "com.x.hongguotv",
    "sourceCommit": os.environ.get("GITHUB_SHA", ""),
    "versionCode": VERSION_CODE,
    "versionName": VERSION_NAME,
    "minSdk": 26,
    "tag": f"hongguotv-v{VERSION_NAME}",
    "apk": {"name": DISTRIBUTION_APK_NAME, "size": size, "sha256": sha256},
    "notes": notes,
}
json_path = os.path.join(OUTPUTS, "update.json")
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)

sha_path = os.path.join(OUTPUTS, "SHA256SUMS")
with open(sha_path, "w", encoding="utf-8") as f:
    f.write(f"{sha256}  {APK_NAME}\n")

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

# Publish APK as an asset of an app-specific Release in AppDownload.
# Each app uses its own tag namespace so releases from other apps never collide.
APP_RELEASE_TAG = f"hongguotv-v{VERSION_NAME}"
release_api = f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/releases/tags/{APP_RELEASE_TAG}"
api_headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "HongguoTV-AppDownload-Publisher",
}
try:
    request = urllib.request.Request(release_api, headers=api_headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        release = json.loads(response.read().decode("utf-8"))
except urllib.error.HTTPError as exc:
    if exc.code != 404:
        detail = exc.read().decode("utf-8", errors="replace")[:1000]
        raise RuntimeError(f"读取 AppDownload Release 失败（HTTP {exc.code}）：{detail}") from exc
    create_payload = json.dumps({
        "tag_name": APP_RELEASE_TAG,
        "target_commitish": "main",
        "name": f"红果 TV {VERSION_NAME}",
        "body": f"红果 TV {VERSION_NAME}\n\n{notes}",
        "draft": False,
        "prerelease": False,
        "make_latest": "false",
    }, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/releases",
        data=create_payload, method="POST",
        headers={**api_headers, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        release = json.loads(response.read().decode("utf-8"))

# This repository distributes multiple apps. Keep HongguoTV Releases out of
# GitHub's repository-wide "Latest" slot, including when retrying an older tag.
release_id = release.get("id")
if release_id:
    latest_payload = json.dumps({"make_latest": "false"}).encode("utf-8")
    latest_request = urllib.request.Request(
        f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/releases/{release_id}",
        data=latest_payload, method="PATCH",
        headers={**api_headers, "Content-Type": "application/json"})
    with urllib.request.urlopen(latest_request, timeout=60) as response:
        release = json.loads(response.read().decode("utf-8"))

# Delete same-name assets when retrying an interrupted publication.
for asset in release.get("assets", []):
    if asset.get("name") in (DISTRIBUTION_APK_NAME, "latest.json", "update.json", "SHA256SUMS"):
        request = urllib.request.Request(asset["url"], method="DELETE", headers=api_headers)
        with urllib.request.urlopen(request, timeout=60):
            pass

upload_url = release["upload_url"].split("{", 1)[0]
def upload_release_asset(local_path, asset_name, content_type):
    with open(local_path, "rb") as source:
        data = source.read()
    request = urllib.request.Request(
        f"{upload_url}?name={urllib.parse.quote(asset_name)}",
        data=data, method="POST",
        headers={**api_headers, "Content-Type": content_type})
    with urllib.request.urlopen(request, timeout=300) as response:
        result = json.loads(response.read().decode("utf-8"))
    if asset_name == DISTRIBUTION_APK_NAME:
        if result.get("size") is not None and int(result["size"]) != size:
            raise RuntimeError("AppDownload Release APK 文件大小与本地构建产物不一致。")
        digest = str(result.get("digest", "")).lower()
        if digest and digest != f"sha256:{sha256}":
            raise RuntimeError("AppDownload Release APK SHA256 与本地构建产物不一致。")
    print(f"Uploaded AppDownload Release asset: {result.get('name')}")

upload_release_asset(apk_path, DISTRIBUTION_APK_NAME, "application/vnd.android.package-archive")
upload_release_asset(sha_path, "SHA256SUMS", "text/plain")
apk_release_url = f"https://github.com/{DISTRIBUTION_REPOSITORY}/releases/download/{APP_RELEASE_TAG}/{DISTRIBUTION_APK_NAME}"
manifest["apk"]["url"] = apk_release_url
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
# Upload the manifest only after the APK URL is known and the APK asset is in place.
upload_release_asset(json_path, "update.json", "application/json")
print(f"AppDownload Release 已发布：{APP_RELEASE_TAG}，APK={DISTRIBUTION_APK_NAME}")

with open(json_path, "r", encoding="utf-8") as manifest_file:
    manifest_text = manifest_file.read() + "\n"
upload_text(f"{DISTRIBUTION_DIRECTORY}/latest.json", manifest_text,
            f"更新红果TV {VERSION_NAME} 版本信息")

# Keep the directory README in sync with the versioned APK filename and manifest.
readme_text = f"""# 红果 TV 分发目录

当前版本：**{VERSION_NAME}**

- APK 安装包：[下载 {DISTRIBUTION_APK_NAME}](https://github.com/{DISTRIBUTION_REPOSITORY}/releases/download/hongguotv-v{VERSION_NAME}/{DISTRIBUTION_APK_NAME})
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
    homepage_text = "# AppDownload\n\n应用安装包与版本更新信息的集中分发仓库。\n"

section = f"""<!-- BEGIN APP: hongguoTV -->
### 红果 TV

当前版本：**{VERSION_NAME}**

- [下载最新版 {DISTRIBUTION_APK_NAME}](https://github.com/{DISTRIBUTION_REPOSITORY}/releases/download/hongguotv-v{VERSION_NAME}/{DISTRIBUTION_APK_NAME})
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
        before = homepage_text.split(latest_heading, 1)[0] + latest_heading + "\n\n"
        after = apps_heading + homepage_text.split(apps_heading, 1)[1]
        homepage_text = before + section + "\n\n" + after
    else:
        if "## 最新版本" not in homepage_text:
            homepage_text = homepage_text.rstrip() + "\n\n## 最新版本\n\n"
        homepage_text = homepage_text.rstrip() + "\n\n" + section + "\n"

upload_text(homepage_path, homepage_text,
            f"更新 AppDownload 首页说明（红果TV {VERSION_NAME}）")

print(f"AppDownload 发布完成：{DISTRIBUTION_REPOSITORY}/{DISTRIBUTION_DIRECTORY}，中文更新说明与红果TV首页区域均已更新")

# Remove older HongguoTV Releases only after the new AppDownload Release,
# manifest, directory README, and homepage section have all been published.
# Other applications' Releases in AppDownload are intentionally preserved.
def cleanup_old_hongguotv_releases():
    page = 1
    all_releases = []
    while True:
        url = (
            f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}/releases"
            f"?per_page=100&page={page}"
        )
        request = urllib.request.Request(url, headers=api_headers)
        with urllib.request.urlopen(request, timeout=90) as response:
            releases = json.loads(response.read().decode("utf-8"))
        if not releases:
            break
        all_releases.extend(releases)
        if len(releases) < 100:
            break
        page += 1

    removed = []
    for old_release in all_releases:
            old_tag = str(old_release.get("tag_name", ""))
            if not old_tag.startswith("hongguotv-v") or old_tag == APP_RELEASE_TAG:
                continue

            delete_url = (
                f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}"
                f"/releases/{old_release['id']}"
            )
            request = urllib.request.Request(delete_url, method="DELETE", headers=api_headers)
            try:
                with urllib.request.urlopen(request, timeout=90):
                    pass
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="replace")[:1000]
                raise RuntimeError(
                    f"删除旧版 AppDownload Release {old_tag} 失败（HTTP {exc.code}）：{detail}"
                ) from exc

            # Release deletion does not remove its Git tag; remove that tag as well.
            tag_ref = urllib.parse.quote(old_tag, safe="")
            ref_url = (
                f"https://api.github.com/repos/{DISTRIBUTION_REPOSITORY}"
                f"/git/refs/tags/{tag_ref}"
            )
            request = urllib.request.Request(ref_url, method="DELETE", headers=api_headers)
            try:
                with urllib.request.urlopen(request, timeout=90):
                    pass
            except urllib.error.HTTPError as exc:
                if exc.code != 404:
                    detail = exc.read().decode("utf-8", errors="replace")[:1000]
                    raise RuntimeError(
                        f"删除旧版 Tag {old_tag} 失败（HTTP {exc.code}）：{detail}"
                    ) from exc
            removed.append(old_tag)


    print(
        f"AppDownload 红果TV旧版清理完成：删除 {len(removed)} 个旧 Release/Tag；"
        f"保留当前版本 {APP_RELEASE_TAG}。"
    )
    if removed:
        print("已清理：" + ", ".join(removed))

cleanup_old_hongguotv_releases()
