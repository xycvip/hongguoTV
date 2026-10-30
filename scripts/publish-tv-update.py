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


def changelog():
    """Build release notes from commits since the previous published release."""
    current_commit = os.environ.get("GITHUB_SHA", "HEAD")
    # The previous stable release remains available until this script creates the new one.
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

    notes = []
    seen = set()
    for subject in subjects:
        line = subject.strip()
        if not line:
            continue
        # Remove workflow control markers and conventional commit prefixes.
        line = re.sub(r"\[(?:release|skip-tests)\]", "", line, flags=re.I).strip()
        line = re.sub(
            r"^(?:feat|fix|perf|refactor|docs|style|test|build|ci|chore)(?:\([^)]*\))?:\s*",
            "", line, flags=re.I
        )
        line = re.sub(r"\s+", " ", line).strip(" -—:;")
        if not line or line.lower() in seen:
            continue
        seen.add(line.lower())
        # Keep each change on one concise line.
        if len(line) > 90:
            line = line[:87].rstrip() + "..."
        notes.append(f"- {line}")

    if not notes:
        return "- 本次版本更新（具体变更见提交记录）。"
    return "\n".join(notes)


notes = os.environ.get("RELEASE_NOTES", "").strip() or changelog()
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
print(f"AppDownload 发布完成：{DISTRIBUTION_REPOSITORY}/{DISTRIBUTION_DIRECTORY}")
