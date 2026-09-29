# Updater

Simple application to download and apply OTA packages.

## Server (pdx237 / danhancach)

The app `GET`s `updater_server_url` (placeholder `{device}` ← `ro.evolution.device`)
and expects Evolution X JSON:

```json
{
  "response": [
    {
      "maintainer": "danhancach",
      "forum": "https://t.me/SonyXperiaChat",
      "firmware": "https://github.com/danhancach",
      "paypal": "",
      "filename": "EvolutionX-….zip",
      "download": "https://downloads.sourceforge.net/project/danhancach/pdx237/evoX/<zip>",
      "timestamp": 1234567890,
      "md5": "…",
      "size": 123456789,
      "version": "12.2"
    }
  ]
}
```

- `paypal` is **optional** (use `""`); the UI does **not** show a donate/PayPal button.
- Support links in the UI are **hardcoded**: Telegram (`support_forum_url`) + GitHub (`support_source_url`).
- Builds JSON and changelog are served from this repo (GitHub raw). ROM zip stays on SourceForge.

This fork:

| Resource | Host | URL |
|----------|------|-----|
| builds JSON | GitHub raw | `https://raw.githubusercontent.com/danhancach/packages_apps_Updater/cnb/builds/{device}.json` |
| changelog | GitHub raw | `https://raw.githubusercontent.com/danhancach/packages_apps_Updater/cnb/changelogs/%1$s.txt` |
| ROM zip | SourceForge | `…/pdx237/evoX/<file>` (prefer `downloads.sourceforge.net/...`) |

In-repo layout (Evolution-X style): `builds/` + `changelogs/`.

Workflow: edit local SSOT `evolution/OTA/` → copy into this tree → commit/push `cnb`. Local `evolution/OTA` is never a git repo. ROM zip upload: skill `sourceforge-rom-upload` (SF does **not** host JSON).

Extra JSON fields are ignored by the parser; `md5` is the download id (package verify uses RecoverySystem).

## Build with Android Studio

Updater needs system APIs and a platform signature. From the Android tree:

- Generate keystore via `gen-keystore.sh`
- `make UpdaterStudio` once for `system_libraries/`
- Sign with the platform key used on device
