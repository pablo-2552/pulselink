package com.wildrift.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.webrtc.*
import java.util.Collections

/**
 * YAYIN CİHAZI (S26) servisi: ekranı ve sesi yakalar, izleyiciye WebRTC ile gönderir.
 */
class HostService : Service() {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: FixedPortraitCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var signalingClient: SignalingClient? = null
    private var videoSender: RtpSender? = null
    private var audioChannel: DataChannel? = null
    private var audioStreamer: AudioStreamer? = null
    private val pendingIceCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var currentResProfile = "720p"
    @Volatile private var currentFps = 60
    @Volatile private var isManualMode = true
    @Volatile private var audioWanted = false
    @Volatile private var bandwidthPercent = 100

    private var hasOffered = false
    private var lastOfferTime = 0L
    private var lastViewerSid = ""
    @Volatile private var iceConnected = false

    companion object {
        var isRunning = false
        private const val OFFER_DEBOUNCE_MS = 4000L
        private const val BWE_MAX_BPS = 15_000_000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_HOST") {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val roomCode = intent?.getStringExtra("ROOM")?.trim() ?: "1234"
        val serverUrl = intent?.getStringExtra("SERVER_URL")?.trim() ?: ""
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        startForegroundNotification()

        if (resultCode == Activity.RESULT_OK && data != null) {
            isRunning = true
            try {
                startStreaming(data, roomCode, serverUrl)
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "PL_HOST_CH"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Yayın Sunucusu", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, HostService::class.java).apply { action = "ACTION_STOP_HOST" }
        val stopPending = PendingIntent.getService(this, 10, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pulse Link — Yayın Aktif")
            .setContentText("Ekran ve ses düşük gecikmeyle paylaşılıyor")
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "YAYINI DURDUR", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(102, notification)
        }
    }

    private fun startStreaming(data: Intent, roomCode: String, serverUrl: String) {
        if (peerConnectionFactory != null) return // aynı servis ikinci kez başlatılırsa çift kurulumu engelle

        val factory = WebRtcManager.createPeerConnectionFactory(this)
        peerConnectionFactory = factory

        val helper = SurfaceTextureHelper.create("ScreenCaptureThread", WebRtcManager.eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val capturer = FixedPortraitCapturer(data, object : MediaProjection.Callback() {
            override fun onStop() {
                // Sistem/kullanıcı ekran paylaşımını durdurursa servisi de kapat
                mainHandler.post { stopSelf() }
            }
        })
        screenCapturer = capturer

        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, this, source.capturerObserver)
        capturer.setFpsAndMode(currentFps, isManualMode)
        capturer.startCapture(
            StreamProfile.CAPTURE_SHORT,
            StreamProfile.CAPTURE_LONG,
            StreamProfile.AUTO_MAX_FPS
        )

        videoTrack = factory.createVideoTrack("ARDAMSv0", source)
        createPeerConnection()

        signalingClient = SignalingClient(serverUrl, roomCode, object : SignalingClient.Listener {
            override fun onServerConnected() {
                signalingClient?.sendReady()
            }

            override fun onOfferReceived(sdp: String) {}

            override fun onAnswerReceived(sdp: String) {
                val pc = peerConnection ?: return
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        drainIceCandidates()
                        applyEncoderParams()
                        // İlk bağlantıda bant genişliği tahmini yavaş yükselmesin diye başlangıç değeri verilir
                        try {
                            pc.setBitrate(
                                null,
                                scaledBps(StreamProfile.startBitrate(currentResProfile, effectiveFps())),
                                BWE_MAX_BPS
                            )
                        } catch (e: Exception) {
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

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
                handlePeerReady(sid)
            }

            override fun onConfigReceived(
                res: String, fps: Int, isManual: Boolean, audio: Boolean,
                lowLatencyEncoder: Boolean, bandwidthPercent: Int
            ) {
                currentResProfile = if (res == "540p" || res == "720p" || res == "1080p") res else "720p"
                currentFps = StreamProfile.normalizeFps(fps)
                isManualMode = isManual
                audioWanted = audio
                this@HostService.bandwidthPercent = bandwidthPercent.coerceIn(20, 100)

                // Kodlayıcı modu yalnızca kodlayıcı yeniden kurulurken uygulanır:
                // değiştiyse bağlantıyı temiz kurup yeni teklif gönder (yayın 1-3 sn kesilir).
                if (lowLatencyEncoder != EncoderTuning.lowLatency) {
                    EncoderTuning.lowLatency = lowLatencyEncoder
                    restartEncoderViaNewOffer()
                }

                // Yakalama (VirtualDisplay) ASLA değişmez; profil/FPS kodlayıcıda uygulanır
                screenCapturer?.setFpsAndMode(currentFps, isManual)
                applyEncoderParams()
                updateAudio()
            }
        })
        signalingClient?.connect()
    }

    private fun effectiveFps(): Int = if (isManualMode) currentFps else StreamProfile.AUTO_MAX_FPS

    private fun scaledBps(bps: Int): Int = (bps.toLong() * bandwidthPercent / 100L).toInt()

    /** Kodlayıcı ayarı değişti: bağlantıyı sıfırdan kurup yeni teklif gönderir (kodlayıcı yeniden oluşur). */
    private fun restartEncoderViaNewOffer() {
        synchronized(this) {
            if (!hasOffered) return // henüz hiç bağlantı yok; ilk teklifte zaten yeni ayar kullanılacak
            rebuildPeerConnection()
            lastOfferTime = SystemClock.elapsedRealtime()
            createOffer()
        }
    }

    /** İzleyici "hazırım" dediğinde: gerekirse bağlantıyı sıfırdan kurup yeni teklif gönderir. */
    private fun handlePeerReady(sid: String) {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val sameViewer = hasOffered && sid.isNotEmpty() && sid == lastViewerSid
            // Aynı izleyicinin tekrarlanan "hazırım" mesajları: teklif yeni gönderildiyse ya da
            // bağlantı zaten sağlamsa dokunma (yayın kesilmesin)
            if (hasOffered && now - lastOfferTime < OFFER_DEBOUNCE_MS && (sameViewer || sid.isEmpty())) return
            if (sameViewer && iceConnected) return
            if (hasOffered) {
                // İzleyici yeniden başladı ya da önceki teklif kayboldu: temiz bir bağlantı kur
                rebuildPeerConnection()
            }
            lastViewerSid = sid
            hasOffered = true
            lastOfferTime = now
            createOffer()
        }
    }

    @Synchronized
    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return
        val track = videoTrack ?: return

        val rtcConfig = PeerConnection.RTCConfiguration(WebRtcManager.getIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(rtcConfig, object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    signalingClient?.sendIceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                iceConnected = state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                // WebRTC kendi iş parçacığından çağırır; kilitlenmemek için ana iş parçacığına aktar
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> mainHandler.post { updateAudio() }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> mainHandler.post { stopAudio() }
                    else -> {}
                }
            }
        }) ?: return
        peerConnection = pc

        videoSender = pc.addTrack(track, listOf("ARDAMS"))

        // Ses için güvenilmez (yeniden gönderimsiz) kanal: gecikme birikmez
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        val dc = pc.createDataChannel("audio", init)
        audioChannel = dc
        dc?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                mainHandler.post { updateAudio() }
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {}
        })

        applyEncoderParams()
    }

    @Synchronized
    private fun rebuildPeerConnection() {
        stopAudio()
        try {
            audioChannel?.unregisterObserver()
            audioChannel?.close()
            audioChannel?.dispose()
        } catch (e: Exception) {
        }
        audioChannel = null
        videoSender = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
        }
        peerConnection = null
        pendingIceCandidates.clear()
        iceConnected = false
        createPeerConnection()
    }

    private fun drainIceCandidates() {
        synchronized(pendingIceCandidates) {
            for (candidate in pendingIceCandidates) {
                peerConnection?.addIceCandidate(candidate)
            }
            pendingIceCandidates.clear()
        }
    }

    /**
     * MANUEL: seçilen çözünürlük/FPS sabittir; kodlayıcı çözünürlüğü ya da FPS'i kendiliğinden DÜŞÜRMEZ
     *         (DISABLED), bitrate dar bir aralıkta tutulur -> karıncalanma/oynama olmaz.
     * OTOMATİK: kodlayıcı ağa göre çözünürlük/FPS/bitrate'i kendisi ayarlar (BALANCED).
     */
    private fun applyEncoderParams() {
        val sender = videoSender ?: return
        try {
            val res = currentResProfile
            val fps = effectiveFps()
            val manual = isManualMode
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return

            for (encoding in parameters.encodings) {
                encoding.active = true
                encoding.maxFramerate = fps
                encoding.scaleResolutionDownBy = StreamProfile.scaleFor(res)
                encoding.maxBitrateBps = scaledBps(StreamProfile.maxBitrate(res, fps))
                encoding.minBitrateBps = scaledBps(
                    if (manual) StreamProfile.manualMinBitrate(res, fps) else StreamProfile.autoMinBitrate(res)
                )
            }
            parameters.degradationPreference =
                if (manual) RtpParameters.DegradationPreference.DISABLED
                else RtpParameters.DegradationPreference.BALANCED
            sender.setParameters(parameters)
        } catch (e: Exception) {
        }
    }

    /** İzleyici sesi açtıysa ve kanal hazırsa yakalamayı başlatır; değilse durdurur. */
    @Synchronized
    private fun updateAudio() {
        val dc = audioChannel
        val projection = screenCapturer?.projection
        val shouldRun = audioWanted && dc != null && dc.state() == DataChannel.State.OPEN && projection != null

        if (shouldRun && audioStreamer == null) {
            val streamer = AudioStreamer(projection!!, dc!!)
            if (streamer.start()) {
                audioStreamer = streamer
            } else {
                audioWanted = false
                val msg = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    "Ses için Android 10+ gerekli"
                } else {
                    "Ses başlatılamadı (yayın cihazında mikrofon/ses izni verilmemiş olabilir)"
                }
                signalingClient?.sendStatus(false, msg)
            }
        } else if (!shouldRun && audioStreamer != null) {
            stopAudio()
        }
    }

    @Synchronized
    private fun stopAudio() {
        audioStreamer?.stop()
        audioStreamer = null
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val newDesc = SessionDescription(it.type, WebRtcManager.preferH264(it.description))
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            signalingClient?.sendOffer(newDesc.description)
                        }
                    }, newDesc)
                }
            }
        }, constraints)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try { signalingClient?.close() } catch (e: Exception) {}
        stopAudio()
        try { screenCapturer?.dispose() } catch (e: Exception) {}
        try {
            audioChannel?.unregisterObserver()
            audioChannel?.close()
            audioChannel?.dispose()
        } catch (e: Exception) {}
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {}
        try { videoTrack?.dispose() } catch (e: Exception) {}
        try { videoSource?.dispose() } catch (e: Exception) {}
        try { surfaceTextureHelper?.dispose() } catch (e: Exception) {}
        try { peerConnectionFactory?.dispose() } catch (e: Exception) {}
    }
}
