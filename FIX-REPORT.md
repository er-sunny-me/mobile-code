# Xyz.IDE — Fix Report (2026-11-15 night)

App renamed from "Mobile Code" / "Novus IDE" to **Xyz.IDE** (versionName 0.1.0, versionCode 131).

## 1. "App not installed" next to Termux — FIXED

**Root cause:** the app kept Termux's identity: `applicationId "com.termux"`.
Android treats it as the same app as the real Termux, and the signature clash
makes the install fail with "App not installed".

**Fix:**
- `applicationId` and every identity string (manifest placeholders, string
  entities, `TermuxConstants.TERMUX_PACKAGE_NAME`) changed to **`com.xyzide`**.
- The Java code packages stay `com.termux.*` on purpose: the JNI bootstrap
  loader (`Java_com_termux_app_TermuxInstaller_getZip`) and all internal class
  references keep working, and the class-name constants in `TermuxConstants`
  (BuildConfig, TermuxActivity, TermuxService, SettingsActivity, file
  receivers) are pinned to the real class names instead of being derived from
  the package name.
- **Bootstrap patching:** Termux's prebuilt bootstrap binaries contain the
  absolute prefix `/data/data/com.termux` compiled in (~10,100 occurrences per
  architecture). `com.xyzide` is deliberately the **same length** as
  `com.termux`, so `scripts/patch-bootstrap.py` can byte-patch every
  occurrence inside the four official bootstrap zips without moving a single
  offset. The patched zips live in `app/src/main/cpp/` and their SHA-256
  checksums replaced the official ones in `app/build.gradle`
  (`downloadBootstraps`). Verified: the patched `bin/bash` contains
  `/data/data/com.xyzide`, and zero files retain `com.termux`.
- Intent-extra literals (`com.termux.execute.*`, `com.termux.app.INSTALL_AGENT`)
  in the GUI/terminal code were renamed to the `com.xyzide.*` forms so they
  stay in sync with the constants `TermuxService` reads.

## 2. AI problems — FIXED (hardcoded paths)

`CLIAgentManager` and `AIChatManager` hardcoded `/data/data/com.termux/files/home`
in install commands, probe scripts and the shell prelude (10+ places). After
the rename those paths point at the *wrong app's* home. They now all derive
from `TermuxConstants.TERMUX_HOME_DIR_PATH` / `TERMUX_PREFIX_DIR_PATH`, so the
agent layer can never disagree with the app about where home is.

## 3. GUI glitch — FIXED (chat output bleeding between runs)

In the Agent Chat, stopping a run and immediately sending a new prompt could
mix the old run's output/finish events into the new chat: `AgentRunner`'s
reader threads only checked a shared `cancelled` flag, which the next `run()`
resets. `AgentRunner` now stamps every run with a generation number and drops
callbacks from stale generations.

## 4. Branding sweep

All remaining user-visible "Mobile Code" strings (setup wizard welcome +
clipboard label, terminal install messages, AI context prompts, update
notification title) now say **Xyz.IDE**. `TERMUX_APP_NAME` in
`TermuxConstants` (was "Novus IDE") is "Xyz.IDE".

## 5. Setup & customisation — audited

The first-run Setup Wizard (storage → bootstrap → distro picker
Ubuntu/Debian/Alpine → agent picker) and the settings surface (agent
enable/model/thinking-level, API keys, workshop distro, workspace) were
already in place and are untouched except for branding. Setup now also works
fully offline from the bundled workshop as before.

## Build verification

Built on 2026-11-16 (~01:10 IST) with Gradle 9.5.0 / AGP 8.13.2,
`gradle :app:assembleDebug` → **BUILD SUCCESSFUL in 23m 28s**.

Universal debug APK: `app/build/outputs/apk/debug/MobileCode-v0.1.0-debug_universal.apk`
(391,880,063 bytes; per-ABI splits also produced).

`aapt2 dump badging`:
```
package: name='com.xyzide' versionCode='131' versionName='0.1.0'
targetSdkVersion:'28'
application-label:'Xyz.IDE'
```

`apksigner verify --print-certs`:
```
Signer #1 certificate DN: CN=APK Signer, OU=Earth, O=Earth
Signer #1 certificate SHA-256 digest: b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1
```
(the repo's debug/test key, as expected for a debug build.)

Bootstrap check inside the shipped APK: `lib/arm64-v8a/libtermux-bootstrap.so`
→ `bin/bash` contains `/data/data/com.xyzide/files/usr/lib` and **zero**
occurrences of `com.termux`.

## Known limits

- The real coexistence test (installing while the genuine Termux app is
  present) can only be done on a phone — install the APK with Termux already
  installed; it should now install as a separate app named Xyz.IDE.
- The `apt-android-5` bootstrap variant in `app/build.gradle` still lists the
  official checksums; only the default `apt-android-7` variant is patched.
- If upstream Termux publishes a new bootstrap version, re-run
  `scripts/patch-bootstrap.py` and update the four checksums.
