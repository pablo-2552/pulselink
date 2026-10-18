package com.wildrift.overlay

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import org.webrtc.*

class FixedPortraitCapturer(
    private val mediaProjectionPermissionResultData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback
) : VideoCapturer, VideoSink {

    private var context: Context? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayManager: DisplayManager? = null
    private var windowManager: WindowManager? = null

    // 19.5:9 SABİT EN-BOY ORANI (yerleşim/boşluklar bozulmasın diye oran asla değişmez).
    // Yakalama en yüksek profilde (1080x2340) yapılır; 720p/540p kodlayıcıda ölçeklenerek elde edilir.
    private val targetShortDim = StreamProfile.CAPTURE_SHORT
    private val targetLongDim = StreamProfile.CAPTURE_LONG
    @Volatile private var targetFps = StreamProfile.AUTO_MAX_FPS
    @Volatile private var isManualMode = true
    private var nextAcceptNs: Long = 0L

    /** Ses yakalama (AudioPlaybackCapture) aynı MediaProjection'ı kullanmak zorunda. */
    val projection: MediaProjection?
        get() = mediaProjection

    private var currentRotation = Surface.ROTATION_0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                checkAndAdjustOrientation()
            }
        }
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.context = applicationContext
        this.capturerObserver = capturerObserver
        this.windowManager = applicationContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        this.displayManager = applicationContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {

        val ctx = context ?: return
        val mediaProjectionManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(
            android.app.Activity.RESULT_OK,
            mediaProjectionPermissionResultData
        )
        mediaProjection?.registerCallback(mediaProjectionCallback, surfaceTextureHelper?.handler)

        displayManager?.registerDisplayListener(displayListener, surfaceTextureHelper?.handler)
        createOrUpdateVirtualDisplay()
        capturerObserver?.onCapturerStarted(true)
        surfaceTextureHelper?.startListening(this)
    }

    /**
     * Manuel: seçilen FPS'e tam uyulur. Otomatik: en fazla 60 FPS, gerisini kodlayıcı ayarlar.
     */
    fun setFpsAndMode(fps: Int, manual: Boolean) {
        this.isManualMode = manual
        this.targetFps = if (manual) StreamProfile.normalizeFps(fps) else StreamProfile.AUTO_MAX_FPS
        this.nextAcceptNs = 0L
    }

    @Synchronized
    private fun checkAndAdjustOrientation() {
        val rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        if (rotation != currentRotation) {
            currentRotation = rotation
            createOrUpdateVirtualDisplay()
        }
    }

    @Synchronized
    private fun createOrUpdateVirtualDisplay() {
        val helper = surfaceTextureHelper ?: return
        val mp = mediaProjection ?: return

        val isLandscape = (currentRotation == Surface.ROTATION_90 || currentRotation == Surface.ROTATION_270)
        val captureWidth = if (isLandscape) targetLongDim else targetShortDim
        val captureHeight = if (isLandscape) targetShortDim else targetLongDim

        helper.setTextureSize(captureWidth, captureHeight)

        if (virtualDisplay == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "FixedPortraitCapture",
                captureWidth,
                captureHeight,
                320,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                android.view.Surface(helper.surfaceTexture),
                null,
                helper.handler
            )
        } else {
            virtualDisplay?.resize(captureWidth, captureHeight, 320)
            virtualDisplay?.surface = android.view.Surface(helper.surfaceTexture)
        }
    }

    override fun onFrame(frame: VideoFrame?) {
        if (frame == null) return

        // Gerçek FPS sınırı (kaynağa kilitlenen ızgara):
        //  - Kaynak, hedef FPS'ten hızlıysa (ör. 120 Hz ekran -> 60) fazla kareler atılır.
        //  - Kaynak hedefe yakınsa (oyun 30/60 FPS, zamanlaması hafif oynak) HİÇBİR kare atılmaz.
        //    (Eski kural hafif erken gelen kareleri de atıp periyodik takılma yapıyordu.)
        val fps = targetFps
        val ts = frame.timestampNs
        val intervalNs = 1_000_000_000L / fps
        if (nextAcceptNs == 0L) nextAcceptNs = ts
        val err = ts - nextAcceptNs // < 0: kare ızgaradan erken geldi
        if (-err > intervalNs * 4 / 10) {
            return // belirgin şekilde erken: fazla kare, at
        }
        nextAcceptNs = if (err > intervalNs) {
            ts + intervalNs // uzun boşluktan sonra ritmi yeniden kur
        } else {
            nextAcceptNs + intervalNs + err / 10 // ızgara kaynağın temposuna yavaşça uyar
        }

        val adjustedRotation = when (currentRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            else -> 0
        }

        frame.buffer.retain()
        val fixedFrame = VideoFrame(
            frame.buffer,
            adjustedRotation,
            frame.timestampNs
        )
        capturerObserver?.onFrameCaptured(fixedFrame)
        fixedFrame.release()
    }

    override fun stopCapture() {
        displayManager?.unregisterDisplayListener(displayListener)
        surfaceTextureHelper?.stopListening()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // FPS ve profil, sinyalleşme üzerinden gelen ayarla yönetilir.
    }

    override fun dispose() { stopCapture() }

    // false: oyun içeriği "video" olarak kodlanır. (true olsaydı WebRTC bant daralınca FPS'i
    // kısıp çözünürlüğü korurdu; ekran paylaşımı için doğru, oyun için yanlış.)
    override fun isScreencast(): Boolean = false
}
