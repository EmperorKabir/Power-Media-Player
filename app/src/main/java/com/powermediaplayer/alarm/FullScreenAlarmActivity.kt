package com.powermediaplayer.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.powermediaplayer.data.preferences.SettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * §C12 — full-screen wake activity. Launched via the alarm
 * notification's setFullScreenIntent on Android 14+ (USE_FULL_SCREEN_INTENT
 * special access required); on pre-14 the activity wakes the screen
 * directly via setShowWhenLocked + setTurnScreenOn.
 *
 * Drives the actual ringing (USAGE_ALARM MediaPlayer for DND override),
 * the volume ramp / hold / wind-down, vibration, and the chosen stop
 * method (tap / shake / math). Snooze schedules a follow-up alarm via
 * [AlarmScheduler].
 */
@AndroidEntryPoint
class FullScreenAlarmActivity : ComponentActivity() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ringJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var alarmRecord: AlarmRecord? = null
    private var snoozeCount: Int = 0

    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installLockscreenFlags()

        val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1L)
        snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0)
        if (alarmId < 0) { finish(); return }

        scope.launch {
            val alarm = settingsDataStore.scheduledAlarms.first()
                .firstOrNull { it.id == alarmId } ?: run { finish(); return@launch }
            alarmRecord = alarm
            startRinging(alarm)
            renderUi(alarm)
        }
    }

    private fun installLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        // Keep the screen awake while the user is interacting with the
        // alarm UI; cleared in onDestroy via removing the flag is
        // implicit when the activity is destroyed.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startRinging(alarm: AlarmRecord) {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
        // §C12 snoozeRestartFromStart: when this is a snooze fire and the
        // user picked "continue ramp", begin at end-volume rather than
        // start-volume so they don't hear the gentle wake-up curve again.
        val isSnoozeFire = snoozeCount > 0
        val openVolPct = if (isSnoozeFire && !alarm.snoozeRestartFromStart)
            alarm.endVolumePct else alarm.startVolumePct
        val startStep = (maxVol * openVolPct / 100).coerceIn(0, maxVol)
        audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, startStep, 0)

        // Vibration if requested. Pattern: short pulses with fade
        // amplitude — non-jarring at start, more insistent over time.
        if (alarm.vibration) startVibration(alarm)

        // Build the alarm-channel MediaPlayer with USAGE_ALARM so the
        // route bypasses DND and the audio framework treats this as
        // an alarm rather than music.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val resolved = resolveAlarmMediaUri(alarm.mediaUri)
        val uri = if (resolved.isNotBlank()) android.net.Uri.parse(resolved)
        else android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                isLooping = true
                setDataSource(this@FullScreenAlarmActivity, uri)
                prepare()
                start()
            }
        }.onFailure {
            com.powermediaplayer.util.Diag.w("PMP_DIAG", "Alarm MediaPlayer failed", it)
        }

        ringJob = scope.launch {
            // Volume ramp: linear over rampSeconds from start% to end%.
            // Skipped entirely on a snooze fire when the user chose to
            // continue ramp at end-volume.
            val skipRamp = isSnoozeFire && !alarm.snoozeRestartFromStart
            val rampMs = if (skipRamp) 0L
            else alarm.rampSeconds.coerceAtLeast(0) * 1000L
            if (rampMs > 0) {
                val steps = 30
                val tickMs = rampMs / steps
                for (i in 1..steps) {
                    delay(tickMs)
                    val pct = alarm.startVolumePct +
                        (alarm.endVolumePct - alarm.startVolumePct) * i / steps
                    val step = (maxVol * pct / 100).coerceIn(0, maxVol)
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, step, 0)
                }
            }
            // Hold at end-volume. -1 = indefinite.
            if (alarm.holdMinutes < 0) return@launch
            val holdMs = alarm.holdMinutes * 60_000L
            delay(holdMs)
            // Wind-down fade. 0 = abrupt cut.
            val windMs = alarm.windDownSeconds.coerceAtLeast(0) * 1000L
            if (windMs > 0) {
                val steps = 30
                val tickMs = windMs / steps
                for (i in 1..steps) {
                    delay(tickMs)
                    val pct = alarm.endVolumePct - (alarm.endVolumePct * i / steps)
                    val step = (maxVol * pct / 100).coerceIn(0, maxVol)
                    audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, step, 0)
                }
            }
            stopRinging()
            finish()
        }

        if (alarm.stopMethod == AlarmRecord.StopMethod.SHAKE) installShakeStop(alarm)
    }

    /**
     * §C12 / A4.5 — resolves the alarm's mediaUri. Plain content://
     * + file:// URIs pass through; the "smartplaylist://<id>"
     * pseudo-scheme triggers SmartPlaylistResolver against the
     * MediaStore audio library and picks the first match. The smart
     * playlist's full sequence isn't queued (alarm playback is a
     * single-track ring); we deliberately keep it simple.
     */
    private fun resolveAlarmMediaUri(input: String): String {
        if (input.isBlank()) return ""
        if (!input.startsWith("smartplaylist://")) return input
        val id = input.removePrefix("smartplaylist://").toLongOrNull() ?: return ""
        return runCatching {
            val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(
                applicationContext, AlarmDeps::class.java
            )
            val playlist = kotlinx.coroutines.runBlocking { deps.smartPlaylistDao().getById(id) }
                ?: return ""
            val files = scanLibraryAudioFiles().take(2000)
            val resolved = com.powermediaplayer.playlist.SmartPlaylistResolver.resolve(
                files = files, rulesJson = playlist.rulesJson
            )
            resolved.firstOrNull()?.uri?.toString() ?: ""
        }.getOrDefault("")
    }

    private fun scanLibraryAudioFiles(): List<com.powermediaplayer.ui.library.MediaFileInfo> {
        val ctx = applicationContext
        val proj = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.ALBUM,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.MIME_TYPE,
            android.provider.MediaStore.Audio.Media.SIZE,
            android.provider.MediaStore.Audio.Media.DATE_MODIFIED
        )
        val files = mutableListOf<com.powermediaplayer.ui.library.MediaFileInfo>()
        ctx.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj, null, null, null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
            val titleC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
            val artistC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
            val albumC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM)
            val durC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
            val mimeC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.MIME_TYPE)
            val sizeC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE)
            val dateC = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                files += com.powermediaplayer.ui.library.MediaFileInfo(
                    id = id,
                    uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ),
                    title = c.getString(titleC).orEmpty(),
                    artist = c.getString(artistC).orEmpty(),
                    album = c.getString(albumC).orEmpty(),
                    duration = c.getLong(durC),
                    mimeType = c.getString(mimeC).orEmpty(),
                    size = c.getLong(sizeC),
                    dateModified = c.getLong(dateC),
                    isVideo = false,
                    albumArtUri = null
                )
            }
        }
        return files
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface AlarmDeps {
        fun smartPlaylistDao(): com.powermediaplayer.data.db.dao.SmartPlaylistDao
    }

    private fun startVibration(alarm: AlarmRecord) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        runCatching {
            val pattern = longArrayOf(0, 500, 800)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun installShakeStop(alarm: AlarmRecord) {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        var lastShakeMs = 0L
        shakeListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val g = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
                if (g > 2.7f) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeMs > 1500) {
                        lastShakeMs = now
                        com.powermediaplayer.util.Diag.i("PMP_DIAG",
                            "Alarm shake-stop fired (g=$g)")
                        stopRinging()
                        finish()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager?.registerListener(
            shakeListener, accel, SensorManager.SENSOR_DELAY_UI
        )
    }

    private fun stopRinging() {
        ringJob?.cancel()
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        sensorManager?.unregisterListener(shakeListener)
    }

    private fun snoozeAndFinish() {
        val alarm = alarmRecord ?: run { finish(); return }
        if (!alarm.snoozeEnabled) return
        if (alarm.maxSnoozes >= 0 && snoozeCount >= alarm.maxSnoozes) {
            stopRinging(); finish(); return
        }
        // §C12 C6 fix — deterministic snooze ID, guaranteed unique
        // per (alarm.id, snoozeCount) and disjoint from the master
        // alarm.id PendingIntent space (negative half-plane). Replaces
        // the prior `-alarm.id - millis % 1000` heuristic which could
        // collide for adjacent alarm IDs with neighbouring millis.
        val snoozeId = -(alarm.id * 256L + ((snoozeCount + 1).toLong() and 0xFF))
        AlarmScheduler.scheduleSnooze(
            this, alarm, snoozeId, snoozeCount + 1
        )
        com.powermediaplayer.util.Diag.i(
            "PMP_DIAG",
            "Alarm snooze: id=${alarm.id} count=${snoozeCount + 1}/${alarm.maxSnoozes}"
        )
        stopRinging(); finish()
    }

    private fun renderUi(alarm: AlarmRecord) {
        // Edge-to-edge per locked spec.
        runCatching {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
        setContent {
            val time = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date()) }
            val dayOfWeek = remember {
                java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                    .format(java.util.Date())
            }
            var rampPct by remember { mutableStateOf(alarm.startVolumePct) }
            // Live countdown text driven by the same hold/wind-down
            // boundaries as the ringJob. Renders "Auto-stops in 28 min"
            // during hold and "Winding down…" during the wind-down fade.
            var countdown by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                val totalMs = alarm.rampSeconds * 1000L
                if (totalMs <= 0) rampPct = alarm.endVolumePct
                else {
                    val tick = 200L
                    var elapsed = 0L
                    while (elapsed < totalMs) {
                        delay(tick); elapsed += tick
                        rampPct = alarm.startVolumePct +
                            ((alarm.endVolumePct - alarm.startVolumePct) * elapsed / totalMs).toInt()
                    }
                }
                if (alarm.holdMinutes < 0) { countdown = "Indefinite"; return@LaunchedEffect }
                val holdEnd = System.currentTimeMillis() + alarm.holdMinutes * 60_000L
                while (System.currentTimeMillis() < holdEnd) {
                    val remMin = ((holdEnd - System.currentTimeMillis()) / 60_000L)
                        .coerceAtLeast(0L).toInt()
                    countdown = "Auto-stops in $remMin min"
                    delay(1_000)
                }
                countdown = "Winding down…"
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(time, style = MaterialTheme.typography.displayLarge,
                        color = Color(0xFFB2DFDB))
                    Text(dayOfWeek, style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFCCCCCC))
                    Spacer(Modifier.height(4.dp))
                    Text(alarm.daysLabel, style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFAAAAAA))
                }
                Column(modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // Cover-art placeholder square (locked spec). When
                    // a media item is loaded its mediaMetadata.artworkUri
                    // is fetched via Coil. Falls back to a tinted icon
                    // for the resume / default-alarm-sound case.
                    AlbumArtSquare(alarm)
                    Spacer(Modifier.height(12.dp))
                    val label = alarm.displayLabel.ifBlank {
                        if (alarm.mediaUri.isBlank()) "Default alarm sound"
                        else alarm.mediaUri.substringAfterLast('/').take(60)
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFFFFFFF))
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { rampPct.toFloat() / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Volume: $rampPct%", color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.bodySmall)
                    if (countdown.isNotBlank()) {
                        Text(countdown, color = Color(0xFFB2DFDB),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                StopAndSnoozeBar(
                    alarm = alarm,
                    onStop = {
                        when (alarm.stopMethod) {
                            AlarmRecord.StopMethod.TAP -> { stopRinging(); finish() }
                            AlarmRecord.StopMethod.SHAKE -> { /* handled by sensor */ }
                            AlarmRecord.StopMethod.MATH -> { /* dialog handles */ }
                        }
                    },
                    onSnooze = { snoozeAndFinish() }
                )
            }
        }
    }

    /**
     * §C12 cover-art square. Renders the alarm's mediaUri-derived art
     * when the URI carries one, else a tinted default. Coil handles
     * sizing + placeholder fallback.
     */
    @Composable
    private fun AlbumArtSquare(alarm: AlarmRecord) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(180.dp)
                .background(Color(0xFF1A1A1A), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (alarm.mediaUri.isNotBlank()) {
                coil3.compose.AsyncImage(
                    model = coil3.request.ImageRequest.Builder(ctx)
                        .data(alarm.mediaUri)
                        .build(),
                    contentDescription = "Alarm cover art",
                    modifier = Modifier
                        .size(180.dp)
                        .background(Color.Transparent)
                )
            } else {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Alarm,
                    contentDescription = null,
                    tint = Color(0xFFB2DFDB),
                    modifier = Modifier.size(80.dp)
                )
            }
        }
    }

    @Composable
    private fun StopAndSnoozeBar(
        alarm: AlarmRecord,
        onStop: () -> Unit,
        onSnooze: () -> Unit
    ) {
        var showMath by remember { mutableStateOf(false) }
        var mathInput by remember { mutableStateOf("") }
        val a = remember { (10..99).random() }
        val b = remember { (10..99).random() }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // §C12 LOCKED — large rounded Snooze button at 70% width.
            if (alarm.snoozeEnabled) {
                androidx.compose.material3.Button(
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(72.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
                ) {
                    Text(
                        "Snooze ${alarm.snoozeMinutes} min",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // §C12 LOCKED — Stop requires swipe-to-confirm on TAP mode.
            // SHAKE and MATH still surface their button label for
            // discoverability but the stop signal travels via the
            // sensor / dialog.
            SwipeToStopButton(
                label = when (alarm.stopMethod) {
                    AlarmRecord.StopMethod.TAP -> "Swipe to stop"
                    AlarmRecord.StopMethod.SHAKE -> "Shake to stop"
                    AlarmRecord.StopMethod.MATH -> "Solve to stop"
                },
                onConfirm = {
                    if (alarm.stopMethod == AlarmRecord.StopMethod.MATH) showMath = true
                    else onStop()
                }
            )
            if (showMath) {
                Spacer(Modifier.height(8.dp))
                Text("Solve: $a + $b = ?", color = Color.White)
                OutlinedTextField(
                    value = mathInput,
                    onValueChange = { mathInput = it.filter(Char::isDigit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row {
                    TextButton(onClick = { showMath = false }) { Text("Cancel") }
                    Button(onClick = {
                        if (mathInput.toIntOrNull() == a + b) {
                            stopRinging(); finish()
                        } else {
                            mathInput = ""
                        }
                    }) { Text("OK") }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        scope.cancel()
    }

    /**
     * §C12 LOCKED — swipe-to-confirm Stop button. Drag the inner thumb
     * 60% across the track to confirm; tap-only is intentionally
     * insufficient (otherwise an accidental knee-tap kills the alarm).
     * Visual: tall rounded pill with a sliding thumb + label that
     * fades out as the user drags.
     */
    @Composable
    private fun SwipeToStopButton(
        label: String,
        onConfirm: () -> Unit
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trackHeightDp = 64.dp
        var trackWidthPx by remember { mutableStateOf(0f) }
        var dragX by remember { mutableStateOf(0f) }
        val thumbSize = with(density) { trackHeightDp.toPx() }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
                .background(
                    Color(0xFF1A1A1A),
                    androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
                )
                .onSizeChanged { trackWidthPx = it.width.toFloat() },
            contentAlignment = Alignment.CenterStart
        ) {
            val labelAlpha = (1f - (dragX / (trackWidthPx - thumbSize).coerceAtLeast(1f)))
                .coerceIn(0f, 1f)
            Text(
                label,
                color = Color(0xFFFFFFFF).copy(alpha = labelAlpha),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = trackHeightDp + 8.dp)
            )
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(dragX.toInt(), 0) }
                    .size(trackHeightDp)
                    .background(
                        Color(0xFFB2DFDB),
                        androidx.compose.foundation.shape.CircleShape
                    )
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragX > (trackWidthPx - thumbSize) * 0.6f) onConfirm()
                                else dragX = 0f
                            },
                            onHorizontalDrag = { _: androidx.compose.ui.input.pointer.PointerInputChange, delta: Float ->
                                dragX = (dragX + delta).coerceIn(0f, trackWidthPx - thumbSize)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.PlayArrow,
                    contentDescription = "Swipe to stop",
                    tint = Color(0xFF000000)
                )
            }
        }
    }

    companion object {
        const val EXTRA_SNOOZE_COUNT = "snooze_count"
        fun intent(context: Context, alarmId: Long, snoozeCount: Int = 0): Intent =
            Intent(context, FullScreenAlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
    }
}
