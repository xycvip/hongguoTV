#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib
import json
import os
import re
import subprocess

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
