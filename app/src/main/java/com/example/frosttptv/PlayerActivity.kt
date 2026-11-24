package com.example.frosttptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private enum class VideoFitMode(val label: String, val resizeMode: Int, val scaleX: Float, val scaleY: Float) {
        FIT("Ajuste (16:9)", AspectRatioFrameLayout.RESIZE_MODE_FIT, 1f, 1f),
        ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, 1.15f, 1.15f),
        CINEMA("Cine (21:9)", AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.12f, 0.9f),
        FILL("Pantalla completa", AspectRatioFrameLayout.RESIZE_MODE_FILL, 1f, 1f)
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPrevEpisode: MaterialButton
    private lateinit var btnNextEpisode: MaterialButton
    private lateinit var btnLockToggle: MaterialButton
    private lateinit var gestureHud: TextView
    private lateinit var unlockButton: MaterialButton
    private lateinit var audioManager: AudioManager

    private var fitMode = VideoFitMode.FIT
    private var isScreenLocked = false
    private var isVodOrSeries = false

    private var episodeUrls: List<String> = emptyList()
    private var episodeTitles: List<String> = emptyList()
    private var currentEpisodeIndex: Int = 0
    private var activeUserId: String = ""

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureMode: String? = null // "BRIGHTNESS" or "VOLUME"
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 15

    private val prefs by lazy { getSharedPreferences("frostt_preferences", MODE_PRIVATE) }

    private val hideUnlockRunnable = Runnable {
        if (isScreenLocked) {
            unlockButton.visibility = View.GONE
        }
    }

    private val hideHudRunnable = Runnable {
        gestureHud.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val initialUrl = intent.getStringExtra("EXTRA_STREAM_URL").orEmpty()
        val initialTitle = intent.getStringExtra("EXTRA_CHANNEL_NAME").orEmpty().ifBlank { "Reproducción" }
        val extraUrls = intent.getStringArrayListExtra("EXTRA_EPISODE_URLS").orEmpty()
        val extraTitles = intent.getStringArrayListExtra("EXTRA_EPISODE_TITLES").orEmpty()
        isVodOrSeries = intent.getBooleanExtra("EXTRA_IS_VOD", false)
        activeUserId = intent.getStringExtra("EXTRA_USER_ID").orEmpty()
        val resumePositionMs = intent.getLongExtra("EXTRA_RESUME_POSITION_MS", 0L)

        episodeUrls = if (extraUrls.isNotEmpty()) extraUrls else listOf(initialUrl)
        episodeTitles = if (extraTitles.size == episodeUrls.size) extraTitles else episodeUrls.mapIndexed { idx, _ ->
            if (idx == 0) initialTitle else "Capítulo ${idx + 1}"
        }
        currentEpisodeIndex = intent.getIntExtra("EXTRA_EPISODE_INDEX", 0)
            .coerceIn(0, (episodeUrls.size - 1).coerceAtLeast(0))

        val startUrl = episodeUrls.getOrNull(currentEpisodeIndex).orEmpty()
        if (startUrl.isBlank()) {
            Toast.makeText(this, "URL de transmisión inválida.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = fitMode.resizeMode
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(playerView)

        val gestureLayer = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (isScreenLocked) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        showUnlockPromptTemporarily()
                    }
                    return true
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureStartX = event.x
                        gestureStartY = event.y
                        gestureMode = null
                        val currentBr = window.attributes.screenBrightness
                        initialBrightness = if (currentBr < 0f) 0.5f else currentBr.coerceIn(0.05f, 1f)
                        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - gestureStartX
                        val dy = gestureStartY - event.y
                        if (gestureMode == null && abs(dy) > dp(18) && abs(dy) > abs(dx)) {
                            gestureMode = if (gestureStartX < width * 0.5f) "BRIGHTNESS" else "VOLUME"
                        }

                        if (gestureMode == "BRIGHTNESS") {
                            val delta = dy / (height * 0.55f).coerceAtLeast(1f)
                            val newBr = (initialBrightness + delta).coerceIn(0.05f, 1.0f)
                            val attrs = window.attributes
                            attrs.screenBrightness = newBr
                            window.attributes = attrs
                            val percent = ((newBr - 0.05f) / 0.95f * 100f).toInt().coerceIn(0, 100)
                            showHud("Brillo: $percent%")
                            return true
                        } else if (gestureMode == "VOLUME") {
                            val deltaSteps = (dy / (height * 0.55f).coerceAtLeast(1f)) * maxVolume
                            val newVol = (initialVolume + deltaSteps).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            val percent = ((newVol.toFloat() / maxVolume) * 100f).toInt().coerceIn(0, 100)
                            showHud("Volumen: $percent%")
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasGesture = gestureMode != null
                        gestureMode = null
                        hideHudDelayed()
                        if (!wasGesture && event.actionMasked == MotionEvent.ACTION_UP) {
                            toggleControlsVisibility()
                        }
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(gestureLayer)

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#8805050A"))
            setPadding(dp(12), dp(26), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val btnBack = createControlButton("←").apply {
            setOnClickListener { finish() }
        }
        tvTitle = TextView(this).apply {
            text = episodeTitles.getOrNull(currentEpisodeIndex) ?: initialTitle
            textSize = 14f
            maxLines = 1
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
        }
        btnLockToggle = createControlButton("🔓").apply {
            setOnClickListener { setScreenLocked(true) }
        }

        topBar.addView(btnBack)
        topBar.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(btnLockToggle)
        rootLayout.addView(topBar)

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#8805050A"))
            setPadding(dp(12), dp(8), dp(12), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        btnPrevEpisode = createControlButton("⏮").apply {
            setOnClickListener { playEpisodeAt(currentEpisodeIndex - 1) }
        }
        btnPlayPause = createControlButton("❚❚").apply {
            setOnClickListener {
                if (player.isPlaying) {
                    player.pause()
                    text = "▶"
                } else {
                    player.play()
                    text = "❚❚"
                }
            }
        }
        btnNextEpisode = createControlButton("⏭").apply {
            setOnClickListener { playEpisodeAt(currentEpisodeIndex + 1) }
        }

        val hasQueue = episodeUrls.size > 1
        btnPrevEpisode.visibility = if (hasQueue) View.VISIBLE else View.GONE
        btnNextEpisode.visibility = if (hasQueue) View.VISIBLE else View.GONE

        val btnSettingsGear = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "⚙"
            textSize = 20f
            isAllCaps = false
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(40)
            minimumHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            setOnClickListener { showPlayerSettingsDialog() }
        }

        bottomBar.addView(btnPrevEpisode)
        bottomBar.addView(btnPlayPause, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
        bottomBar.addView(btnNextEpisode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottomBar.addView(btnSettingsGear)
        rootLayout.addView(bottomBar)

        gestureHud = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#CC141424"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        rootLayout.addView(gestureHud)

        unlockButton = MaterialButton(this).apply {
            text = "🔒 Desbloquear"
            isAllCaps = false
            visibility = View.GONE
            cornerRadius = dp(20)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CC6C47FF"))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(36)
            }
            setOnClickListener { setScreenLocked(false) }
        }
        rootLayout.addView(unlockButton)

        setContentView(rootLayout)
        hideSystemBars()

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(startUrl))
            if (isVodOrSeries && resumePositionMs > 0L) {
                exo.seekTo(resumePositionMs)
            }
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlayPause.text = if (isPlaying) "❚❚" else "▶"
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && episodeUrls.size > 1 &&
                        currentEpisodeIndex < episodeUrls.lastIndex
                    ) {
                        playEpisodeAt(currentEpisodeIndex + 1)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Error al reproducir: ${error.localizedMessage ?: "stream no disponible"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }

        updateEpisodeButtonsState()
    }

    private fun playEpisodeAt(targetIndex: Int) {
        if (targetIndex !in episodeUrls.indices) return
        saveCurrentWatchProgress()
        currentEpisodeIndex = targetIndex
        val nextUrl = episodeUrls[targetIndex]
        val nextTitle = episodeTitles.getOrNull(targetIndex) ?: "Capítulo ${targetIndex + 1}"
        tvTitle.text = nextTitle

        val savedPos = readSavedProgressForUrl(nextUrl)
        player.setMediaItem(MediaItem.fromUri(nextUrl))
        if (isVodOrSeries && savedPos > 0L) {
            player.seekTo(savedPos)
        }
        player.prepare()
        player.playWhenReady = true
        updateEpisodeButtonsState()
        showHud(nextTitle)
        hideHudDelayed()
    }

    private fun updateEpisodeButtonsState() {
        btnPrevEpisode.isEnabled = currentEpisodeIndex > 0
        btnPrevEpisode.alpha = if (btnPrevEpisode.isEnabled) 1f else 0.45f
        btnNextEpisode.isEnabled = currentEpisodeIndex < episodeUrls.lastIndex
        btnNextEpisode.alpha = if (btnNextEpisode.isEnabled) 1f else 0.45f
    }

    private fun readSavedProgressForUrl(url: String): Long {
        if (!isVodOrSeries || url.isBlank() || activeUserId.isBlank()) return 0L
        val raw = prefs.getString("local_users_json", null) ?: return 0L
        return runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val uObj = arr.optJSONObject(i) ?: continue
                if (uObj.optString("id") == activeUserId) {
                    val progObj = uObj.optJSONObject("watchProgress") ?: return@runCatching 0L
                    val entry = progObj.optJSONObject(url) ?: return@runCatching 0L
                    return@runCatching entry.optLong("positionMs", 0L)
                }
            }
            0L
        }.getOrDefault(0L)
    }

    private fun saveCurrentWatchProgress() {
        if (!::player.isInitialized || !isVodOrSeries || activeUserId.isBlank()) return
        val currentUrl = episodeUrls.getOrNull(currentEpisodeIndex).orEmpty()
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        if (currentUrl.isBlank() || pos < 5_000L) return

        val raw = prefs.getString("local_users_json", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val uObj = arr.optJSONObject(i) ?: continue
                if (uObj.optString("id") == activeUserId) {
                    val progObj = uObj.optJSONObject("watchProgress") ?: JSONObject().also {
                        uObj.put("watchProgress", it)
                    }
                    progObj.put(
                        currentUrl,
                        JSONObject().apply {
                            put("positionMs", pos)
                            put("durationMs", dur)
                        }
                    )
                    prefs.edit().putString("local_users_json", arr.toString()).apply()
                    break
                }
            }
        }
    }

    private fun createControlButton(label: String): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            cornerRadius = dp(16)
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(10), 0, dp(10), 0)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6C47FF"))
            setTextColor(Color.WHITE)
        }
    }

    private fun setScreenLocked(locked: Boolean) {
        isScreenLocked = locked
        if (locked) {
            btnLockToggle.text = "🔒"
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            showUnlockPromptTemporarily()
        } else {
            unlockButton.removeCallbacks(hideUnlockRunnable)
            unlockButton.visibility = View.GONE
            btnLockToggle.text = "🔓"
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            showHud("🔓 Pantalla desbloqueada")
            hideHudDelayed()
        }
    }

    private fun showUnlockPromptTemporarily() {
        if (!isScreenLocked) return
        unlockButton.removeCallbacks(hideUnlockRunnable)
        unlockButton.visibility = View.VISIBLE
        unlockButton.postDelayed(hideUnlockRunnable, 3000L)
    }

    private fun toggleControlsVisibility() {
        val show = topBar.visibility != View.VISIBLE
        val vis = if (show) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
    }

    private fun showPlayerSettingsDialog() {
        val options = arrayOf(
            "Idioma / Audio",
            "Subtítulos",
            "Ajuste de video (${fitMode.label})"
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes de reproducción")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAudioTrackDialog()
                    1 -> showSubtitleTrackDialog()
                    2 -> showVideoFitDialog()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showVideoFitDialog() {
        val modes = VideoFitMode.entries
        val labels = modes.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ajuste de video")
            .setItems(labels) { _, which ->
                fitMode = modes[which]
                playerView.resizeMode = fitMode.resizeMode
                playerView.videoSurfaceView?.apply {
                    scaleX = fitMode.scaleX
                    scaleY = fitMode.scaleY
                }
                showHud("Video: ${fitMode.label}")
                hideHudDelayed()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAudioTrackDialog() {
        data class TrackOption(val label: String, val group: Tracks.Group, val trackIndex: Int)
        val options = mutableListOf<TrackOption>()

        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.takeIf { it.isNotBlank() }?.let {
                        Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
                    } ?: "Pista ${options.size + 1}"
                    val extra = listOfNotNull(
                        format.label?.takeIf { it.isNotBlank() },
                        format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
                    ).joinToString(" · ")
                    val fullLabel = if (extra.isNotBlank()) "$lang ($extra)" else lang
                    options.add(TrackOption(fullLabel, group, i))
                }
            }
        }

        if (options.isEmpty()) {
            Toast.makeText(this, "Este stream solo tiene una pista de audio o no reporta idiomas.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Idioma y sonido")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val chosen = options[which]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(chosen.group.mediaTrackGroup, listOf(chosen.trackIndex))
                    )
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
                Toast.makeText(this, "Audio: ${chosen.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showSubtitleTrackDialog() {
        data class SubOption(val label: String, val group: Tracks.Group?, val trackIndex: Int)
        val options = mutableListOf<SubOption>()
        options.add(SubOption("Desactivar subtítulos", null, -1))

        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.takeIf { it.isNotBlank() }?.let {
                        Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
                    } ?: format.label?.takeIf { it.isNotBlank() } ?: "Subtítulo ${options.size}"
                    options.add(SubOption(lang, group, i))
                }
            }
        }

        if (options.size == 1) {
            Toast.makeText(this, "No hay pistas de subtítulos disponibles en esta transmisión.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Subtítulos")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val chosen = options[which]
                val builder = player.trackSelectionParameters.buildUpon()
                if (chosen.group == null) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(chosen.group.mediaTrackGroup, listOf(chosen.trackIndex))
                        )
                }
                player.trackSelectionParameters = builder.build()
                Toast.makeText(this, "Subtítulos: ${chosen.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showHud(message: String) {
        gestureHud.removeCallbacks(hideHudRunnable)
        gestureHud.text = message
        gestureHud.visibility = View.VISIBLE
    }

    private fun hideHudDelayed() {
        gestureHud.removeCallbacks(hideHudRunnable)
        gestureHud.postDelayed(hideHudRunnable, 900L)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        saveCurrentWatchProgress()
        super.onPause()
    }

    override fun onDestroy() {
        saveCurrentWatchProgress()
        unlockButton.removeCallbacks(hideUnlockRunnable)
        gestureHud.removeCallbacks(hideHudRunnable)
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }
}
