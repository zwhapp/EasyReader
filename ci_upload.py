#!/usr/bin/env python3
"""用带 workflow 权限的 PAT 把 .github/workflows/ci.yml 补传到 zwhapp/EasyReader。
仅此一个文件；其余 76 文件已在仓库中。"""
import os, sys, base64, json, urllib.request

TOKEN = os.environ.get("GH_TOKEN")
if not TOKEN:
    print("ERROR: GH_TOKEN not set"); sys.exit(1)
OWNER, REPO = "zwhapp", "EasyReader"
GQL = "https://api.github.com/graphql"
REPO_DIR = r"E:/Android/EinkReader"
CI = ".github/workflows/ci.yml"

def gql(query, variables):
    req = urllib.request.Request(GQL, method="POST")
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("User-Agent", "ci_upload")
    req.add_header("Content-Type", "application/json")
    req.data = json.dumps({"query": query, "variables": variables}).encode("utf-8")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace")[:300]

st, head = gql("""query($o:String!,$n:String!){repository(owner:$o,name:$n){ref(qualifiedName:"refs/heads/main"){target{oid}}}}""",
               {"o":OWNER,"n":REPO})
oid = head["data"]["repository"]["ref"]["target"]["oid"]
print("main head oid:", oid)

with open(os.path.join(REPO_DIR, CI), "rb") as f:
    b64 = base64.b64encode(f.read()).decode()
q = "mutation($i:CreateCommitOnBranchInput!){createCommitOnBranch(input:$i){commit{oid}}}"
vars = {"i":{
    "branch":{"repositoryNameWithOwner":f"{OWNER}/{REPO}","branchName":"main"},
    "message":{"headline":"ci: add GitHub Actions workflow"},
    "fileChanges":{"additions":[{"path":CI,"contents":b64}]},
    "expectedHeadOid":oid
}}
st2, r2 = gql(q, vars)
print("status:", st2)
print("resp:", json.dumps(r2)[:300])
if st2 == 200 and not r2.get("errors"):
    print("CI YAML UPLOADED OK")
else:
    print("FAILED")
