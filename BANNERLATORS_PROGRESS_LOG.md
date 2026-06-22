# Bannerlators — Progress Log

Standalone fix/build fork of `star-compose` (Winlator-based Android app, marcescence 1.4 line).
Repo: https://github.com/The412Banner/bannerlators (public). Created 2026-06-18.

## Origin / what this repo is
- Fresh, **unattached** repo: brand-new git history (single initial commit), origin points
  only at `bannerlators`. No remote/history link to `The412Banner/star-compose`.
- Source = the `star-compose` working tree at the **1.4-marcescence** line
  (gradle `versionName "7.1.4x-cmod"`, `versionCode 20` — "1.4-marcescence" is the release
  tag name, not the gradle version).
- **Self-contained**: OpenXR-SDK + adrenotools (incl. nested linkernsbypass) submodule sources
  were fetched at their pinned commits and **vendored in as plain files**. `.gitmodules`
  removed; CI no longer needs `submodules: recursive`.
- Build artifacts excluded (`.gradle`, `build/`, `.cxx`, `local.properties`, `*.iml`, `.idea`).
  Large assets (`imagefs.txz`, `proton-9.0-*.txz`) stay gitignored, same as upstream.

## CI (both manual — no accidental releases)
- **`.github/workflows/main.yml`** — "Any branch compilation." `workflow_dispatch` only.
  Builds JavaSteam JARs → `assembleDebug` → uploads APK as artifact `compiled-debug`.
  Dropped the `submodules: recursive` checkout option (vendored now).
- **`.github/workflows/release.yml`** — "Release build" `workflow_dispatch` with inputs
  `tag` (required) + `prerelease` (bool, default true). Same build, then renames the APK to
  `bannerlators-<tag>.apk` and publishes a GitHub Release via `softprops/action-gh-release@v2`
  (`permissions: contents: write`, `generate_release_notes: true`).

## Fixes applied
### 2026-06-18 — Settings screen overlapping/collapsed rows
- **Symptom** (reported via two device screenshots of 1.4-marcescence): every section card on
  the Settings screen rendered its rows on top of each other — XServer checkboxes
  ("True Mouse Control…", "Disable Xinput…") piled onto the Cursor Speed slider; Box64/FEXCore
  preset spinner + icon row + label collapsed onto one line; "Winlator/Shortcut Export Path"
  labels overlapped, etc.
- **Root cause**: each section is a `LinearLayout style="@style/FieldSet.Dark"` that gets its
  `orientation="vertical"` **only from the style**. Under 1.4's new Compose host
  (`FragmentScreen` → `AndroidView` → `FragmentContainerView` w/ `ContextThemeWrapper`), the
  style's orientation wasn't being honored, so the LinearLayout fell back to its default
  **horizontal** → children stacked at the same spot. (Width *was* honored — cards are
  full-width — so only orientation was dropped.) Inner rows were fine because they set
  `android:orientation` directly on the tag.
- **Fix**: added `android:orientation="vertical"` directly to all **11** `FieldSet.Dark`
  LinearLayout tags in `app/src/main/res/layout/settings_fragment.xml`, decoupling orientation
  from the style. Low-risk, surgical.
- The earlier suspect (commit `12b01e3` switching `FieldSet`→`FieldSet.Dark`) was a **red
  herring** — that change only swaps the background drawable (`bordered_panel`→
  `bordered_panel_dark`, a `<solid>` fill, no padding/insets) and cannot change child stacking.
- **Status**: ⏳ awaiting device confirmation (CI green ≠ working). NOT yet device-tested.

### 2026-06-18 — Splash screen rebrand (`SplashScreen.kt`)
- First-run "Installing system files" overlay was hardcoded "Bionic Star" / "V1.2" + app icon.
- **Change**: swapped `R.mipmap.ic_launcher_foreground` → repo banner logo
  (`app/src/main/res/drawable/splash_logo.jpg`, copied from root `logo.jpg`), sized
  `fillMaxWidth()` so the wide 1245×602 banner isn't squished; **removed** the title Text
  (logo already carries the "Bannerlator" wordmark); version label → **`v1.0`**. Kept
  "Installing system files", progress bar, Proceed button. Dropped unused `size` import.

### 2026-06-22 — GOG login white screen — FIXED (candidate #5, device-confirmed)
- **Symptom**: GOG store login WebView rendered a blank white screen; the OAuth login
  iframe handshake never completed. Regression from the marcescence Compose rewrite that
  hosted the login WebView inside a Compose `AndroidView` and mutated the auth params.
- **Candidates tried on device** (branch `fix/gog-login-whitescreen`):
  1. Enable third-party cookies for the login iframe (`b3cc94d`) — ❌
  2. Drop `layout=client2` so the web form self-renders (`cce55d7`) — ❌
  3. Plain Chrome UA instead of the Galaxy UA (`70ddcaa`) — ❌
  4. (combinations of the above) — ❌
  5. **Mirror the proven star-compose `GogLoginActivity` exactly** (`ef3d6df`) — ✅ **WORKED**
- **The fix (#5)**: a plain `ComponentActivity` that hosts the WebView via `setContentView`
  (NOT a Compose `AndroidView`), keeps `layout=client2` **and** the `GOG Galaxy/2.0` UA,
  enables only JS + DOM storage. `ef3d6df` reverts the three dead-end attempts; the rest of
  the app stays Compose. BH_GOG diagnostic logging retained. See the locked-in note in
  `GogLoginActivity.kt` companion comment — do NOT reintroduce the dead-end changes.

### 2026-06-22 — Game Controller Test in Start menu + `.lnk` working-directory fix
- Bundled **Game Controller Test** (`GameConTest.exe`, SDL3 gamepad tester + `GameConTest.000`,
  `SDL3.dll`, 12 `.loc` files) into `container_pattern_common.tzst` at `C:\Game Controller Test\`
  and added a top-level Start-menu shortcut. Pulled from device
  `/storage/emulated/0/Winlator/Games/Game Controller Test/`; dropped runtime junk
  (vkd3d cache, d3d/dxgi logs). Appended into the decompressed tar preserving archive
  owner/perms (uid 10314 / gid 1023, setgid dirs, 660 files). Merge `4a3e974`.
- **`.lnk` WorkingDir ("Start in") fix** (`403cd64`): GameConTest only ran from its own folder
  (needs sibling files in cwd). The generated `.lnk` set no working dir. Added `WorkingDir`
  (`HasWorkingDir` 0x10) to `MSLink` in MS-SHLLINK StringData order; optional `"workingDir"`
  field in `wine_startmenu.json`. Device-confirmed working.

### 2026-06-22 — Ship RELEASE builds, not debug (Compose-sluggishness root cause)
- Users reported the Compose UI feels laggy vs the old XML/View UI. Root cause: **every CI
  artifact was `assembleDebug`** (main.yml, build-artifacts.yml, even release.yml). Compose is
  ~2–10× slower in debug; Views barely care → the gap reads as "Compose is sluggish."
- Branch `chore/release-builds`: switched all workflows to `assemble*Release` + APK output
  paths `/debug/`→`/release/`. Kept `minifyEnabled false` (NO R8 → no reflection/JNI risk);
  release type already testkey-signed so updates still install over installs.
- Release-only gotchas fixed: `lint { abortOnError false; checkReleaseBuilds false }`; and
  `release { crunchPngs false }` — `ab_*`/`ab_gear_*`/`ab_quilt_*` animation frames +
  `ic_stat_ab_gear*` are GIFs with a `.png` extension, which release's PNG cruncher rejects
  (debug skips crunching). First release build failed on this, then fixed.
- APK size audit (built APK ~564MB): imagefs 184MB + proton 91MB = ~49% (fetched at build by
  `downloadProton`), dxwrapper 69MB, graphics_driver 63MB, dex 30MB. Found a few orphan
  component `.tzst` (turnip25.1.0, dxvk-2.3.1) — **user chose to leave all assets as-is.** R8 +
  baseline profiles + download-on-first-run (the real 275MB lever) deferred.

## Branding / repo housekeeping (2026-06-18)
- **Repo renamed** `bannerlators` → **`Bannerlator`** (https://github.com/The412Banner/Bannerlator,
  old URL redirects). Local git remote updated; download badge + release-APK name → `Bannerlator-<tag>.apk`.
- **Logo**: replaced placeholder with neon **Bannerlator** banner (`logo.jpg`, 1245×602); old
  `logo.png` removed.
- **README**: professional rewrite (centered header, badges, quick-links nav, sectioned tables).
  Added **Project Notice** = personal continuation of the discontinued/archived Winlator
  *Star Bionic* ([star-emu/star](https://github.com/star-emu/star)); no original devs except
  The412Banner; built on their work + cherry-picked community commits; free to use/share. About
  section moved OUT of README into the GitHub repo "About" description. Discord
  (`discord.gg/n8S4G2WZQ4`) + Telegram (`t.me/The412BannerGaming`) point to The412Banner.
- **Credits expanded**: StevenMXZ (Winlator-Ludashi `ludashi`/`redmagic` variants),
  GameNative (utkarshdalal, Proton bionic layers), Star/Frost dev team (star-emu),
  leegao (BCn/ASTC/ETC Vulkan texture-compression layers), isygold (Star Engine / VEGAS
  Adreno DXVK fork — the `vegas` in `v1.3-vegas`).

## Build/run history
- 2026-06-18 ~23:21 UTC — first action build (run **27795368178**, "Any branch compilation."
  on `main`) for marcescence + orientation fix. **✅ SUCCESS** — artifact `compiled-debug`
  (~541 MB APK). Awaiting device test of the Settings screen.
- 2026-06-18 — README adopted from `The412Banner/star`, adapted for bannerlators (name, badge
  → The412Banner/bannerlators, banner → logo.png). External frontends-guide link left pointing
  at `star-emu/star` (doc not vendored here).
- 2026-06-18 — second action build (run **27797077384**, on `main`) with splash rebrand +
  branding work. **✅ SUCCESS** — artifact `compiled-debug` (~541 MB APK). Still awaiting
  device test (Settings fix + new splash).

## 2026-06-19 — ⚠️ CRITICAL: source was star-compose, NOT marcescence → full re-import
- **Bug found (user-reported):** the new builds were "not from marcescence." Investigation
  proved the initial import (`60dce24`) was a snapshot of **`star-compose/main`**
  (`versionName "7.1.4x-cmod"`, NO product flavors) — the star-compose *predecessor* of
  marcescence, not the 1.4 line. Runs 27795368178 + 27797077384 therefore shipped
  star-compose. Proof: bannerlator tree was 113 files off `star-compose/main` but **600**
  files off `star@marcescence`; `app/build.gradle` was byte-identical to star-compose/main.
- **Fix — full re-import** (commit `c55fe68`): replaced the entire app source with
  **`The412Banner/star @ marcescence`** (`versionName "1.4-marcescene"`, `versionCode 20`,
  **3 product flavors** standard `com.winlator.star` / ludashi `com.ludashi.benchmark` / pubg
  `com.tencent.ig`, `cmod`→`star` package, Vulkan + Compose settings/input tabs + SteamGridDB
  + drive/container pickers). marcescence lives at star@`marcescence` (tip `0139024`); also
  mirrored in private `The412Banner/marcescence-backup`@`f112fd1`.
- **Submodules vendored** as plain files (OpenXR-SDK, adrenotools + nested linkernsbypass);
  `.gitmodules` removed; verified 0 gitlinks staged.
- **CI switched to marcescence's flavor-aware workflows** (bannerlator's old flavor-less CI
  could not build marcescence). Kept marcescence `main.yml` (workflow_dispatch; installs NDK
  26.1.10909125 + cmake; uploads 3 artifacts `standard-debug`/`ludashi-debug`/`pubg-debug`) +
  `release.yml` (workflow_dispatch, input `release_notes`). **Removed 5 extra marcescence
  workflows incl. push-triggered `release-differentpkg.yml`** (would auto-release on push).
- **Add-ons re-applied:** branded README + progress logs; Settings overlapping-rows fix
  re-applied to marcescence `settings_fragment.xml` (all 11 `FieldSet.Dark` → `orientation="vertical"`).
- **Re-import build run 27798743622 = ✅ SUCCESS** — 3 flavor APKs ~588 MB each. Standard APK
  copied to `/sdcard/Download/Bannerlator-1.4-marcescene-standard.apk` (md5 `07c3034244…`).
- **Splash branding port** (commit `ad67a6a`, build run 27799338372): marcescence's
  `SplashScreen.kt` (star pkg) rendered `R.mipmap.ic_launcher_foreground` in a 120dp sparkle
  box + a `"Star Marcescence"` title — so the earlier `splash_logo.jpg` image swap had NO
  visible effect (this is why the user "didn't see" the logo/text change). Replaced the icon
  with the banner logo (`R.drawable.splash_logo`, `fillMaxWidth`) and dropped the title text.
  `SparkleCanvas`/`frameTime` now unused (harmless; no warnings-as-errors).
- **OPEN branding choices (user to decide):** in-app name still marcescence's (`star Bionic` /
  flavor IDs above), not "Bannerlator"; splash version line still reads `v1.4-marcescence`.

## Notes / TODO
- Device-test the Settings screen after the action build (verify rows now stack vertically).
- Dialog layouts (`shortcut_settings_dialog.xml`, `box64_edit_preset_dialog.xml`,
  `screen_effect_dialog.xml`) also use style-only-orientation FieldSet but render in classic
  dialogs (not the Compose AndroidView host), so they're NOT affected by this regression.
- `app/build/outputs/apk/debug/app-debug.apk` is the single debug output path the workflows
  use (confirm flavor handling if multi-flavor APKs are ever needed here).

## 2026-06-22 (PM) — Release builds shipped + 1.5 cut
- Switched all CI workflows debug→release (`chore/release-builds`); fixes laggy Compose UI
  (debug Compose 2–10× slower). Release-build gotchas fixed: `crunchPngs false` (GIF-as-.png
  drawables), `lint{abortOnError false}`, excluded dup okhttp-coroutines artifact, dropped
  hand-committed baseline.prof(m). CI green run `27971367549`.
- ✅ DEVICE-CONFIRMED — user ran release APK, UI lag gone ("works just fine").
- Merged `chore/release-builds` → main; bumped versionCode 22→23, versionName 1.4→1.5
  (`a31bc4b`); splash auto-reads BuildConfig.VERSION_NAME → shows "V 1.5".
- Repo flipped PUBLIC again (was private during bionic-fg work).
- Cut **1.5 release** via release.yml (run 27973731778): release builds + GOG login fix +
  start-menu apps (AIO Graphics Test / Game Controller Test) + WFM drive-icon fix.
