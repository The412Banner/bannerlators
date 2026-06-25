# Plan: Replace Bannerlator's Steam store with a Pluvia/GameNative-grade Steam client

Status: **RECON + PLAN (not started)** — drafted 2026-06-24. For down-the-road implementation.

## TL;DR

- "Pluvia Steam" = the Steam-on-Android stack that **Pluvia** (oxters168/Pluvia, original, now stalled) pioneered and **GameNative** (utkarshdalal/GameNative, GPL-3.0) matured: JavaSteam + DepotDownloader for auth/download, plus a Goldberg/coldclient steam-emu launch so games actually run offline in a Wine container.
- **We do not start from scratch.** Bannerlator already has a working JavaSteam 1.8.0 Steam store (just fixed the BC-provider + login-race bugs). This is an **upgrade**, not a greenfield build.
- **The single best reference is REF4IK/winlator-ref4ik-** (`com.winlator.cmod`, v7.1.4x-cmod). It already ported the GameNative/Pluvia Steam module into a Winlator **Cmod** fork — the *same lineage as Bannerlator* (`com.winlator.star`) — using the *same* `in.dragonbra:javasteam:1.8.0` we already depend on. Its container/Shortcut/`.desktop` model maps ~1:1 to ours.
- **The real prize is the launch model, not the download.** Our current Steam store launches the raw exe (`Exec=wine Z:\game.exe`) with no Steam emulation, so anything calling `SteamAPI_Init`/`steam_api(64).dll` fails. ref4ik/GameNative launch via `steamclient_loader_x64.exe` + generated `steam_settings` (Goldberg/coldclient) = games actually boot, offline, with cloud-save dirs. This is what makes Steam titles playable.

## What each codebase gives us

### GameNative (`/home/claude-user/GameNative/`, GPL-3.0, pkg `app.gamenative`)
- Steam stack = a **fork of JavaSteam** published as `io.github.joshuatam:javasteam[-depotdownloader]:1.8.0.1-20-SNAPSHOT` (namespace still `in.dragonbra.javasteam.*`), + `com.madgag.spongycastle:prov`. Uses Sonatype snapshots repo.
- Whole brain is `service/SteamService.kt` (~4.5k lines): auth (QR/creds/Guard via `SteamClient.authentication`), PICS, licenses, `DepotDownloader` orchestration, cloud sync. Tokens persisted **Keystore-encrypted** via `PrefManager`+`Crypto`. Room DB `PluviaDatabase` (v22, ~17 entities).
- Install path: `<dataDir>/Steam/steamapps/common/<game>` (or external). Manifests cached in `<appDir>/.DepotDownloader/`.
- Container glue: container id = `"STEAM_<appId>"`; mounts `getAppDirPath(appId)` as a wine drive in `container.drives`; launch string built in `XServerScreen.getWineStartCommand` with 3 modes — Bionic-Steam (native libsteamclient), Real Steam (`steam.exe -applaunch`), and coldclient loader. **No `Security.addProvider` in app code** (uses platform `MessageDigest`, not `"BC"`) — note this differs from upstream JavaSteam 1.8.0 which needs the BC fix.
- Best for: the most feature-complete reference (cloud saves, updates/verify, multiple launch modes). But it's a different package, different DB, Compose-heavy, and pulls a SNAPSHOT JavaSteam fork.

### ref4ik (`REF4IK/winlator-ref4ik-`, pkg `com.winlator.cmod`) ← **primary reference**
- Winlator **Cmod** (Coffincolors) fork — same family as Bannerlator. Credits Bruno + Cmod + GameNative + Pipetto bionic + WinNative + FEX.
- Ships a **full Pluvia/GameNative Steam port** under `steam/` (~90 Kotlin files, Compose + Room + coroutines), on **upstream `in.dragonbra:javasteam:1.8.0`** (exactly what we already use).
- `steam/service/SteamService.kt` (~5.8k lines): `downloadApp/downloadAppForUpdate/downloadAppForVerify`, `DepotDownloader(androidEmulation=true)`, `getInstalledExe()` via PICS `LaunchInfo` + cached manifests, `SteamAutoCloud` saves.
- Install roots: internal `…/Steam/steamapps/common`, external, or a SAF-picked folder (`PrefManager.defaultDownloadFolder`).
- **Launch = Goldberg/coldclient emulation** (`steam/SteamGameLauncher.kt`): `wine "C:/Program Files (x86)/Steam/steamclient_loader_x64.exe"` + generated `steam_settings`/app manifests/interfaces, game mounted on **A: drive** (`mountADrive`).
- **Already solved the exact UX the user wants**:
  - `PrefManager.preferredSteamContainerId` + `ContainerUtils.getUsableContainerOrNull/getOrCreateContainer` + `SteamGameLauncher.launch(..., preferredContainerId)` = **pick / find-or-create the container** to add+launch from.
  - Generic store-agnostic import: `GameImportPickerActivity` + `GameImportConfirmActivity` → `.desktop` writer.
  - `steam/enums/GameSource.kt` = `STEAM, CUSTOM_GAME, GOG, EPIC, AMAZON` multi-store scaffolding; shortcuts carry `game_source` + `app_id` Extra-Data keys.
- Friction: namespace `com.winlator.cmod` → `com.winlator.star`; Steam layer is Kotlin+Compose+Room (Compose already in our build).

### Bannerlator today (`com.winlator.star`, `store/Steam*`)
- Working JavaSteam 1.8.0 store: `SteamRepository` (CM connect/login/licenses/PICS/SQLite + string event bus), `SteamDepotDownloader.runInstall`, `SteamDatabase` (SQLite, not Room), `SteamGamesActivity`/`SteamGameDetailActivity` (Compose), QR+cred auth.
- Install path: **`filesDir/imagefs/steam_games/<sanitized name>/`** (the location the user wants to keep). Under `imagefs/` so it's reachable as a wine drive.
- Add-to-games-tab: shared `StarLaunchBridge.addToLauncher` (used by GOG/Epic/Amazon too) → **container-picker AlertDialog** at add time → writes `<name>.desktop` (`Exec=wine Z:\…`) into the chosen container's Desktop dir → `ContainerManager.loadShortcuts` surfaces it in the Games tab.
- Token persistence: `SharedPreferences("steam_prefs")` keys `username`+`refresh_token` (plaintext).
- Gaps vs Pluvia/ref4ik: **no Steam-emulation launch** (raw `wine exe` → DRM/steam_api games fail), no cloud saves, no updates/verify, install path hardcoded (no SAF), exe detection is filesystem-scoring not PICS LaunchInfo.

## The clean integration seams (from recon)

Our UX (browse → install w/ progress/pause/resume → add to Games tab → pick container → shortcut) is already decoupled from the JavaSteam engine. Swap points, in priority order:

1. **Launch model** (biggest user-visible win): add a Goldberg/coldclient launch path. Bundle `steamclient_loader_x64.exe` + Goldberg `steam_api(64).dll` + a `steam_settings` generator (lift `SteamGameLauncher.kt` + the loader assets from ref4ik). Either replace the `Exec=wine Z:\…` shortcut body or add a `game_source=STEAM`/`app_id` Extra-Data path that `XServerDisplayActivity` recognizes and rewrites into the loader invocation.
2. **Container selection at add time**: we already have a picker in `StarLaunchBridge`; add a **persisted preferred container** (`preferredSteamContainerId`) + find-or-create (lift `ContainerUtils.getOrCreateContainer`) so the user can set a default and skip the prompt.
3. **Exe / launch-info detection**: replace filesystem exe-scoring with PICS `LaunchInfo` + cached-manifest parsing (`getInstalledExe()` from ref4ik) for correct default exe + launch args.
4. **Cloud saves** (optional, later): port `SteamAutoCloud` + GSE/Goldberg save-dir mapping under the prefix.
5. **Updates / verify** (optional, later): `downloadAppForUpdate/Verify`.

Keep unchanged: `imagefs/steam_games/<name>` path, `SteamDatabase` schema (or migrate to Room only if we port wholesale), the string-event protocol, `ContainerManager.loadShortcuts`, the Games tab.

## Two implementation strategies

### Option A — Incremental upgrade of our existing store (RECOMMENDED first)
Keep our `SteamRepository`/`SteamDatabase`/UX; selectively graft ref4ik pieces:
- Phase 1: **Goldberg/coldclient launch** + bundled loader assets + `game_source`/`app_id` shortcut extras + `XServerDisplayActivity` recognizes them. (Makes games actually run.)
- Phase 2: **preferred-container** pref + find-or-create; keep picker as fallback.
- Phase 3: **PICS LaunchInfo** exe detection.
- Phase 4 (opt): cloud saves; Phase 5 (opt): updates/verify.
- Pros: low risk, reuses our working download+auth (already bug-fixed), no Room migration, no package-rename churn, ships value each phase. Cons: we maintain our own store shell; less "complete" than GameNative day one.

### Option B — Wholesale port of ref4ik's `steam/` module
Drop ref4ik's entire `steam/` (SteamService + SteamGameLauncher + ContainerUtils + Compose UI + Room) into `com.winlator.star`, repackaged; retire our `store/Steam*`.
- Pros: full feature parity immediately (cloud saves, updates, Goldberg launch, multi-store enum). Cons: big surface (Room DB v22 migration/coexistence with our SQLite, Compose screens, container-API differences cmod↔star, A:-drive vs our Z: convention), high regression risk, GPL-3.0 compliance for the whole module, more namespace/asset churn.

**Recommendation:** Option A. We already own a working JavaSteam store; the missing magic is the **Goldberg launch** (Phase 1) — do that first, it's the highest value/risk ratio and directly uses ref4ik's `SteamGameLauncher` + loader assets. Revisit Option B only if we decide we want GameNative's cloud-save/update suite wholesale.

## Risks & open questions
- **Licensing**: ref4ik/GameNative/Pluvia Steam code is **GPL-3.0**; JavaSteam is LGPL-2.1. Bannerlator must be GPL-3.0-compatible to lift their source (we already ship JavaSteam). Confirm our distribution license.
- **Goldberg loader assets**: need `steamclient_loader_x64.exe` + Goldberg `steam_api(64).dll` (arm-compatible PE) bundled in imagefs/container_pattern; verify redistribution + arch.
- **Bionic vs glibc launch**: GameNative has bionic-steam/real-steam modes; ref4ik uses coldclient only. Bannerlator is bionic — coldclient/Goldberg is the compatible path; real-Steam-in-Wine likely won't work on our bionic Proton.
- **Drive convention**: ref4ik mounts game on **A:**, we use a computed `Z:\` relative path. Pick one and keep `StarLaunchBridge.writeShortcut` consistent with the launcher.
- **JavaSteam version**: ref4ik uses upstream 1.8.0 (= ours, good). GameNative uses the joshuatam SNAPSHOT fork (avoid the SNAPSHOT unless we vendor-build it).
- **DB**: ref4ik is Room; we are raw SQLite. Option A avoids this; Option B needs a migration/coexistence plan.

## First concrete step when we start (Option A, Phase 1 spike)
1. Clone is already at `/home/claude-user/scratchpad/ref4ik`. Extract `steam/SteamGameLauncher.kt`, `steam/utils/ContainerUtils.kt`, the coldclient/steam_settings generator, and the bundled loader assets.
2. Add a `game_source=STEAM` + `app_id` path to `StarLaunchBridge.writeShortcut` and teach `XServerDisplayActivity` to launch via `steamclient_loader_x64.exe` when those extras are present.
3. Device-test a known steam_api game end-to-end (download already works post-fix → add to container → launches under Goldberg).
