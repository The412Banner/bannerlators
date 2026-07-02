package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.theme.WinlatorTheme
import java.io.File
import java.net.URL

/** Semantic action carried by the install button; the colour resolves inside the composable
 *  (install/retry = primary, cancel/uninstall = error) so theme presets recolor it live. */
private enum class InstallAction { INSTALL, CANCEL, UNINSTALL, RETRY }

/** Semantic pause-button mode: PAUSE renders as a calm surfaceVariant container, RESUME as primary. */
private enum class PauseAction { PAUSE, RESUME }

/** Semantic install status; colour resolves inside the composable (installed = green,
 *  failed = error, everything else = onSurfaceVariant). */
private enum class GameStatus { NOT_INSTALLED, INSTALLED, CANCELLED, FAILED }

class SteamGameDetailActivity : ComponentActivity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
    }

    private var appId: Int = 0
    private var game by mutableStateOf<SteamGame?>(null)

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null
    private var lastThreadCount = 4

    // UI state
    private var headerBitmap by mutableStateOf<Bitmap?>(null)
    private var nameText by mutableStateOf("Loading…")
    private var typeText by mutableStateOf("GAME")
    private var sizeText by mutableStateOf("Size unknown")
    private var statusText by mutableStateOf("Not installed")
    private var gameStatus by mutableStateOf(GameStatus.NOT_INSTALLED)
    private var installBtnText by mutableStateOf("Install")
    private var installAction by mutableStateOf(InstallAction.INSTALL)
    private var installBtnEnabled by mutableStateOf(true)
    private var pauseBtnText by mutableStateOf("Pause")
    private var pauseAction by mutableStateOf(PauseAction.PAUSE)
    private var pauseBtnEnabled by mutableStateOf(false)
    private var launchBtnEnabled by mutableStateOf(false)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    private var progressText by mutableStateOf("")
    private var progressTextVisible by mutableStateOf(false)

    private var showSpeedPicker by mutableStateOf(false)
    private var showExePicker by mutableStateOf<ExePickerDataGame?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)

        setContent {
            WinlatorTheme {
                SteamGameDetailScreen(
                    headerBitmap = headerBitmap,
                    nameText = nameText,
                    typeText = typeText,
                    sizeText = sizeText,
                    statusText = statusText,
                    gameStatus = gameStatus,
                    installBtnText = installBtnText,
                    installAction = installAction,
                    installBtnEnabled = installBtnEnabled,
                    pauseBtnText = pauseBtnText,
                    pauseAction = pauseAction,
                    pauseBtnEnabled = pauseBtnEnabled,
                    launchBtnEnabled = launchBtnEnabled,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    progressText = progressText,
                    progressTextVisible = progressTextVisible,
                    onBack = { finish() },
                    onInstallClick = { onInstallClicked() },
                    onPauseResumeClick = { onPauseResumeClicked() },
                    onLaunchClick = { onLaunchClicked() },
                )

                if (showSpeedPicker) {
                    DownloadSpeedPickerDialog(
                        selectedIndex = when (lastThreadCount) { 8 -> 1; 16 -> 2; else -> 0 },
                        onDismiss = { showSpeedPicker = false },
                        onDownload = { threadCount ->
                            showSpeedPicker = false
                            lastThreadCount = threadCount
                            installBtnEnabled = false
                            installBtnText = "Starting…"
                            downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, lastThreadCount)
                        },
                    )
                }

                showExePicker?.let { data ->
                    ExePickerDialogGame(
                        gameName = data.gameName,
                        candidates = data.candidates,
                        onDismiss = { showExePicker = null },
                        onSelected = { chosen ->
                            showExePicker = null
                            StarLaunchBridge.addToLauncher(
                                this@SteamGameDetailActivity, data.gameName, chosen, data.coverUrl
                            )
                        },
                    )
                }
            }
        }

        SteamRepository.getInstance().addListener(this)
        loadGame()
        loadHeaderImage()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    override fun onEvent(event: String) {
        when {
            event.startsWith("DownloadProgress:") -> {
                val parts = event.split(":")
                val id    = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val done  = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val total = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = pct
                progressTextVisible = true
                progressText = "Downloading… $pct%  (${fmtSize(done)} / ${fmtSize(total)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = "Pause"
                pauseAction = PauseAction.PAUSE
            }
            event.startsWith("DownloadPaused:") -> {
                val id = event.substringAfter("DownloadPaused:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                val dlRow = SteamRepository.getInstance().database.getDownload(appId)
                val done  = dlRow?.bytesDownloaded ?: 0L
                val total = dlRow?.bytesTotal ?: 0L
                val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = pct
                progressTextVisible = true
                progressText = "Paused — $pct%  (${fmtSize(done)} / ${fmtSize(total)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = "Resume"
                pauseAction = PauseAction.RESUME
            }
            event.startsWith("DownloadComplete:") -> {
                val id = event.substringAfter("DownloadComplete:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                resetPauseBtn()
                loadGame()
            }
            event.startsWith("DownloadCancelled:") -> {
                val id = event.substringAfter("DownloadCancelled:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                statusText = "Download cancelled"
                gameStatus = GameStatus.CANCELLED
                installBtnEnabled = true
                installBtnText = "Install"
                installAction = InstallAction.INSTALL
                resetPauseBtn()
            }
            event.startsWith("DownloadFailed:") -> {
                val parts = event.split(":")
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val reason = parts.drop(2).joinToString(":")
                val logPath = SteamDepotDownloader.debugLogPath
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                statusText = "Download failed: $reason\nDebug log: $logPath"
                gameStatus = GameStatus.FAILED
                installBtnEnabled = true
                installBtnText = "Retry"
                installAction = InstallAction.RETRY
                resetPauseBtn()
            }
        }
    }

    private fun resetPauseBtn() {
        pauseBtnEnabled = false
        pauseBtnText = "Pause"
        pauseAction = PauseAction.PAUSE
    }

    private fun loadGame() {
        val row = SteamRepository.getInstance().database.getGame(appId) ?: run { finish(); return }
        game = SteamGame.fromGameRow(row)
        refreshUI()

        val dlRow = SteamRepository.getInstance().database.getDownload(appId)
        if (dlRow != null) {
            val pct = if (dlRow.bytesTotal > 0) (dlRow.bytesDownloaded * 100 / dlRow.bytesTotal).toInt().coerceIn(0, 100) else 0
            when (dlRow.status) {
                SteamDatabase.DL_DOWNLOADING -> {
                    if (SteamDepotDownloader.isDownloading(appId)) {
                        progressVisible = true
                        progressValue = pct
                        progressTextVisible = true
                        progressText = "Downloading… $pct%"
                        installBtnEnabled = true
                        installBtnText = "Cancel"
                        installAction = InstallAction.CANCEL
                        pauseBtnEnabled = true
                        pauseBtnText = "Pause"
                        pauseAction = PauseAction.PAUSE
                    } else {
                        SteamRepository.getInstance().database.deleteDownload(appId)
                    }
                }
                SteamDatabase.DL_PAUSED -> {
                    progressVisible = true
                    progressValue = pct
                    progressTextVisible = true
                    progressText = "Paused — $pct%  (${fmtSize(dlRow.bytesDownloaded)} / ${fmtSize(dlRow.bytesTotal)})"
                    installBtnEnabled = true
                    installBtnText = "Cancel"
                    installAction = InstallAction.CANCEL
                    pauseBtnEnabled = true
                    pauseBtnText = "Resume"
                    pauseAction = PauseAction.RESUME
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText = g.name.ifEmpty { "App ${g.appId}" }
        typeText = g.type.uppercase()
        sizeText = if (g.sizeBytes > 0) "~${fmtSize(g.sizeBytes)}" else "Size unknown"

        if (g.isInstalled) {
            statusText = "Installed"
            gameStatus = GameStatus.INSTALLED
            installBtnText = "Uninstall"
            installAction = InstallAction.UNINSTALL
            installBtnEnabled = true
            launchBtnEnabled = true
        } else {
            statusText = "Not installed"
            gameStatus = GameStatus.NOT_INSTALLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            launchBtnEnabled = false
        }
    }

    private fun loadHeaderImage() {
        val g = game ?: return
        val url = g.headerUrl ?: return
        Thread {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                headerBitmap = bmp
            } catch (_: Exception) {}
        }.start()
    }

    private fun onInstallClicked() {
        val g = game ?: return

        val handle = downloadHandle
        if (handle != null) {
            val db = SteamRepository.getInstance().database
            val dir = db.getDownload(appId)?.installDir ?: ""
            handle.cancel.run()
            downloadHandle = null
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressVisible = false
            progressTextVisible = false
            statusText = "Download cancelled"
            gameStatus = GameStatus.CANCELLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        val db = SteamRepository.getInstance().database
        val dlRow = db.getDownload(appId)
        if (dlRow != null && dlRow.status == SteamDatabase.DL_PAUSED) {
            db.deleteDownload(appId)
            val dir = dlRow.installDir
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressVisible = false
            progressTextVisible = false
            statusText = "Download cancelled"
            gameStatus = GameStatus.CANCELLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        if (g.isInstalled) {
            SteamRepository.getInstance().database.markUninstalled(appId)
            if (g.installDir.isNotEmpty()) {
                Thread { File(g.installDir).deleteRecursively() }.start()
            }
            loadGame()
        } else {
            showSpeedPicker = true
        }
    }

    private fun onPauseResumeClicked() {
        val handle = downloadHandle
        if (handle != null) {
            handle.pause.run()
            downloadHandle = null
            pauseBtnText = "Resume"
            pauseAction = PauseAction.RESUME
            pauseBtnEnabled = true
            installBtnText = "Cancel"
            installBtnEnabled = true
            val cur = progressText
            if (cur.startsWith("Downloading")) progressText = cur.replace("Downloading", "Pausing")
        } else {
            val dlRow = SteamRepository.getInstance().database.getDownload(appId) ?: return
            if (dlRow.status != SteamDatabase.DL_PAUSED) return
            pauseBtnEnabled = false
            pauseBtnText = "Resuming…"
            installBtnEnabled = false
            installBtnText = "Starting…"
            downloadHandle = SteamDepotDownloader.resumeApp(appId, applicationContext, lastThreadCount)
        }
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            Toast.makeText(this, "Game not installed", Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No .exe found in install directory", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val lowerTitle = g.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${g.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                runOnUiThread { StarLaunchBridge.addToLauncher(this, g.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker = ExePickerDataGame(g.name, candidates, coverUrl)
            }
        }.start()
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}

private data class ExePickerDataGame(
    val gameName: String,
    val candidates: List<String>,
    val coverUrl: String,
)

// --- Composable Screens ---

@Composable
private fun SteamGameDetailScreen(
    headerBitmap: Bitmap?,
    nameText: String,
    typeText: String,
    sizeText: String,
    statusText: String,
    gameStatus: GameStatus,
    installBtnText: String,
    installAction: InstallAction,
    installBtnEnabled: Boolean,
    pauseBtnText: String,
    pauseAction: PauseAction,
    pauseBtnEnabled: Boolean,
    launchBtnEnabled: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    progressText: String,
    progressTextVisible: Boolean,
    onBack: () -> Unit,
    onInstallClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onLaunchClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // Hero image with a subtle gradient into the background at the bottom
        Box(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (headerBitmap != null) {
                Image(
                    bitmap = headerBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
        }

        // Info section
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                text = nameText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoChip(typeText)
                Spacer(Modifier.width(8.dp))
                InfoChip(sizeText)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (gameStatus) {
                    GameStatus.INSTALLED -> Color(0xFF4CAF50) // semantic installed-green
                    GameStatus.FAILED    -> MaterialTheme.colorScheme.error
                    else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Progress
        if (progressVisible) {
            LinearProgressIndicator(
                progress = { progressValue / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (progressTextVisible) {
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onInstallClick,
                enabled = installBtnEnabled,
                colors = when (installAction) {
                    InstallAction.CANCEL, InstallAction.UNINSTALL -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                    else -> ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text(installBtnText, maxLines = 1) }

            Button(
                onClick = onPauseResumeClick,
                enabled = pauseBtnEnabled,
                colors = when (pauseAction) {
                    PauseAction.PAUSE -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    PauseAction.RESUME -> ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text(pauseBtnText, maxLines = 1) }

            Button(
                onClick = onLaunchClick,
                enabled = launchBtnEnabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Launch", maxLines = 1) }
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadSpeedPickerDialog(
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onDownload: (threadCount: Int) -> Unit,
) {
    val options = listOf(
        "Safe (4 threads) — least RAM/CPU usage" to 4,
        "Normal (8 threads) — balanced" to 8,
        "Fast (16 threads) — maximum speed" to 16,
    )
    var selected by remember { mutableIntStateOf(selectedIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download speed") },
        text = {
            Column {
                options.forEachIndexed { index, (label, _) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selected == index,
                            onClick = { selected = index },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(options[selected].second) }) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExePickerDialogGame(
    gameName: String,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select executable for \"$gameName\"") },
        text = {
            Column {
                candidates.forEach { path ->
                    val f = java.io.File(path)
                    val parent = f.parentFile
                    val label = if (parent != null) "${parent.name}/${f.name}" else f.name
                    TextButton(
                        onClick = { onSelected(path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.weight(1f)) }
                }
            }
        },
        confirmButton = {},
    )
}
