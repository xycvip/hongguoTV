#!/usr/bin/env python3
"""Mirror a verified AppDownload APK into the matching source-repository Kotlin Release."""
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

SOURCE_REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "xycvip/hongguoTV")
DISTRIBUTION_REPOSITORY = "xycvip/AppDownload"
LATEST_MANIFEST_URL = (
    "https://raw.githubusercontent.com/xycvip/AppDownload/main/hongguoTV/latest.json"
)
TOKEN = os.environ.get("GH_TOKEN", "").strip()
if not TOKEN:
    raise SystemExit("缺少 GH_TOKEN：无法在源码仓库创建 kotlin-v 版本 Release。")

API_HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "HongguoTV-Source-Release-Sync",
}


def request_json(url, method="GET", payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = dict(API_HEADERS)
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            body = response.read()
            return json.loads(body.decode("utf-8")) if body else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1200]
        if method == "GET" and exc.code == 404:
            return None
        raise RuntimeError(f"GitHub API 请求失败（HTTP {exc.code}）：{detail}") from exc


def load_manifest():
    if len(sys.argv) > 1:
        with open(sys.argv[1], "r", encoding="utf-8") as source:
            return json.load(source)
    request = urllib.request.Request(
        LATEST_MANIFEST_URL + "?cachebust=" + str(os.getpid()),
        headers={"User-Agent": "HongguoTV-Source-Release-Sync"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


manifest = load_manifest()
version = str(manifest.get("versionName", "")).strip()
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("latest.json 的 versionName 无效，拒绝创建源码 Release。")

distribution_tag = f"hongguotv-v{version}"
source_tag = f"kotlin-v{version}"
if manifest.get("tag") != distribution_tag:
    raise SystemExit("latest.json 的 tag 与 versionName 不一致，拒绝同步。")

apk = manifest.get("apk") or {}
apk_name = str(apk.get("name", "")).strip()
apk_url = str(apk.get("url", "")).strip()
expected_size = int(apk.get("size", 0))
expected_sha256 = str(apk.get("sha256", "")).lower()
source_commit = str(manifest.get("sourceCommit", "")).strip()
expected_url = (
    f"https://github.com/{DISTRIBUTION_REPOSITORY}/releases/download/"
    f"{distribution_tag}/{apk_name}"
)
if not apk_name or apk_url != expected_url:
    raise SystemExit("latest.json 的 APK 文件名或下载地址与 AppDownload Release 不匹配。")
if expected_size <= 0 or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
    raise SystemExit("latest.json 的 APK 大小或 SHA256 无效。")
if not re.fullmatch(r"[0-9a-fA-F]{40}", source_commit):
    raise SystemExit("latest.json 缺少有效 sourceCommit，无法准确标记源码版本。")

download_request = urllib.request.Request(
    apk_url, headers={"User-Agent": "HongguoTV-Source-Release-Sync"}
)
with urllib.request.urlopen(download_request, timeout=300) as response:
    apk_bytes = response.read()
actual_size = len(apk_bytes)
actual_sha256 = hashlib.sha256(apk_bytes).hexdigest()
if actual_size != expected_size or actual_sha256 != expected_sha256:
    raise SystemExit(
        "AppDownload APK 校验失败："
        f"预期 {expected_size} bytes / {expected_sha256}，"
        f"实际 {actual_size} bytes / {actual_sha256}。"
    )

source_release_url = (
    f"https://api.github.com/repos/{SOURCE_REPOSITORY}/releases/tags/{source_tag}"
)
source_release = request_json(source_release_url)
notes = str(manifest.get("notes", "")).strip()
if source_release is None:
    source_release = request_json(
        f"https://api.github.com/repos/{SOURCE_REPOSITORY}/releases",
        method="POST",
        payload={
            "tag_name": source_tag,
            "target_commitish": source_commit,
            "name": f"红果 TV {version}",
            "body": f"红果 TV {version}\n\n{notes}",
            "draft": False,
            "prerelease": False,
            "make_latest": "true",
        },
    )

for asset in source_release.get("assets", []):
    if asset.get("name") in (apk_name, "SHA256SUMS", "update.json"):
        request_json(asset["url"], method="DELETE")

with tempfile.TemporaryDirectory(prefix="hongguotv-source-release-") as temp_dir:
    apk_path = os.path.join(temp_dir, apk_name)
    sha_path = os.path.join(temp_dir, "SHA256SUMS")
    update_path = os.path.join(temp_dir, "update.json")
    with open(apk_path, "wb") as output:
        output.write(apk_bytes)
    with open(sha_path, "w", encoding="utf-8") as output:
        output.write(f"{expected_sha256}  {apk_name}\n")
    with open(update_path, "w", encoding="utf-8") as output:
        json.dump(manifest, output, indent=2, ensure_ascii=False)
        output.write("\n")

    upload_url = source_release["upload_url"].split("{", 1)[0]

    def upload_asset(local_path, asset_name, content_type):
        with open(local_path, "rb") as source:
            data = source.read()
        request = urllib.request.Request(
            f"{upload_url}?name={urllib.parse.quote(asset_name)}",
            data=data,
            method="POST",
            headers={**API_HEADERS, "Content-Type": content_type},
        )
        with urllib.request.urlopen(request, timeout=300) as response:
            result = json.loads(response.read().decode("utf-8"))
        if int(result.get("size", -1)) != len(data):
            raise RuntimeError(f"源码 Release 资源大小不匹配：{asset_name}")
        return result

    upload_asset(apk_path, apk_name, "application/vnd.android.package-archive")
    upload_asset(sha_path, "SHA256SUMS", "text/plain")
    upload_asset(update_path, "update.json", "application/json")

assets = request_json(source_release["assets_url"])
source_apk = next((item for item in assets if item.get("name") == apk_name), None)
if source_apk is None or int(source_apk.get("size", -1)) != expected_size:
    raise RuntimeError("源码仓库 Release 未包含大小正确的 APK。")
remote_digest = str(source_apk.get("digest", "")).lower()
if remote_digest and remote_digest != f"sha256:{expected_sha256}":
    raise RuntimeError("源码仓库 Release APK 的 GitHub SHA256 与 AppDownload 不一致。")
remote_url = source_apk.get("browser_download_url")
if not remote_url:
    raise RuntimeError("源码仓库 Release APK 缺少下载地址。")
remote_request = urllib.request.Request(
    remote_url, headers={"User-Agent": "HongguoTV-Source-Release-Sync"}
)
with urllib.request.urlopen(remote_request, timeout=300) as response:
    remote_bytes = response.read()
if len(remote_bytes) != expected_size or hashlib.sha256(remote_bytes).hexdigest() != expected_sha256:
    raise RuntimeError("源码仓库 Release APK 下载复核失败，未能证明与 AppDownload 为同一文件。")

print(
    f"源码 Release 已核验：{SOURCE_REPOSITORY}/{source_tag}；"
    f"APK={apk_name}；size={expected_size}；SHA256={expected_sha256}；"
    f"sourceCommit={source_commit}"
)
