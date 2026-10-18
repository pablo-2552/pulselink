package com.wildrift.overlay

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class SignalingClient(
    private val serverUrl: String,
    private val roomCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOfferReceived(sdp: String)
        fun onAnswerReceived(sdp: String)
        fun onIceCandidateReceived(sdp: String, sdpMid: String, sdpMLineIndex: Int)
        fun onPeerReady(sid: String) {}
        fun onServerConnected()
        fun onConfigReceived(
            res: String, fps: Int, isManual: Boolean, audio: Boolean,
            lowLatencyEncoder: Boolean, bandwidthPercent: Int
        ) {}
        /** Yayın cihazından izleyiciye durum bildirimi (ör. ses başlatılamadı). */
        fun onStatusReceived(audioOk: Boolean, message: String) {}
    }

    private val cleanRoom = roomCode.trim()
    private var webSocket: WebSocket? = null
    @Volatile private var closedByUser = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    } catch (e: Exception) {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }

    fun connect() {
        val request = Request.Builder().url(serverUrl.trim()).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("room", cleanRoom)
                }
                webSocket.send(joinMsg.toString())
                listener.onServerConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("room").trim() != cleanRoom) return
                    when (json.optString("type")) {
                        "offer" -> listener.onOfferReceived(json.getString("sdp"))
                        "answer" -> listener.onAnswerReceived(json.getString("sdp"))
                        "candidate" -> {
                            val sdp = json.optString("sdp", "")
                            val sdpMid = json.optString("sdpMid", "")
                            val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
                            if (sdp.isNotEmpty()) {
                                listener.onIceCandidateReceived(sdp, sdpMid, sdpMLineIndex)
                            }
                        }
                        "ready" -> listener.onPeerReady(json.optString("sid", ""))
                        "config" -> {
                            val res = json.optString("res", "720p")
                            val fps = json.optInt("fps", 60)
                            val isManual = json.optBoolean("manual", true)
                            val audio = json.optBoolean("audio", false)
                            val enc = json.optBoolean("enc", true)
                            val bw = json.optInt("bw", 100)
                            listener.onConfigReceived(res, fps, isManual, audio, enc, bw)
                        }
                        "status" -> {
                            listener.onStatusReceived(
                                json.optBoolean("audioOk", true),
                                json.optString("msg", "")
                            )
                        }
                    }
                } catch (e: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        mainHandler.postDelayed({
            if (closedByUser) return@postDelayed
            try { connect() } catch (e: Exception) {}
        }, 2000)
    }

    fun sendOffer(sdp: String) {
        val json = JSONObject().apply {
            put("type", "offer")
            put("room", cleanRoom)
            put("sdp", sdp)
        }
        webSocket?.send(json.toString())
    }

    fun sendAnswer(sdp: String) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("room", cleanRoom)
            put("sdp", sdp)
        }
        webSocket?.send(json.toString())
    }

    fun sendIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        val json = JSONObject().apply {
            put("type", "candidate")
            put("room", cleanRoom)
            put("sdp", sdp)
            put("sdpMid", sdpMid ?: "")
            put("sdpMLineIndex", sdpMLineIndex)
        }
        webSocket?.send(json.toString())
    }

    fun sendReady(sid: String = "") {
        val json = JSONObject().apply {
            put("type", "ready")
            put("room", cleanRoom)
            put("sid", sid)
        }
        webSocket?.send(json.toString())
    }

    fun sendConfig(
        res: String, fps: Int, isManual: Boolean, audio: Boolean,
        lowLatencyEncoder: Boolean, bandwidthPercent: Int
    ) {
        val json = JSONObject().apply {
            put("type", "config")
            put("room", cleanRoom)
            put("res", res)
            put("fps", fps)
            put("manual", isManual)
            put("audio", audio)
            put("enc", lowLatencyEncoder)
            put("bw", bandwidthPercent)
        }
        webSocket?.send(json.toString())
    }

    fun sendStatus(audioOk: Boolean, message: String) {
        val json = JSONObject().apply {
            put("type", "status")
            put("room", cleanRoom)
            put("audioOk", audioOk)
            put("msg", message)
        }
        webSocket?.send(json.toString())
    }

    fun close() {
        closedByUser = true
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Closed")
    }
}
