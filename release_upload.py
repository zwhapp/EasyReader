#!/usr/bin/env python3
"""把构建好的 release APK 上传到 GitHub Release (zwhapp/EasyReader)。
前置: 环境变量 GH_TOKEN 有效 (api.github.com 可达; 无需 workflow scope)。
流程: 创建/复用 tag v<ver> -> 创建 Release -> 上传 APK 资源。
"""
import os, sys, json, urllib.request, urllib.error

TOKEN = os.environ.get("GH_TOKEN")
if not TOKEN:
    print("ERROR: GH_TOKEN 未设置"); sys.exit(2)
OWNER, REPO = "zwhapp", "EasyReader"
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"
VER = "0.2.5"
TAG = f"v{VER}"
APK = rf"E:/WORKBUDDY/OUTPUT/EasyReader-v{VER}-release.apk"
REPO_DIR = r"E:/Android/EinkReader"

def req(method, url, data=None, headers=None, binary=False):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", f"Bearer {TOKEN}")
    r.add_header("User-Agent", "release_upload")
    r.add_header("Accept", "application/vnd.github+json")
    if headers:
        for k, v in headers.items(): r.add_header(k, v)
    if data is not None:
        r.data = data if binary else json.dumps(data).encode()
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            b = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(b) if b else {})
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:400]
        return e.code, body

# 0) 校验本地 APK
if not os.path.exists(APK):
    print("ERROR: APK 不存在:", APK); sys.exit(3)
size = os.path.getsize(APK)
print("APK:", APK, size // 1024, "KB")

# 1) 查是否已存在同名 Release
st, rel = req("GET", f"{API}/repos/{OWNER}/{REPO}/releases/tags/{TAG}")
release_id = None
if st == 200 and isinstance(rel, dict):
    release_id = rel.get("id")
    print("已存在 Release id:", release_id)
elif st in (404,):
    print("无同名 Release, 将创建")
else:
    print("查询 Release 返回", st, rel); sys.exit(4)

# 2) 创建 Release (含 tag, 指向 main)
if release_id is None:
    body = {
        "tag_name": TAG, "name": f"EasyReader {VER}",
        "body": f"EasyReader {VER} — MOBI/AZW3 解析修复与网盘导入体验优化。",
        "target_commitish": "main", "draft": False, "prerelease": False,
    }
    st, rel = req("POST", f"{API}/repos/{OWNER}/{REPO}/releases", body)
    if st != 201 or not isinstance(rel, dict):
        print("创建 Release 失败", st, rel); sys.exit(5)
    release_id = rel["id"]
    print("创建 Release id:", release_id)

# 3) 删除已存在的同名 asset (避免 422)
assets = rel.get("assets", []) if isinstance(rel, dict) else []
for a in assets:
    if a.get("name") == os.path.basename(APK):
        st, _ = req("DELETE", f"{API}/repos/{OWNER}/{REPO}/releases/assets/{a['id']}")
        print("删除旧 asset:", a['name'], st)

# 4) 上传 APK
name = os.path.basename(APK)
with open(APK, "rb") as f:
    data = f.read()
st, resp = req("POST",
    f"{UPLOAD}/repos/{OWNER}/{REPO}/releases/{release_id}/assets?name={name}",
    data=data, binary=True,
    headers={"Content-Type": "application/vnd.android.package-archive"})
if st == 201 or st == 200:
    print("APK 上传成功:", resp.get("browser_download_url") if isinstance(resp, dict) else resp)
else:
    print("APK 上传失败", st, resp); sys.exit(6)
print("DONE")
