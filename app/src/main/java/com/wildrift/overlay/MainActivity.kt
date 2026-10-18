package com.wildrift.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etRoomCode: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnHost: Button

    private val defaultServerUrl = "wss://wildrift-overlay.onrender.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etRoomCode = findViewById(R.id.etRoomCode)
        etServerUrl = findViewById(R.id.etServerUrl)
        etServerUrl.setText(defaultServerUrl)
        btnConnect = findViewById(R.id.btnConnect)
        btnHost = findViewById(R.id.btnHost)

        checkOverlayPermission()
        updateButtonStates()

        btnConnect.setOnClickListener {
            if (HostService.isRunning) {
                Toast.makeText(this, "Bu cihazda yayın açıkken odaya katılamazsınız!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val room = etRoomCode.text.toString().trim().ifEmpty { "1234" }
            val server = etServerUrl.text.toString().trim().ifEmpty { defaultServerUrl }

            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("ROOM", room)
                putExtra("SERVER_URL", server)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        }

        btnHost.setOnClickListener {
            if (HostService.isRunning) {
                val stopIntent = Intent(this, HostService::class.java).apply { action = "ACTION_STOP_HOST" }
                startService(stopIntent)
                Toast.makeText(this, "Yayın durduruldu.", Toast.LENGTH_SHORT).show()
                updateButtonStates()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                // Cihazın sesini yayınlamak için gerekli (mikrofon kullanılmaz, sadece sistem sesi yakalanır)
                Toast.makeText(this, "Ses aktarımı için izin ver (yalnızca cihaz sesi alınır)", Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            } else {
                startScreenCapture()
            }
        }
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            // İzin verilmese de görüntü yayını devam eder; sadece ses çalışmaz
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Ses izni verilmedi: sadece görüntü yayınlanacak", Toast.LENGTH_LONG).show()
            }
            startScreenCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        if (HostService.isRunning) {
            btnHost.text = "YAYINI DURDUR"
            btnHost.setBackgroundColor(Color.parseColor("#D50000"))
            btnConnect.isEnabled = false
            btnConnect.alpha = 0.4f
        } else {
            btnHost.text = "BU CİHAZDAN YAYIN AÇ (S26)"
            btnHost.setBackgroundColor(Color.parseColor("#2979FF"))
            btnConnect.isEnabled = true
            btnConnect.alpha = 1.0f
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            val room = etRoomCode.text.toString().trim().ifEmpty { "1234" }
            val server = etServerUrl.text.toString().trim().ifEmpty { defaultServerUrl }

            val serviceIntent = Intent(this, HostService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
                putExtra("ROOM", room)
                putExtra("SERVER_URL", server)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Yayın başladı! Kod: $room", Toast.LENGTH_LONG).show()
            updateButtonStates()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }
}
