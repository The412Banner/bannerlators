# RESUME — bionic-fg Phase 3 (per-container toggle) — DEVICE TEST IN PROGRESS

**Status 2026-06-20: Phase 3 code DONE + built + installed. Mid device-test (fresh-install path).**

## What just happened
- Phase 3 code committed `9ccf22f` on `feature/bionic-fg-framegen` (private repo). Per-container Frame Generation toggle: Container.java (frameGenEnabled/frameGenMultiplier in extraData), XServerDisplayActivity writes `$HOME/.config/bionic-fg/conf.toml` + sets BIONIC_FG_ENABLE=1 on launch, ContainerDetailScreen/ViewModel UI (toggle + 2x/3x/4x dropdown).
- CI: `build-artifacts.yml` changed to **standard-flavor-only** (commit on branch) to avoid 3-flavor OOM (exit 143). Green build = run **27869509588**. Standard APK md5 `df68351f296ebf0f4e9d96df1cb1b796` → `/sdcard/Download/Bannerlator-phase3-fg-standard.apk`.
- **Signing:** CI uses ephemeral debug keys (no committed keystore). Installed app key `2cca1df7…` ≠ new APK key `2091b4be…` → can't update in place. **USER CHOSE "wipe & start fresh."**
- **DID:** backed up working `.so`+manifest (md5 `23f5bfda`, proven dispatch-fix build) → `/sdcard/Download/bionic-fg-staged/{libbionic_fg.so,VkLayer_BIONIC_framegen.json}`. Then `pm uninstall com.winlator.banner` + `pm install` new APK = **Success** (firstInstall 07:34:56). **Old containers (xuser/-1/-2 incl DOOMBLADE) + old staged .so are GONE (intentional wipe).**

## ➡️ NEXT STEPS (resume here)
1. **User opens the app** → completes first-run setup (extracts fresh imagefs + makes default container). THIS IS A LAUNCH (session-crash risk — that's why this file exists).
2. **Re-stage the .so into the FRESH imagefs** (run after imagefs exists). Get the app uid first (`pm list packages -U | grep com.winlator.banner`), then:
   ```
   getlog --exec "U=<uid>; D=/data/data/com.winlator.banner/files/imagefs; \
     cp /sdcard/Download/bionic-fg-staged/libbionic_fg.so \$D/usr/lib/ && \
     cp /sdcard/Download/bionic-fg-staged/VkLayer_BIONIC_framegen.json \$D/usr/share/vulkan/implicit_layer.d/ && \
     chown \$U:\$U \$D/usr/lib/libbionic_fg.so \$D/usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json && \
     chmod 644 \$D/usr/lib/libbionic_fg.so \$D/usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json && ls -la \$D/usr/lib/libbionic_fg.so"
   ```
   (Verify md5 of staged .so == `23f5bfda72e49366e2d443ffc4ae288c`.)
3. **User**: create/use a container, install DOOMBLADE (or any DXVK game).
4. **User**: open that container's settings → toggle **Frame Generation (AI)** ON → (optionally pick 3x/4x) → save. (This now writes conf.toml + sets BIONIC_FG_ENABLE=1 automatically — NO manual `.container` hack needed = the whole point of Phase 3.)
5. **User**: launch the game. Capture logcat to crash-surviving file FIRST:
   ```
   getlog --exec "logcat -c; nohup logcat > /sdcard/Download/bionicfg_phase3_test.txt 2>&1 &"
   ```
6. **Verify** (in log + on screen): `SwapchainState ready … mult=N`, DXVK HUD fps ≈ half of system/perf overlay fps (2x), conf.toml at `imagefs/home/xuser/.config/bionic-fg/conf.toml` has the multiplier the UI set. Confirm it worked WITHOUT any manual `.container` edit. Then try 3x/4x (UNPROVEN on this stack).

## Gotchas
- ⚠️ Next CI rebuild will ALSO mismatch signature (ephemeral key) → another wipe, UNLESS we commit a stable debug keystore (user declined for now; re-offer if iterating a lot).
- `.so`+manifest still hand-staged (Phase 5 = bundle into imagefs/APK so no staging).
- conf.toml hot-reloads (layer re-stats mtime each present) → Phase 4 in-game drawer rewrites it live.
- Only 2x device-proven (run6); 3x/4x built but untested.
