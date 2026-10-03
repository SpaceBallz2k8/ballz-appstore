#!/usr/bin/env python3
"""
Add (or re-verify) an app in catalog/store.json.

    python tools/add_app.py TeamNewPipe/NewPipe -c Media
    python tools/add_app.py https://github.com/edde746/plezy -c Streaming
    python tools/add_app.py owner/repo --force        # re-verify / update an entry
    python tools/add_app.py --remove newpipe

It downloads the latest release APK, then works out for itself:
package name, label, minSdk, native ABIs, and the signing-cert SHA-256.
It refuses apps that can't run on armeabi-v7a (the default target).

Set GITHUB_TOKEN to avoid API rate limits (optional).
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

STORE = Path(__file__).resolve().parent.parent / "catalog" / "store.json"
UA = "ballz-appstore-tool"

V7 = ("armeabi", "v7a", "armv7", "arm32")
WRONG_ARCH = ("arm64", "aarch64", "v8a", "x86", "x64")


# ---------- GitHub ----------

def gh_json(url):
    req = urllib.request.Request(
        url, headers={"Accept": "application/vnd.github+json", "User-Agent": UA}
    )
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", f"Bearer {os.environ['GITHUB_TOKEN']}")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def download(url, dest):
    # No auth header here: the redirect goes to a different host.
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)


ARCHIVE_EXT = (".zip", ".tar.gz", ".tgz", ".tar")


def is_archive(name):
    return name.lower().endswith(ARCHIVE_EXT)


def score(name, target="armeabi-v7a"):
    """Mirror of ApkPicker.kt for a 32-bit ARM target. -1 = unusable."""
    n = name.lower()
    if "debug" in n:
        return -1
    is_apk = n.endswith(".apk")
    # Archives only count if they look like Android builds (releases also hold linux/windows/mac files).
    if not is_apk and not (is_archive(n) and ("android" in n or "apk" in n)):
        return -1
    is_v7 = any(t in n for t in V7)
    if not is_v7 and any(t in n for t in WRONG_ARCH):
        return -1
    if is_v7:
        return 3
    if "universal" in n:
        return 2
    return 1


def pick_asset(assets, pattern=None):
    def usable(a):
        return a["name"].lower().endswith(".apk") or is_archive(a["name"])
    if pattern:
        rx = re.compile(pattern, re.I)
        for a in assets:
            if usable(a) and rx.search(a["name"]):
                return a
    ranked = sorted(
        ((score(a["name"]), a["name"].lower().endswith(".apk"), a) for a in assets),
        key=lambda t: (-t[0], not t[1]),
    )
    return ranked[0][2] if ranked and ranked[0][0] > 0 else None


def extract_apks(archive, asset_name, outdir):
    """Return [(member_name, path)] for every .apk inside a .zip / .tar / .tar.gz."""
    found = []

    def dest():
        return Path(outdir) / f"inner{len(found)}.apk"

    if asset_name.lower().endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                if not info.is_dir() and info.filename.lower().endswith(".apk"):
                    d = dest()
                    with z.open(info) as src, open(d, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    found.append((info.filename, d))
    else:
        with tarfile.open(archive) as t:
            for m in t:
                if m.isfile() and m.name.lower().endswith(".apk"):
                    d = dest()
                    with t.extractfile(m) as src, open(d, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    found.append((m.name, d))
    return found


def pick_inner(found, pattern=None):
    if pattern:
        rx = re.compile(pattern, re.I)
        for name, path in found:
            if rx.search(name):
                return name, path
    ranked = sorted(((score(n.rsplit("/", 1)[-1]), n, p) for n, p in found), key=lambda t: -t[0])
    return (ranked[0][1], ranked[0][2]) if ranked and ranked[0][0] > 0 else None


# ---------- APK inspection ----------

def apk_abis(path):
    with zipfile.ZipFile(path) as z:
        return sorted({n.split("/")[1] for n in z.namelist()
                       if n.startswith("lib/") and n.count("/") >= 2})


def apk_info(path):
    try:
        from androguard.core.apk import APK          # androguard 4.x
    except ImportError:
        try:
            from androguard.core.bytecodes.apk import APK  # androguard 3.x
        except ImportError:
            sys.exit("Missing dependency:  pip install -r tools/requirements.txt")
    a = APK(str(path))
    certs = {hashlib.sha256(c.dump()).hexdigest() for c in a.get_certificates()}
    return {
        "package": a.get_package(),
        "label": a.get_app_name(),
        "minSdk": int(a.get_min_sdk_version() or 1),
        "certs": sorted(certs),
    }


# ---------- store ----------

def load_store():
    if STORE.exists():
        return json.loads(STORE.read_text(encoding="utf-8"))
    return {"schemaVersion": 1, "catalogVersion": 0, "updatedAt": None, "apps": []}


def save_store(store):
    store["catalogVersion"] += 1
    store["updatedAt"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    store["apps"].sort(key=lambda a: (a["category"].lower(), a["name"].lower()))
    STORE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STORE.with_suffix(".tmp")
    tmp.write_text(json.dumps(store, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(STORE)


def parse_repo(s):
    m = re.search(r"github\.com/([^/\s]+/[^/\s#?]+)", s)
    repo = (m.group(1) if m else s).strip().removesuffix(".git")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        sys.exit(f"Can't parse repo from: {s}")
    return repo


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


# ---------- main ----------

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("repo", nargs="?", help="owner/repo or GitHub URL")
    p.add_argument("-c", "--category", default=None, help="e.g. Media, Streaming, Utilities, Games")
    p.add_argument("-n", "--name", help="override display name")
    p.add_argument("-d", "--description", help="override description")
    p.add_argument("--id", help="override catalog id")
    p.add_argument("--icon", help="icon URL (optional)")
    p.add_argument("--asset-pattern", help="regex to force a specific release asset")
    p.add_argument("--inner-pattern", help="regex to pick the APK inside an archive asset")
    p.add_argument("--force", action="store_true", help="replace an existing entry")
    p.add_argument("-y", "--yes", action="store_true", help="don't ask for confirmation")
    p.add_argument("--remove", metavar="ID", help="remove an entry by id")
    args = p.parse_args()

    store = load_store()

    if args.remove:
        before = len(store["apps"])
        store["apps"] = [a for a in store["apps"] if a["id"] != args.remove]
        if len(store["apps"]) == before:
            sys.exit(f"No app with id '{args.remove}'")
        save_store(store)
        print(f"Removed {args.remove}")
        return

    if not args.repo:
        p.error("repo is required")
    repo = parse_repo(args.repo)

    existing = next((a for a in store["apps"] if a["repo"].lower() == repo.lower()), None)
    if existing and not args.force:
        sys.exit(f"{repo} is already in the catalog as '{existing['id']}'. Use --force to re-verify.")

    print(f"Fetching {repo} …")
    try:
        meta = gh_json(f"https://api.github.com/repos/{repo}")
        rel = gh_json(f"https://api.github.com/repos/{repo}/releases/latest")
    except urllib.error.HTTPError as e:
        sys.exit(f"GitHub error {e.code}: repo missing/private, or it has no (non-prerelease) release.")

    asset = pick_asset(rel.get("assets", []), args.asset_pattern)
    if not asset:
        names = ", ".join(a["name"] for a in rel.get("assets", [])) or "none"
        sys.exit(f"No usable APK (or Android archive) for armeabi-v7a in {rel['tag_name']}. Assets: {names}\n"
                 f"(Try --asset-pattern if the naming is unusual.)")

    with tempfile.TemporaryDirectory() as tmp:
        dl = Path(tmp) / "asset.bin"
        print(f"Downloading {asset['name']} ({asset['size'] / 1e6:.1f} MB) …")
        download(asset["browser_download_url"], dl)

        if is_archive(asset["name"]):
            found = extract_apks(dl, asset["name"], tmp)
            if not found:
                sys.exit(f"No .apk found inside {asset['name']}.")
            chosen = pick_inner(found, args.inner_pattern)
            if not chosen:
                sys.exit("No suitable APK inside the archive. Found: "
                         + ", ".join(n for n, _ in found) + "\n(Try --inner-pattern.)")
            inner_name, apk = chosen
            print(f"Using {inner_name} from the archive.")
        else:
            apk = dl

        libs = apk_abis(apk)
        if libs and not any(l in ("armeabi-v7a", "armeabi") for l in libs):
            sys.exit(f"REJECTED: APK only contains native libs for {libs}; no armeabi-v7a.")
        info = apk_info(apk)

    entry = {
        "id": args.id or (existing or {}).get("id") or slug(repo.split("/")[1]),
        "repo": repo,
        "name": args.name or (existing or {}).get("name") or info["label"] or repo.split("/")[1],
        "description": (args.description or (existing or {}).get("description")
                        or meta.get("description") or "No description.")[:300],
        "category": args.category or (existing or {}).get("category") or "Other",
        "packageName": info["package"],
        "minSdk": info["minSdk"],
        "abis": libs or ["any"],
        "certSha256": info["certs"],
        "status": "active",
    }
    icon = args.icon or (existing or {}).get("icon")
    if icon:
        entry["icon"] = icon
    pattern = args.asset_pattern or (existing or {}).get("assetPattern")
    if pattern:
        entry["assetPattern"] = pattern
    inner = args.inner_pattern or (existing or {}).get("innerApkPattern")
    if inner:
        entry["innerApkPattern"] = inner

    print("\n" + json.dumps(entry, indent=2, ensure_ascii=False))
    print(f"\nRelease {rel['tag_name']} · asset {asset['name']}")
    if info["minSdk"] > 22:
        print(f"Note: minSdk {info['minSdk']} – won't show on devices older than that (e.g. Fire OS 5).")

    if not args.yes and input("\nAdd to catalog? [Y/n] ").strip().lower() in ("n", "no"):
        sys.exit("Aborted.")

    store["apps"] = [a for a in store["apps"] if a["repo"].lower() != repo.lower()] + [entry]
    save_store(store)
    print(f"✔ Saved. catalogVersion = {store['catalogVersion']}. Now: git add catalog && git commit && git push")


if __name__ == "__main__":
    main()
