package com.wildrift.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.webrtc.*
import java.util.Collections
import java.util.Locale
import java.util.UUID

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var videoView: View
    private lateinit var videoRenderer: SurfaceViewRenderer
    private lateinit var tvStaticHud: TextView
    private lateinit var videoParams: WindowManager.LayoutParams

    private lateinit var floatingWidgetView: View
    private lateinit var btnBubble: FrameLayout
    private lateinit var llSettingsMenu: LinearLayout
    private lateinit var btnToggleVisibility: Button
    private lateinit var btnModeToggle: Button
    private lateinit var btnRes720: Button
    private lateinit var btnRes1080: Button
    private lateinit var btnRes540: Button
    private lateinit var btnFps45: Button
    private lateinit var btnFps60: Button
    private lateinit var btnFps30: Button
    private lateinit var btnAudioToggle: Button
    private lateinit var btnHudToggle: Button
    private lateinit var btnPacer: Button
    private lateinit var btnEncoder: Button
    private lateinit var btnBandwidth: Button
    private lateinit var btnMenuClose: Button
    private lateinit var floatingParams: WindowManager.LayoutParams

    private var isVideoVisible = true
    private var isConnectedToPeer = false
    private var isManualMode = true
    private var currentResProfile = "720p"
    private var currentFps = 60
    private var audioEnabled = true
    private var hudVisible = true
    private var pacerDepth = 0                // 0 = kapalı (en düşük gecikme), 1-2 = akıcılık için bekletilen kare
    private var encoderLowLatency = true      // true = HIZLI (B-kare kapalı), false = cihaz varsayılanı
    private var bandwidthPercent = 100        // yayın bitrate tavanının yüzdesi
    private lateinit var prefs: SharedPreferences
    private var framePacer: FramePacer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val deviceMaxProfile: String by lazy { DeviceCaps.maxSupportedProfile(30) }

    private val colorActive = ColorStateList.valueOf(Color.parseColor("#00C853"))
    private val colorInactive = ColorStateList.valueOf(Color.parseColor("#424242"))

    private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    private var signalingClient: SignalingClient? = null
    private val pendingIceCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val pcLock = Any()

    private var audioPlayer: AudioPlayer? = null
    private var audioChannel: DataChannel? = null

    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable? = null
    private var peerLostRunnable: Runnable? = null
    private var lastFramesDecoded: Long = 0
    private var lastStatsTime: Long = 0
    private var prevBytes: Long = 0
    private var prevJbDelay: Double = 0.0
    private var prevJbCount: Long = 0
    private var prevDecTime: Double = 0.0
    private var prevDecFrames: Long = 0
    private var prevLost: Long = 0
    private var prevNack: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSettings()
        acquireWifiLock()
        startForegroundServiceNotification()
    }

    /** Son kullanılan ayarları hatırlar (uygulama kapanıp açılınca tekrar seçmek gerekmez). */
    private fun loadSettings() {
        prefs = getSharedPreferences("pulselink", Context.MODE_PRIVATE)
        isManualMode = prefs.getBoolean("manual", true)
        currentFps = prefs.getInt("fps", 60).let { if (it == 30 || it == 45 || it == 60) it else 60 }
        audioEnabled = prefs.getBoolean("audio", true)
        hudVisible = prefs.getBoolean("hud", true)
        pacerDepth = prefs.getInt("pacer", 0).coerceIn(0, 2)
        encoderLowLatency = prefs.getBoolean("enc", true)
        bandwidthPercent = prefs.getInt("bw", 100).let { if (it == 100 || it == 75 || it == 50) it else 100 }
        val savedRes = prefs.getString("res", "720p") ?: "720p"
        currentResProfile = if (DeviceCaps.isProfileSupported(savedRes, currentFps)) savedRes else "720p"
    }

    private fun saveSettings() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putBoolean("manual", isManualMode)
            .putInt("fps", currentFps)
            .putBoolean("audio", audioEnabled)
            .putBoolean("hud", hudVisible)
            .putInt("pacer", pacerDepth)
            .putBoolean("enc", encoderLowLatency)
            .putInt("bw", bandwidthPercent)
            .putString("res", currentResProfile)
            .apply()
    }

    /** Wi-Fi güç tasarrufunu kapatır: RTT dalgalanmasını ve ani gecikme sıçramalarını azaltır. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "PulseLink:wifi").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val roomCode = intent?.getStringExtra("ROOM")?.trim() ?: "1234"
        val serverUrl = intent?.getStringExtra("SERVER_URL")?.trim() ?: ""

        createVideoOverlay()
        createFloatingWidget()
        startWebRtcClient(roomCode, serverUrl)

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "PL_OVERLAY_CH"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Pulse Link Katman", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = "ACTION_STOP" }
        val stopPending = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pulse Link Katmanı Aktif")
            .setContentText("Kapatmak için dokun.")
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "KAPAT", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(101, notification)
        }
    }

    private fun createVideoOverlay() {
        if (::videoView.isInitialized) return
        videoView = LayoutInflater.from(this).inflate(R.layout.overlay_view, null)
        videoRenderer = videoView.findViewById(R.id.videoRenderer)
        tvStaticHud = videoView.findViewById(R.id.tvStaticHud)

        videoRenderer.init(WebRtcManager.eglBase.eglBaseContext, null)
        videoRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        videoRenderer.setEnableHardwareScaler(false)

        framePacer = FramePacer(videoRenderer).also { it.setDepth(pacerDepth) }
        tvStaticHud.visibility = if (hudVisible) View.VISIBLE else View.GONE

        videoParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        videoParams.gravity = Gravity.CENTER
        videoParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        windowManager.addView(videoView, videoParams)

        positionHud()
    }

    /**
     * HUD'u sağ siyah şeridin ortasına ve EN ÜSTE sabitler (yazı uzunluğu değişse de başlangıcı kaymaz).
     * HUD sol-üst köşesi etrafında 90° döner, yani kapladığı alan x'te [x0 - yükseklik, x0], y'de [üst, üst + uzunluk].
     */
    private fun positionHud() {
        tvStaticHud.post {
            val rootW = if (videoView.width > 0) videoView.width else resources.displayMetrics.widthPixels
            val rootH = if (videoView.height > 0) videoView.height else resources.displayMetrics.heightPixels
            val rendW = if (videoRenderer.width > 0) videoRenderer.width else (rootH * 9f / 19.5f).toInt()
            val barW = ((rootW - rendW) / 2f).coerceAtLeast(0f)
            val hudThickness = tvStaticHud.height.toFloat()
            val x0 = minOf(rootW.toFloat(), rootW - barW / 2f + hudThickness / 2f)
            tvStaticHud.translationX = x0
            tvStaticHud.translationY = 10f * resources.displayMetrics.density
        }
    }

    private fun createFloatingWidget() {
        if (::floatingWidgetView.isInitialized) return
        floatingWidgetView = LayoutInflater.from(this).inflate(R.layout.floating_control, null)

        btnBubble = floatingWidgetView.findViewById(R.id.btnBubble)
        llSettingsMenu = floatingWidgetView.findViewById(R.id.llSettingsMenu)
        btnToggleVisibility = floatingWidgetView.findViewById(R.id.btnToggleVisibility)
        btnModeToggle = floatingWidgetView.findViewById(R.id.btnModeToggle)
        btnRes720 = floatingWidgetView.findViewById(R.id.btnRes720)
        btnRes1080 = floatingWidgetView.findViewById(R.id.btnRes1080)
        btnRes540 = floatingWidgetView.findViewById(R.id.btnRes540)
        btnFps45 = floatingWidgetView.findViewById(R.id.btnFps45)
        btnFps60 = floatingWidgetView.findViewById(R.id.btnFps60)
        btnFps30 = floatingWidgetView.findViewById(R.id.btnFps30)
        btnAudioToggle = floatingWidgetView.findViewById(R.id.btnAudioToggle)
        btnHudToggle = floatingWidgetView.findViewById(R.id.btnHudToggle)
        btnPacer = floatingWidgetView.findViewById(R.id.btnPacer)
        btnEncoder = floatingWidgetView.findViewById(R.id.btnEncoder)
        btnBandwidth = floatingWidgetView.findViewById(R.id.btnBandwidth)
        btnMenuClose = floatingWidgetView.findViewById(R.id.btnMenuClose)

        updateResolutionButtonsUI()
        updateFpsButtonsUI()
        updateModeButtonUI()
        applyAudioState()
        updateAudioButtonUI()
        updateHudButtonUI()
        updatePacerButtonUI()
        updateEncoderButtonUI()
        updateBandwidthButtonUI()

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        floatingParams.gravity = Gravity.TOP or Gravity.START
        floatingParams.x = 10
        floatingParams.y = 80
        floatingParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        btnBubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = true

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = floatingParams.x
                        initialY = floatingParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false
                            floatingParams.x = initialX + dx
                            floatingParams.y = initialY + dy
                            windowManager.updateViewLayout(floatingWidgetView, floatingParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            val isOpen = llSettingsMenu.visibility == View.VISIBLE
                            llSettingsMenu.visibility = if (isOpen) View.GONE else View.VISIBLE
                            windowManager.updateViewLayout(floatingWidgetView, floatingParams)
                        }
                        return true
                    }
                }
                return false
            }
        })

        btnToggleVisibility.setOnClickListener {
            isVideoVisible = !isVideoVisible
            videoView.visibility = if (isVideoVisible) View.VISIBLE else View.GONE
            btnToggleVisibility.text = if (isVideoVisible) "👁 YAYINI GİZLE" else "👁 YAYINI GÖSTER"
        }

        btnModeToggle.setOnClickListener {
            isManualMode = !isManualMode
            updateModeButtonUI()
            saveSettings()
            sendCurrentConfig()
        }

        btnRes720.setOnClickListener { setResolution("720p") }
        btnRes1080.setOnClickListener { setResolution("1080p") }
        btnRes540.setOnClickListener { setResolution("540p") }

        btnFps45.setOnClickListener { setFps(45) }
        btnFps60.setOnClickListener { setFps(60) }
        btnFps30.setOnClickListener { setFps(30) }

        btnAudioToggle.setOnClickListener {
            audioEnabled = !audioEnabled
            applyAudioState()
            updateAudioButtonUI()
            saveSettings()
            sendCurrentConfig()
        }

        btnHudToggle.setOnClickListener {
            hudVisible = !hudVisible
            tvStaticHud.visibility = if (hudVisible) View.VISIBLE else View.GONE
            updateHudButtonUI()
            saveSettings()
        }

        btnPacer.setOnClickListener {
            pacerDepth = (pacerDepth + 1) % 3
            framePacer?.setDepth(pacerDepth)
            updatePacerButtonUI()
            saveSettings()
        }

        btnEncoder.setOnClickListener {
            encoderLowLatency = !encoderLowLatency
            updateEncoderButtonUI()
            saveSettings()
            sendCurrentConfig()
            showToast("Kodlayıcı değişiyor, yayın 1-3 sn kesilip yeniden bağlanacak")
        }

        btnBandwidth.setOnClickListener {
            bandwidthPercent = when (bandwidthPercent) { 100 -> 75; 75 -> 50; else -> 100 }
            updateBandwidthButtonUI()
            saveSettings()
            sendCurrentConfig()
        }

        btnMenuClose.setOnClickListener { stopSelf() }

        windowManager.addView(floatingWidgetView, floatingParams)
        startStatsPolling()
    }

    private fun showToast(text: String) {
        statsHandler.post {
            try { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } catch (e: Exception) {}
        }
    }

    private fun setResolution(res: String) {
        var target = res
        if (!DeviceCaps.isProfileSupported(res, currentFps)) {
            target = DeviceCaps.maxSupportedProfile(currentFps)
            showToast("Bu cihazın video çözücüsü $res'i desteklemiyor, $target kullanılıyor")
        }
        currentResProfile = target
        updateResolutionButtonsUI()
        saveSettings()
        sendCurrentConfig()
    }

    private fun setFps(fps: Int) {
        currentFps = fps
        updateFpsButtonsUI()
        saveSettings()
        sendCurrentConfig()
    }

    private fun sendCurrentConfig() {
        // Manuelde seçilen profil aynen uygulanır; otomatikte bu cihazın çözebildiği en yüksek profil tavan olur
        val res = if (isManualMode) currentResProfile else deviceMaxProfile
        signalingClient?.sendConfig(res, currentFps, isManualMode, audioEnabled, encoderLowLatency, bandwidthPercent)
    }

    private fun applyAudioState() {
        if (audioEnabled) {
            val player = audioPlayer ?: AudioPlayer().also { audioPlayer = it }
            if (!player.start()) {
                audioEnabled = false
                showToast("Ses başlatılamadı")
            }
        } else {
            audioPlayer?.stop()
        }
    }

    private fun updateAudioButtonUI() {
        if (audioEnabled) {
            btnAudioToggle.text = "🔊 SES: AÇIK"
            btnAudioToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00C853"))
        } else {
            btnAudioToggle.text = "🔇 SES: KAPALI"
            btnAudioToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#424242"))
        }
    }

    private fun handleDataChannel(dc: DataChannel) {
        if (dc.label() != "audio") return
        audioChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer?) {
                val b = buffer ?: return
                if (!audioEnabled) return
                val data = b.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                audioPlayer?.push(bytes)
            }
        })
    }

    private fun updateHudButtonUI() {
        btnHudToggle.text = if (hudVisible) "📊 GÖSTERGE: AÇIK" else "📊 GÖSTERGE: KAPALI"
        btnHudToggle.backgroundTintList = if (hudVisible) colorActive else colorInactive
    }

    private fun updatePacerButtonUI() {
        when (pacerDepth) {
            0 -> {
                btnPacer.text = "AKICILIK: KAPALI"
                btnPacer.backgroundTintList = colorInactive
            }
            1 -> {
                btnPacer.text = "AKICILIK: 1 KARE"
                btnPacer.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0288D1"))
            }
            else -> {
                btnPacer.text = "AKICILIK: 2 KARE"
                btnPacer.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0288D1"))
            }
        }
    }

    private fun updateEncoderButtonUI() {
        if (encoderLowLatency) {
            btnEncoder.text = "KODLAYICI: HIZLI"
            btnEncoder.backgroundTintList = colorActive
        } else {
            btnEncoder.text = "KODLAYICI: VARSAYILAN"
            btnEncoder.backgroundTintList = colorInactive
        }
    }

    private fun updateBandwidthButtonUI() {
        btnBandwidth.text = "BANT SINIRI: %$bandwidthPercent"
        btnBandwidth.backgroundTintList = if (bandwidthPercent == 100) colorInactive
        else ColorStateList.valueOf(Color.parseColor("#F57C00"))
    }

    private fun updateModeButtonUI() {
        if (isManualMode) {
            btnModeToggle.text = "MOD: MANUEL"
            btnModeToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00C853"))
        } else {
            btnModeToggle.text = "MOD: OTOMATİK"
            btnModeToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0288D1"))
        }
    }

    private fun updateResolutionButtonsUI() {
        btnRes720.backgroundTintList = if (currentResProfile == "720p") colorActive else colorInactive
        btnRes1080.backgroundTintList = if (currentResProfile == "1080p") colorActive else colorInactive
        btnRes540.backgroundTintList = if (currentResProfile == "540p") colorActive else colorInactive
    }

    private fun updateFpsButtonsUI() {
        btnFps45.backgroundTintList = if (currentFps == 45) colorActive else colorInactive
        btnFps60.backgroundTintList = if (currentFps == 60) colorActive else colorInactive
        btnFps30.backgroundTintList = if (currentFps == 30) colorActive else colorInactive
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun startStatsPolling() {
        statsRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                peerConnection?.getStats { report ->
                    val stats = report.statsMap

                    // Seçili bağlantı çiftini bul (RTT ve bağlantı türü buradan okunur)
                    var selectedPairId: String? = null
                    for (st in stats.values) {
                        if (st.type == "transport") {
                            (st.members["selectedCandidatePairId"] as? String)?.let { selectedPairId = it }
                        }
                    }
                    var pair: RTCStats? = selectedPairId?.let { stats[it] }
                    if (pair == null) {
                        pair = stats.values.firstOrNull {
                            it.type == "candidate-pair" && it.members["state"] == "succeeded" &&
                                it.members["nominated"] == true
                        }
                    }

                    var rttMs = 0
                    var connLabel = ""
                    if (pair != null) {
                        num(pair.members["currentRoundTripTime"])?.let { rttMs = (it * 1000).toInt() }
                        val lt = (pair.members["localCandidateId"] as? String)
                            ?.let { stats[it]?.members?.get("candidateType") as? String }
                        val rt = (pair.members["remoteCandidateId"] as? String)
                            ?.let { stats[it]?.members?.get("candidateType") as? String }
                        connLabel = when {
                            lt == "relay" || rt == "relay" -> "RELAY"
                            lt != null -> "P2P"
                            else -> ""
                        }
                    }

                    var statFps = 0.0
                    var currentFrames: Long = 0
                    var frameW = 0
                    var frameH = 0
                    var bytes = 0L
                    var jbDelay = 0.0
                    var jbCount = 0L
                    var decTime = 0.0
                    var lost = 0L
                    var nack = 0L
                    var pli = 0L
                    var freezes = 0L

                    for (stat in stats.values) {
                        if (stat.type == "inbound-rtp" && stat.members["kind"] == "video") {
                            num(stat.members["framesPerSecond"])?.let { if (it > 0) statFps = it }
                            num(stat.members["framesDecoded"])?.let { if (it > 0) currentFrames = it.toLong() }
                            num(stat.members["frameWidth"])?.let { frameW = it.toInt() }
                            num(stat.members["frameHeight"])?.let { frameH = it.toInt() }
                            num(stat.members["bytesReceived"])?.let { bytes = it.toLong() }
                            num(stat.members["jitterBufferDelay"])?.let { jbDelay = it }
                            num(stat.members["jitterBufferEmittedCount"])?.let { jbCount = it.toLong() }
                            num(stat.members["totalDecodeTime"])?.let { decTime = it }
                            num(stat.members["packetsLost"])?.let { lost = it.toLong() }
                            num(stat.members["nackCount"])?.let { nack = it.toLong() }
                            num(stat.members["pliCount"])?.let { pli = it.toLong() }
                            num(stat.members["freezeCount"])?.let { freezes = it.toLong() }
                        }
                    }

                    val dt = (now - lastStatsTime) / 1000.0
                    val computedFps = if (dt > 0.5 && lastFramesDecoded > 0 && currentFrames >= lastFramesDecoded) {
                        ((currentFrames - lastFramesDecoded) / dt).toInt()
                    } else 0

                    // Son 1 saniyedeki ortalamalar
                    var mbps = 0.0
                    if (dt > 0.5 && prevBytes > 0 && bytes >= prevBytes) {
                        mbps = (bytes - prevBytes) * 8.0 / dt / 1_000_000.0
                    }
                    var jitterMs = -1
                    if (jbCount > prevJbCount && prevJbCount > 0) {
                        jitterMs = ((jbDelay - prevJbDelay) / (jbCount - prevJbCount) * 1000).toInt()
                    }
                    var decodeMs = -1
                    if (currentFrames > prevDecFrames && prevDecFrames > 0) {
                        decodeMs = ((decTime - prevDecTime) / (currentFrames - prevDecFrames) * 1000).toInt()
                    }

                    // Son 1 saniyedeki kayıp paket / yeniden gönderim isteği
                    val lostDelta = if (prevLost > 0 || lost > 0) (lost - prevLost).coerceAtLeast(0) else 0
                    val nackDelta = if (prevNack > 0 || nack > 0) (nack - prevNack).coerceAtLeast(0) else 0
                    prevLost = lost
                    prevNack = nack
                    // Ekranda gösterilen kareler arası en uzun boşluk (ms)
                    val gaps = framePacer?.snapshotGaps()
                    val maxGapMs = gaps?.first ?: -1

                    lastFramesDecoded = currentFrames
                    lastStatsTime = now
                    prevBytes = bytes
                    prevJbDelay = jbDelay
                    prevJbCount = jbCount
                    prevDecTime = decTime
                    prevDecFrames = currentFrames

                    val finalFps = if (statFps > 0) statFps.toInt() else computedFps
                    val shortSide = if (frameW > 0 && frameH > 0) minOf(frameW, frameH) else 0

                    statsHandler.post {
                        val modeChar = if (isManualMode) "M" else "O"
                        if (finalFps > 0) {
                            // Gerçekte gelen çözünürlük gösterilir (otomatikte değişebilir)
                            val resLabel = if (shortSide > 0) "${shortSide}p" else currentResProfile
                            val sb = StringBuilder()
                            sb.append("$finalFps FPS • ${if (rttMs > 0) "$rttMs ms" else "<4ms"} | $resLabel [$modeChar]")
                            if (connLabel.isNotEmpty()) sb.append(" | $connLabel")
                            if (mbps > 0.05) sb.append(String.format(Locale.US, " %.1fM", mbps))
                            if (jitterMs >= 0) sb.append(" J$jitterMs")
                            if (decodeMs >= 0) sb.append(" D$decodeMs")
                            if (maxGapMs >= 0) sb.append(" G$maxGapMs")
                            if (lostDelta > 0) sb.append(" L$lostDelta")
                            if (nackDelta > 0) sb.append(" N$nackDelta")
                            if (pli > 0) sb.append(" K$pli")
                            if (freezes > 0) sb.append(" F$freezes")
                            tvStaticHud.text = sb.toString()
                            // Relay (aracı sunucu) üzerinden gidiyorsa gecikmeyi artırır, turuncu ile uyar
                            tvStaticHud.setTextColor(
                                if (connLabel == "RELAY") Color.parseColor("#FFAB00") else Color.parseColor("#00E676")
                            )
                        } else if (isConnectedToPeer) {
                            tvStaticHud.text = "Görüntü Bekleniyor [$modeChar]"
                            tvStaticHud.setTextColor(Color.parseColor("#00E5FF"))
                        } else {
                            tvStaticHud.text = "Oda Aranıyor..."
                            tvStaticHud.setTextColor(Color.parseColor("#FFD600"))
                        }
                        positionHud()
                    }
                }
                statsHandler.postDelayed(this, 1000)
            }
        }
        statsHandler.postDelayed(statsRunnable!!, 1000)
    }

    private fun buildPeerConnection(): PeerConnection? {
        val factory = peerConnectionFactory ?: return null
        val rtcConfig = PeerConnection.RTCConfiguration(WebRtcManager.getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    signalingClient?.sendIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                attachVideoTrack(transceiver?.receiver?.track())
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                attachVideoTrack(receiver?.track())
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.forEach { attachVideoTrack(it) }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                dataChannel?.let { handleDataChannel(it) }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // WebRTC iş parçacığından gelir; ana iş parçacığına aktar
                statsHandler.post { handleIceState(state) }
            }
        })
    }

    private fun handleIceState(state: PeerConnection.IceConnectionState?) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                peerLostRunnable?.let { statsHandler.removeCallbacks(it) }
                isConnectedToPeer = true
                sendCurrentConfig() // yayın cihazı, bu cihazdaki seçimleri bilsin
            }
            PeerConnection.IceConnectionState.DISCONNECTED,
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.CLOSED -> {
                // DISCONNECTED geçici olabilir; biraz bekleyip hâlâ bağlı değilse yeniden aramaya başla
                val delay = if (state == PeerConnection.IceConnectionState.DISCONNECTED) 8000L else 500L
                peerLostRunnable?.let { statsHandler.removeCallbacks(it) }
                peerLostRunnable = Runnable {
                    isConnectedToPeer = false
                    startHeartbeat()
                }
                statsHandler.postDelayed(peerLostRunnable!!, delay)
            }
            else -> {}
        }
    }

    private fun startHeartbeat() {
        heartbeatRunnable?.let { statsHandler.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!isConnectedToPeer) {
                    signalingClient?.sendReady(sessionId)
                    statsHandler.postDelayed(this, 1500)
                }
            }
        }
        statsHandler.post(heartbeatRunnable!!)
    }

    private fun startWebRtcClient(roomCode: String, serverUrl: String) {
        val factory = WebRtcManager.createPeerConnectionFactory(this)
        peerConnectionFactory = factory

        signalingClient = SignalingClient(serverUrl, roomCode, object : SignalingClient.Listener {
            override fun onServerConnected() {
                signalingClient?.sendReady(sessionId)
            }

            override fun onOfferReceived(sdp: String) {
                isConnectedToPeer = true
                synchronized(pcLock) {
                    // Zaten bağlıyken yeni teklif gelirse (yayın cihazı yeniden başladı) temiz bir bağlantıyla başla
                    if (peerConnection?.remoteDescription != null) {
                        rebuildPeerConnection()
                    }
                    val pc = peerConnection ?: return
                    pc.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            drainIceCandidates()
                            createAnswer()
                        }
                    }, SessionDescription(SessionDescription.Type.OFFER, sdp))
                }
            }

            override fun onAnswerReceived(sdp: String) {}

            override fun onIceCandidateReceived(sdp: String, sdpMid: String, sdpMLineIndex: Int) {
                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                synchronized(pendingIceCandidates) {
                    val pc = peerConnection
                    if (pc?.remoteDescription != null) {
                        pc.addIceCandidate(candidate)
                    } else {
                        pendingIceCandidates.add(candidate)
                    }
                }
            }

            override fun onPeerReady(sid: String) {
                // Karşı taraf (yayın cihazı) kendini duyurdu: yeni başlamış olabilir, her zaman karşılık ver
                signalingClient?.sendReady(sessionId)
            }

            override fun onStatusReceived(audioOk: Boolean, message: String) {
                if (!audioOk) {
                    statsHandler.post {
                        audioEnabled = false
                        applyAudioState()
                        updateAudioButtonUI()
                    }
                    if (message.isNotEmpty()) showToast(message)
                }
            }
        })
        signalingClient?.connect()

        peerConnection = buildPeerConnection()
        startHeartbeat()
    }

    private fun rebuildPeerConnection() {
        try {
            audioChannel?.unregisterObserver()
            audioChannel?.close()
            audioChannel?.dispose()
        } catch (e: Exception) {
        }
        audioChannel = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
        }
        peerConnection = null
        pendingIceCandidates.clear()
        peerConnection = buildPeerConnection()
    }

    private fun attachVideoTrack(track: MediaStreamTrack?) {
        if (track is VideoTrack) {
            track.setEnabled(true)
            track.addSink(framePacer ?: videoRenderer)
        }
    }

    private fun drainIceCandidates() {
        synchronized(pendingIceCandidates) {
            for (candidate in pendingIceCandidates) {
                peerConnection?.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val pc = peerConnection ?: return
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val cleanSdp = WebRtcManager.preferH264(it.description)
                    val newDesc = SessionDescription(it.type, cleanSdp)
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            signalingClient?.sendAnswer(newDesc.description)
                            statsHandler.post { sendCurrentConfig() }
                        }
                    }, newDesc)
                }
            }
        }, constraints)
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        peerLostRunnable?.let { statsHandler.removeCallbacks(it) }
        try { signalingClient?.close() } catch (e: Exception) {}
        try { audioPlayer?.stop() } catch (e: Exception) {}
        try { framePacer?.release() } catch (e: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        if (::videoView.isInitialized) {
            try {
                videoRenderer.release()
                windowManager.removeView(videoView)
            } catch (e: Exception) {}
        }
        if (::floatingWidgetView.isInitialized) {
            try {
                windowManager.removeView(floatingWidgetView)
            } catch (e: Exception) {}
        }
        try {
            audioChannel?.unregisterObserver()
            audioChannel?.close()
            audioChannel?.dispose()
        } catch (e: Exception) {}
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (e: Exception) {}
    }
}
