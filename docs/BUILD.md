# Build Instructions

## Workshop Bundle (Ubuntu + CLI agents in the APK)
`./gradlew assembleDebug` automatically runs `downloadWorkshopBundle` first, which
fetches version-pinned files into `app/src/main/assets/workshop/` (git-ignored):

| File | Size | Source |
|---|---|---|
| `rootfs/ubuntu-arm64.tar.bin` | ~29MB | Ubuntu Base 24.04 (cdimage.ubuntu.com) |
| `rootfs/ubuntu-x86_64.tar.bin` | ~29MB | same, for the emulator |
| `agents/arm64/agy.tar.bin` | ~59MB | Antigravity manifest (sha512-verified) |
| `agents/arm64/opencode.tar.bin` | ~58MB | GitHub sst/opencode releases |
| `agents/arm64/grok.bin` | ~160MB | x.ai stable channel |
| `agents/arm64/kiro.zip` | ~157MB | Kiro stable channel |
| `bundle.properties` | — | versions + sha256 manifest (runtime verifies) |

Notes:
- Compressed files use the neutral `.tar.bin` suffix — AAPT2 gunzips anything
  ending in `.gz` and strips the suffix (59MB would become 195MB in-APK).
- `npm` agents (Claude, Codex, Kilo, Copilot) are NOT bundled — they need Node
  at setup time and install via the wizard in ~1 minute.
- `proot-distro` package + `apt` packages (curl, nodejs) still need network once
  at first setup; everything big is offline.
- Opt-out flags: `-PbundleWorkshop=0` (no rootfs), `-PbundleAgents=0` (no agents),
  `-PbundleBigAgents=0` (skip grok+kiro). Failures warn loudly but never break
  the build — the setup wizard falls back to network install.
- Test APK is ~600MB (universal). For daily dev builds use
  `-PbundleWorkshop=0 -PbundleAgents=0` to keep it small.

## First-Run Setup Wizard
First app open shows `SetupWizardActivity` (re-runnable from
Settings → Run Setup Wizard) with live progress:
1. **Storage access** — All-files permission for workspaces.
2. **Termux base system** — bootstrap (already in APK).
3. **Linux system** — Ubuntu (bundled, offline), Debian or Alpine (download).
4. **Coding agents** — bundled binaries first, network installers as fallback.

## Local Command Line Build
To build an unsigned debug APK:
```bash
./gradlew assembleDebug
```
The APK will be located at `app/build/outputs/apk/debug/`.

To build a release APK:
```bash
export KEYSTORE_FILE=/path/to/keystore
export KEY_ALIAS=alias
export KEYSTORE_PASSWORD=password
export KEY_PASSWORD=password
./gradlew assembleRelease
```

## Running Tests
Run unit tests with:
```bash
./gradlew test
```
