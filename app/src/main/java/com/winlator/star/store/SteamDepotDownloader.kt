package com.winlator.star.store

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Steam depot download engine — uses JavaSteam's built-in DepotDownloader.
 *
 * Replaces the hand-rolled HTTP approach. DepotDownloader handles:
 *   - manifest request codes (CM connection)
 *   - CDN auth tokens (CM connection)
 *   - depot key requests (CM connection)
 *   - chunk downloading via Ktor CIO HTTP
 *   - AES-ECB decryption + VZip/LZMA decompression
 */
object SteamDepotDownloader {

    private const val TAG = "SteamDepot"

    // -------------------------------------------------------------------------
    // Active download tracking — used by UI to detect stale DL_DOWNLOADING rows
    // -------------------------------------------------------------------------

    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Int, Unit>()

    /** True if a download for this appId is currently running in this process. */
    @JvmStatic fun isDownloading(appId: Int): Boolean = activeDownloads.containsKey(appId)

    // -------------------------------------------------------------------------
    // Debug log — written to getExternalFilesDir/steam_debug.txt
    // -------------------------------------------------------------------------

    private var debugLogFile: File? = null
    val debugLogPath: String get() = debugLogFile?.absolutePath ?: "(not initialized)"

    private fun initDebugLog(ctx: Context) {
        try {
            val dir = ctx.getExternalFilesDir(null)
            if (dir != null) {
                debugLogFile = File(dir, "steam_debug.txt")
                BufferedWriter(FileWriter(debugLogFile!!, false)).use { w ->
                    w.write("=== Steam DepotDownloader Debug Log (JavaSteam native) ===\n")
                    w.write("Engine: JavaSteam DepotDownloader (Ktor CIO)\n")
                    w.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
                }
                dlog("Debug log: ${debugLogFile!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create debug log: ${e.message}")
        }
    }

    private fun dlog(msg: String) {
        Log.i(TAG, msg)
        debugLogFile ?: return
        try {
            BufferedWriter(FileWriter(debugLogFile!!, true)).use { w ->
                val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                w.write("[$ts] $msg\n")
            }
        } catch (_: Exception) {}
    }

    private fun dlogError(msg: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        dlog("$msg: ${t.message}")
        dlog("Stack: $sw")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returned by installApp() / resumeApp() — provides independent cancel and pause controls. */
    class DownloadControl(val cancel: Runnable, val pause: Runnable)

    /** Java-compatible singleton accessor. */
    @JvmStatic fun getInstance(): SteamDepotDownloader = this

    /**
     * Start a fresh install. Returns a DownloadControl with cancel + pause Runnables.
     * @param threads number of parallel chunk downloads + decompression workers (4 / 8 / 16)
     */
    fun installApp(appId: Int, ctx: Context, threads: Int = 4): DownloadControl =
        buildControl(appId, ctx, threads, isResume = false)

    /**
     * Resume a previously paused install. Keeps the existing DB row (bytes intact).
     * DepotDownloader will re-verify and skip already-written chunks where possible.
     */
    fun resumeApp(appId: Int, ctx: Context, threads: Int = 4): DownloadControl =
        buildControl(appId, ctx, threads, isResume = true)

    private fun buildControl(appId: Int, ctx: Context, threads: Int, isResume: Boolean): DownloadControl {
        val cancelled     = AtomicBoolean(false)
        val paused        = AtomicBoolean(false)
        val downloaderRef = AtomicReference<DepotDownloader?>(null)

        CoroutineScope(Dispatchers.IO).launch {
            runInstall(appId, ctx, cancelled, paused, downloaderRef, threads, isResume)
        }

        return DownloadControl(
            cancel = Runnable {
                if (cancelled.compareAndSet(false, true)) {
                    paused.set(false)  // cancel overrides pause
                    dlog("Cancel requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            },
            pause = Runnable {
                if (!cancelled.get() && paused.compareAndSet(false, true)) {
                    dlog("Pause requested for appId=$appId")
                    downloaderRef.get()?.let { try { it.close() } catch (_: Exception) {} }
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // Core install logic
    // -------------------------------------------------------------------------

    private fun runInstall(
        appId: Int,
        ctx: Context,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean,
        downloaderRef: AtomicReference<DepotDownloader?>,
        threads: Int = 4,
        isResume: Boolean = false,
    ) {
        activeDownloads[appId] = Unit
        initDebugLog(ctx)
        dlog("=== Starting install: appId=$appId ===")

        val repo = SteamRepository.getInstance()
        val steamClient = repo.steamClient
        if (steamClient == null) {
            dlog("FAIL: SteamClient is null — not connected to Steam")
            emitFailed(appId, "Not connected to Steam")
            return
        }
        dlog("SteamClient: connected=${repo.isConnected}, loggedIn=${repo.isLoggedIn}")

        // Steam connections cycle, and the re-logon after a reconnect is async — so we can land
        // here connected but not yet logged in (the cached license list hides it). Starting a
        // depot download without a live session makes the manifest job time out → the user sees a
        // bogus "Unknown error". Wait for the session to come back (re-logging-on if needed).
        if (!repo.isLoggedIn) {
            dlog("Not logged in — waiting for session (re-logging-on from saved token if available)…")
            val ok = repo.ensureLoggedIn(15_000L)
            dlog("ensureLoggedIn → $ok (loggedIn=${repo.isLoggedIn})")
            if (!ok) {
                emitFailed(appId, "Steam session not ready — sign in again or retry in a moment")
                return
            }
        }

        val licenses = repo.getLicenses()
        dlog("Licenses: ${licenses.size} entries")
        if (licenses.isEmpty()) {
            dlog("WARNING: license list is empty — DepotDownloader may not find any depots")
        }

        val db = repo.database
        val row = db.getGame(appId)
        if (row == null) {
            dlog("FAIL: appId=$appId not found in database")
            emitFailed(appId, "Game not found in database")
            return
        }
        dlog("Game: name='${row.name}' type=${row.type} sizeBytes=${row.sizeBytes}")

        // Sanitise game name for directory usage
        val safeName = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val installDir = File(File(ctx.filesDir, "imagefs/steam_games"), safeName)
        dlog("Install dir: ${installDir.absolutePath}")

        // Denominators come from the SELECTED-depot sums computed at library sync
        // (SteamRepository now filters out other-OS / non-english / undownloadable depots):
        //   installTotal  = uncompressed bytes written to disk        (row.sizeBytes)
        //   downloadTotal = compressed bytes fetched over the network (repo in-memory cache)
        val hasPicsSize: Boolean
        val installTotal: Long = if (row.sizeBytes > 0L) {
            hasPicsSize = true
            row.sizeBytes
        } else {
            hasPicsSize = false
            db.getDepotManifests(appId).sumOf { it.sizeBytes }.let { if (it > 0L) it else 1L }
        }
        // Compressed (network) total for the download bar. In-memory cache populated by
        // library sync; on a cache miss (resume in a fresh process without a re-sync) fall
        // back to the install total so the bar still has a sane denominator.
        val cachedDownload = repo.getSelectedDownloadSize(appId)
        val downloadTotalSeed: Long = if (cachedDownload > 0L) cachedDownload else installTotal
        dlog("Denominators: install=${fmtSize(installTotal)} download=${fmtSize(downloadTotalSeed)} " +
                "(hasPicsSize=$hasPicsSize, cachedDownload=$cachedDownload)")

        // Queue in DB so UI shows progress (skip reset on resume — keep existing bytes).
        // The DB tracks the INSTALL (uncompressed) bytes/total; compressed is UI-only.
        if (isResume) {
            db.markDownloadResuming(appId)
        } else {
            db.queueDownload(appId, installTotal, installDir.absolutePath)
        }

        // Per-depot cumulative accumulators. DepotDownloader reports cumulative bytes PER
        // DEPOT; a multi-depot game needs these SUMMED or the bar tracks only the largest
        // single depot and stalls partway. installByDepot=uncompressed, downloadByDepot=compressed.
        val installByDepot  = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val downloadByDepot = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        // Resume seeding: the DB persists only install bytes. Seed both bars so neither
        // restarts at 0. The download (compressed) seed is derived from the install fraction
        // since compressed progress isn't persisted — a reasonable approximation.
        val persistedInstall = if (isResume) (db.getDownload(appId)?.bytesDownloaded ?: 0L) else 0L
        val installBase  = persistedInstall
        val downloadBase = if (isResume && installTotal > 0L)
            (persistedInstall.toDouble() / installTotal * downloadTotalSeed).toLong() else 0L
        if (isResume) dlog("Resume seed: install=${fmtSize(installBase)} " +
                "download=${fmtSize(downloadBase)} (download from install-fraction)")

        // Latest aggregate install bytes — read by the pause/complete paths in finally.
        val lastInstallDone = AtomicLong(installBase)
        // Running denominators — grown from chunk data if the seed was too low.
        val installTotalRunning  = AtomicLong(installTotal)
        val downloadTotalRunning = AtomicLong(downloadTotalSeed)

        // Build the 4-field progress event: install pair + download pair.
        fun emitProgress(iDone: Long, iTotal: Long, dDone: Long, dTotal: Long) {
            repo.emit("DownloadProgress:$appId:$iDone:$iTotal:$dDone:$dTotal")
        }

        dlog("Constructing DepotDownloader(androidEmulation=true, maxDownloads=$threads, maxDecompress=$threads, debug=true)")
        val downloader = try {
            DepotDownloader(
                steamClient,
                licenses,
                true,    // debug
                false,   // useLanCache
                threads, // maxDownloads
                threads, // maxDecompress
                100,     // progressUpdateInterval
                true,    // androidEmulation
                null,    // parentJob
            )
        } catch (e: Exception) {
            dlog("FAIL: DepotDownloader constructor threw")
            dlogError("DepotDownloader()", e)
            emitFailed(appId, "DepotDownloader init failed: ${e.message}")
            return
        }
        dlog("DepotDownloader constructed OK")
        downloaderRef.set(downloader)

        downloader.addListener(object : IDownloadListener {
            override fun onDownloadStarted(item: DownloadItem) {
                dlog("onDownloadStarted: appId=${item.appId}")
                emitProgress(installBase, installTotalRunning.get(), downloadBase, downloadTotalRunning.get())
            }

            override fun onStatusUpdate(message: String) {
                dlog("Status: $message")
            }

            override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                val pct = (depotPercentComplete * 100).toInt()
                dlog("File done: depot=$depotId pct=$pct% file=$fileName")
            }

            override fun onChunkCompleted(
                depotId: Int,
                depotPercentComplete: Float,
                compressedBytes: Long,
                uncompressedBytes: Long,
            ) {
                // Both params are cumulative PER DEPOT — keep the latest (monotonic) per
                // depot, then SUM across depots so multi-depot games climb to the true total.
                if (uncompressedBytes > (installByDepot[depotId] ?: 0L)) installByDepot[depotId] = uncompressedBytes
                if (compressedBytes   > (downloadByDepot[depotId] ?: 0L)) downloadByDepot[depotId] = compressedBytes

                // maxOf(base, sessionSum): fresh downloads use the sum directly (base=0);
                // resumes never drop below the persisted floor.
                val installDone  = maxOf(installBase,  installByDepot.values.sum())
                val downloadDone = maxOf(downloadBase, downloadByDepot.values.sum())
                lastInstallDone.set(installDone)

                // Only back-calculate the install denominator when PICS gave no valid size —
                // avoids spiking to inflated values on large depots that sit near 0% for a while.
                if (!hasPicsSize && depotPercentComplete > 0.05f && installDone > 0L) {
                    val implied = (installDone.toDouble() / depotPercentComplete).toLong()
                    if (implied > installTotalRunning.get()) installTotalRunning.set(implied)
                }
                val iTotal = installTotalRunning.get()
                var dTotal = downloadTotalRunning.get()
                // Never let the download bar exceed 100% — grow the denominator if the
                // in-memory estimate was low (e.g. cache miss on resume).
                if (downloadDone > dTotal) { downloadTotalRunning.set(downloadDone); dTotal = downloadDone }

                // Overall % is the aggregate install fraction (what's actually on disk),
                // clamped to 99 — 100% is reserved for onDownloadCompleted.
                val pct = if (iTotal > 0L) minOf((installDone * 100 / iTotal).toInt(), 99) else 0
                dlog("Chunk: depot=$depotId $pct% install=${fmtSize(installDone)}/${fmtSize(iTotal)} " +
                        "download=${fmtSize(downloadDone)}/${fmtSize(dTotal)}")
                emitProgress(installDone, iTotal, downloadDone, dTotal)
                db.updateDownloadProgress(appId, installDone)
            }

            override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                dlog("Depot $depotId complete: ${fmtSize(uncompressedBytes)} uncompressed / ${fmtSize(compressedBytes)} compressed")
            }

            override fun onDownloadCompleted(item: DownloadItem) {
                dlog("=== Download complete: appId=${item.appId} ===")
                val iTotal = installTotalRunning.get()
                val dTotal = downloadTotalRunning.get()
                // Both bars reach 100% before switching to installed state.
                emitProgress(iTotal, iTotal, dTotal, dTotal)
                val finalInstall = maxOf(lastInstallDone.get(), installByDepot.values.sum())
                db.markInstalled(appId, installDir.absolutePath, if (finalInstall > 0L) finalInstall else iTotal)
                repo.emit("DownloadComplete:$appId")
            }

            override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                if (cancelled.get()) {
                    // Cancel path: finally block guarantees DownloadCancelled is emitted.
                    dlog("=== Download cancelled by user: appId=${item.appId} ===")
                } else {
                    dlog("=== Download FAILED: appId=${item.appId} ===")
                    dlogError("onDownloadFailed", error)
                    emitFailed(appId, error.message ?: "Unknown error")
                }
            }
        })

        val item = AppItem(
            appId = appId,
            installDirectory = installDir.absolutePath,
            branch = "public",
            // Explicitly request Windows depots — don't let Util.getSteamOS() guess,
            // since androidEmulation only works if IS_OS_ANDROID is true at runtime.
            os = "windows",
            // Skip arch filtering — we always want the game's Windows depots regardless
            // of what os.arch returns on this Android device (arm64, aarch64, armv8l, etc.).
            // Wine/Box64 handles x86_64 translation; arch mismatch would filter all depots.
            downloadAllArchs = true,
        )
        dlog("Adding AppItem: appId=${item.appId} branch=${item.branch} dir=${item.installDirectory}")
        downloader.add(item)
        downloader.finishAdding()

        dlog("Items added, download auto-starts via getCompletion()")

        dlog("Blocking on getCompletion().get()...")
        var completedNormally = false
        try {
            downloader.getCompletion().get()
            completedNormally = true
            dlog("getCompletion() returned — download finished")
        } catch (e: ExecutionException) {
            dlog("getCompletion() ExecutionException: ${e.cause?.message ?: e.message}")
            dlogError("ExecutionException.cause", e.cause ?: e)
        } catch (e: InterruptedException) {
            dlog("getCompletion() interrupted: ${e.message}")
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            dlog("getCompletion() unexpected exception: ${e.message}")
            dlogError("getCompletion unexpected", e)
        } finally {
            activeDownloads.remove(appId)
            dlog("Closing DepotDownloader")
            try { downloader.close() } catch (_: Exception) {}
            downloaderRef.set(null)
            if (!completedNormally) {
                when {
                    paused.get() -> {
                        // Pause path: keep files + DB row, just mark paused
                        dlog("finally: paused=true — marking DL_PAUSED")
                        db.markDownloadPaused(appId, lastInstallDone.get())
                        repo.emit("DownloadPaused:$appId")
                    }
                    cancelled.get() -> {
                        // Cancel path: delete files + row
                        dlog("finally: cancelled=true — ensuring DownloadCancelled emitted")
                        db.deleteDownload(appId)
                        repo.emit("DownloadCancelled:$appId")
                    }
                }
            }
            dlog("=== runInstall() finished ===")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun emitFailed(appId: Int, reason: String) {
        SteamRepository.getInstance().database.markDownloadFailed(appId, reason)
        SteamRepository.getInstance().emit("DownloadFailed:$appId:$reason")
        Log.e(TAG, "DownloadFailed $appId: $reason")
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}
