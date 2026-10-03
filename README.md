# Ballz Store

Small Android TV / Fire TV app that installs open-source apps from GitHub releases.
Single repo: the app (`app/`) and the catalog (`catalog/store.json`) it reads.

## Setup
1. Open the folder in Android Studio (it generates the Gradle wrapper), or run `gradle wrapper` once.
2. In `CatalogRepo.kt` replace `YOUR_USER` in `CATALOG_URL` with your GitHub user/repo.
3. Build and sideload. First install asks you to allow "install unknown apps" for the store.

## Adding an app
```bash
pip install -r tools/requirements.txt
python tools/add_app.py TeamNewPipe/NewPipe -c Media
git add catalog && git commit -m "add NewPipe" && git push
```
The script downloads the latest release and fills in package name, minSdk, ABIs and the
signing-cert SHA-256 itself. It rejects apps with no armeabi-v7a build.
Devices pick up the new catalog on next launch.

- `--force` re-verifies an existing entry (needed if an author rotates their signing key)
- `--remove <id>` removes one
- `--asset-pattern "regex"` for repos with odd asset names

## Safety model
The app refuses to install unless the downloaded APK's package name and signing cert match the
catalog entry. A hijacked release alone can't push malware; the catalog repo is the root of trust.
