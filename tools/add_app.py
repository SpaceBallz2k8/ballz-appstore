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
import io
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


DPI_ORDER = ("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi")


def find_icon(path, a):
    """Best raster launcher icon bytes from the APK, or None (adaptive/vector-only apps)."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        try:
            p = a.get_app_icon()
        except Exception:
            p = None
        if p and p.lower().endswith((".png", ".webp", ".jpg", ".jpeg")) and p in names:
            return z.read(p)

        def rank(n):
            l = n.lower()
            base = l.rsplit("/", 1)[-1]
            layer = any(w in base for w in ("foreground", "background", "monochrome"))
            dpi = next((i for i, d in enumerate(DPI_ORDER) if d in l), len(DPI_ORDER))
            return (layer, not base.startswith("ic_launcher."), dpi, -z.getinfo(n).file_size)

        cands = [n for n in names
                 if n.startswith("res/") and n.lower().endswith((".png", ".webp"))
                 and ("launcher" in n.lower() or "icon" in n.lower())]
        return z.read(min(cands, key=rank)) if cands else None


def normalize_icon(data):
    """Resize to <=192px PNG. Needs Pillow for webp/large images; raw PNGs pass through without it."""
    if not data:
        return None
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(data)).convert("RGBA")
        img.thumbnail((192, 192))
        out = io.BytesIO()
        img.save(out, "PNG", optimize=True)
        return out.getvalue()
    except ImportError:
        return data if data[:8] == b"\x89PNG\r\n\x1a\n" else None
    except Exception:
        return None


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
        "icon": find_icon(path, a),
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


class AddError(Exception):
    """A problem the user should be told about (bad repo, no usable APK, ...)."""


def parse_repo(s):
    m = re.search(r"github\.com/([^/\s]+/[^/\s#?]+)", s)
    repo = (m.group(1) if m else s).strip().removesuffix(".git")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        raise AddError(f"Can't parse a GitHub repo from: {s}")
    return repo


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def write_icon(entry_id, png_bytes):
    icons_dir = STORE.parent / "icons"
    icons_dir.mkdir(parents=True, exist_ok=True)
    (icons_dir / f"{entry_id}.png").write_bytes(png_bytes)


def build_entry(repo, *, category=None, name=None, description=None, entry_id=None,
                icon=None, asset_pattern=None, inner_pattern=None, existing=None, warning=None,
                warning_note=None, log=print):
    """
    Download the newest release of `repo` and work out everything the catalog needs.
    Shared by the command line and the GUI. Raises AddError on any problem.

    `existing` is the current catalog entry when re-verifying: its name, description,
    category, status, icon and patterns are kept unless overridden.
    Returns {"entry", "icon_png", "icon_from_arg", "tag", "asset", "inner", "libs", "notes"}.
    """
    existing = existing or {}
    asset_pattern = asset_pattern or existing.get("assetPattern")
    inner_pattern = inner_pattern or existing.get("innerApkPattern")

    log(f"Fetching {repo} …")
    try:
        meta = gh_json(f"https://api.github.com/repos/{repo}")
        rel = gh_json(f"https://api.github.com/repos/{repo}/releases/latest")
    except urllib.error.HTTPError as e:
        raise AddError(f"GitHub error {e.code}: repo missing/private, or it has no (non-prerelease) release.")
    except urllib.error.URLError as e:
        raise AddError(f"Network error: {e.reason}")

    asset = pick_asset(rel.get("assets", []), asset_pattern)
    if not asset:
        names = ", ".join(a["name"] for a in rel.get("assets", [])) or "none"
        raise AddError(f"No usable APK (or Android archive) for armeabi-v7a in {rel['tag_name']}.\n"
                       f"Assets: {names}\n(Try an asset pattern if the naming is unusual.)")

    inner_name = None
    with tempfile.TemporaryDirectory() as tmp:
        dl = Path(tmp) / "asset.bin"
        log(f"Downloading {asset['name']} ({asset['size'] / 1e6:.1f} MB) …")
        download(asset["browser_download_url"], dl)

        if is_archive(asset["name"]):
            found = extract_apks(dl, asset["name"], tmp)
            if not found:
                raise AddError(f"No .apk found inside {asset['name']}.")
            chosen = pick_inner(found, inner_pattern)
            if not chosen:
                raise AddError("No suitable APK inside the archive. Found: "
                               + ", ".join(n for n, _ in found) + "\n(Try an inner-APK pattern.)")
            inner_name, apk = chosen
            log(f"Using {inner_name} from the archive.")
        else:
            apk = dl

        libs = apk_abis(apk)
        if libs and not any(l in ("armeabi-v7a", "armeabi") for l in libs):
            raise AddError(f"REJECTED: APK only contains native libs for {libs}; no armeabi-v7a.")
        log("Reading APK details …")
        info = apk_info(apk)

    entry = {
        "id": entry_id or existing.get("id") or slug(repo.split("/")[1]),
        "repo": repo,
        "name": name or existing.get("name") or info["label"] or repo.split("/")[1],
        "description": (description or existing.get("description")
                        or meta.get("description") or "No description.")[:300],
        "category": category or existing.get("category") or "Other",
        "packageName": info["package"],
        "minSdk": info["minSdk"],
        "abis": libs or ["any"],
        "certSha256": info["certs"],
        "status": existing.get("status") or "active",
    }
    if existing.get("statusNote"):
        entry["statusNote"] = existing["statusNote"]
    # system-changes warning (launchers etc.): kept on re-verify, can be set with warning=True
    if warning or (warning is None and existing.get("warning")):
        entry["warning"] = True
    note = warning_note or existing.get("warningNote")
    if note and entry.get("warning"):
        entry["warningNote"] = note

    icon_png = normalize_icon(info.get("icon"))
    if icon:
        entry["icon"] = icon
    elif icon_png:
        entry["icon"] = f"icons/{entry['id']}.png"     # relative to catalog/, resolved by the app
    elif existing.get("icon"):
        entry["icon"] = existing["icon"]
    if asset_pattern:
        entry["assetPattern"] = asset_pattern
    if inner_pattern:
        entry["innerApkPattern"] = inner_pattern

    notes = []
    if info["minSdk"] > 22:
        notes.append(f"minSdk {info['minSdk']}: won't show on devices older than that (e.g. Fire OS 5).")
    return {
        "entry": entry,
        "icon_png": None if icon else icon_png,
        "icon_from_arg": bool(icon),
        "tag": rel["tag_name"],
        "asset": asset["name"],
        "inner": inner_name,
        "libs": libs,
        "notes": notes,
    }


# ---------- command line ----------

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
    p.add_argument("--warning", action="store_true", help="show the system-changes warning on this app")
    p.add_argument("--warning-note", help="extra text shown with the warning")
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

    try:
        repo = parse_repo(args.repo)
        existing = next((a for a in store["apps"] if a["repo"].lower() == repo.lower()), None)
        if existing and not args.force:
            sys.exit(f"{repo} is already in the catalog as '{existing['id']}'. Use --force to re-verify.")

        r = build_entry(repo, category=args.category, name=args.name, description=args.description,
                        entry_id=args.id, icon=args.icon, asset_pattern=args.asset_pattern,
                        inner_pattern=args.inner_pattern, existing=existing,
                        warning=True if args.warning else None, warning_note=args.warning_note)
    except AddError as e:
        sys.exit(str(e))

    entry = r["entry"]
    print("\n" + json.dumps(entry, indent=2, ensure_ascii=False))
    print(f"\nRelease {r['tag']} · asset {r['asset']}")
    print("Icon: " + ("extracted from the APK" if r["icon_png"] else
                      "from --icon" if r["icon_from_arg"] else
                      "no raster icon in the APK – the app will use the owner's GitHub avatar"))
    for n in r["notes"]:
        print("Note: " + n)

    if not args.yes and input("\nAdd to catalog? [Y/n] ").strip().lower() in ("n", "no"):
        sys.exit("Aborted.")

    if r["icon_png"]:
        write_icon(entry["id"], r["icon_png"])

    store["apps"] = [a for a in store["apps"] if a["repo"].lower() != repo.lower()] + [entry]
    save_store(store)
    print(f"✔ Saved. catalogVersion = {store['catalogVersion']}. Now: git add catalog && git commit && git push")


if __name__ == "__main__":
    main()
