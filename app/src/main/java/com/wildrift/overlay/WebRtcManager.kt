package com.wildrift.overlay

import android.content.Context
import org.webrtc.*

object WebRtcManager {
    val eglBase: EglBase by lazy { EglBase.create() }

    // Alıcı tarafındaki görüntü tamponunun (jitter buffer) şişmesini sınırlar:
    // yayıncı her kareye "en fazla 20 ms bekletme" bilgisi ekler, izleyici de ona uyar.
    // (Desteklemeyen sürümlerde yok sayılır, zararsızdır.)
    private const val FIELD_TRIALS = "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:20/"

    fun createPeerConnectionFactory(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setFieldTrials(FIELD_TRIALS)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun getIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }

    fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var mVideoIndex = -1
        val h264Payloads = mutableListOf<String>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("m=video")) {
                mVideoIndex = i
            } else if (line.startsWith("a=rtpmap:") && line.contains("H264/", ignoreCase = true)) {
                val colonIdx = line.indexOf(':')
                val spaceIdx = line.indexOf(' ', colonIdx)
                if (colonIdx != -1 && spaceIdx != -1) {
                    val pt = line.substring(colonIdx + 1, spaceIdx).trim()
                    h264Payloads.add(pt)
                }
            }
        }

        if (mVideoIndex != -1 && h264Payloads.isNotEmpty()) {
            val mParts = lines[mVideoIndex].split(" ").toMutableList()
            if (mParts.size > 3) {
                val header = mParts.subList(0, 3)
                val payloads = mParts.subList(3, mParts.size)
                val newPayloads = mutableListOf<String>()
                for (pt in h264Payloads) {
                    if (payloads.contains(pt)) newPayloads.add(pt)
                }
                for (pt in payloads) {
                    if (!newPayloads.contains(pt)) newPayloads.add(pt)
                }
                lines[mVideoIndex] = (header + newPayloads).joinToString(" ")
            }
        }
        return lines.joinToString("\r\n")
    }

}
