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
      "forum": "https://github.com/danhancach",
      "firmware": "https://example.com/firmware",
      "paypal": "https://github.com/danhancach",
      "filename": "EvolutionX-….zip",
      "download": "https://sourceforge.net/projects/danhancach/files/pdx237/evoX/<zip>/download",
      "timestamp": 1234567890,
      "md5": "…",
      "size": 123456789,
      "version": "12.2"
    }
  ]
}
```

This fork:

| Resource | Host | URL |
|----------|------|-----|
| builds JSON | SourceForge | `…/pdx237/ota/builds/{device}.json/download` |
| changelog | GitHub raw | `https://raw.githubusercontent.com/danhancach/packages_apps_Updater/cnb/changelogs/%1$s.txt` |
| ROM zip | SourceForge | `…/pdx237/evoX/<file>/download` |

Changelog text lives in-repo under `changelogs/` (synced from local `evolution/OTA/changelogs/`). JSON / ROM zip stay on SourceForge (skill `sourceforge-rom-upload`).

Extra JSON fields are ignored by the parser; `md5` is the download id (package verify uses RecoverySystem).

## Build with Android Studio

Updater needs system APIs and a platform signature. From the Android tree:

- Generate keystore via `gen-keystore.sh`
- `make UpdaterStudio` once for `system_libraries/`
- Sign with the platform key used on device
