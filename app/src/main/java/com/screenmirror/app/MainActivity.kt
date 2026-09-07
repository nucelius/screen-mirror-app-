package com.screenmirror.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var wifiDirect: WifiDirectHelper
    private var pendingProjectionResult: Pair<Int, Intent>? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
        ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingProjectionResult = result.resultCode to result.data!!
            startWifiDirectGroupThenSender()
        } else {
            Toast.makeText(this, "ไม่ได้รับอนุญาตให้แคปหน้าจอ", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
        ) { /* proceed regardless of result */ }

    private val requiredPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
        ) { /* proceed regardless; we check again before using WiFi Direct */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiDirect = WifiDirectHelper(this)

        findViewById<Button>(R.id.btnSender).setOnClickListener {
            requestNotifPermIfNeeded()
            requestWifiDirectPermsIfNeeded()
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnReceiver).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        wifiDirect.register()
    }

    override fun onStop() {
        wifiDirect.unregister()
        super.onStop()
    }

    private fun startWifiDirectGroupThenSender() {
        Toast.makeText(this, "กำลังสร้างกลุ่ม WiFi Direct...", Toast.LENGTH_SHORT).show()
        wifiDirect.createGroup { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(
                        this,
                        "สร้างกลุ่มสำเร็จ พร้อมให้เครื่องรับเชื่อมต่อ",
                        Toast.LENGTH_LONG
                        ).show()
                    launchSenderService()
                } else {
                    Toast.makeText(
                        this,
                        "สร้างกลุ่ม WiFi Direct ไม่สำเร็จ ลองเปิด WiFi และลองใหม่",
                        Toast.LENGTH_LONG
                        ).show()
                }
            }
        }
    }

    private fun launchSenderService() {
        val (resultCode, data) = pendingProjectionResult ?: return
        val intent = Intent(this, SenderService::class.java).apply {
            putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SenderService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
                ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestWifiDirectPermsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
                ) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
                ) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (perms.isNotEmpty()) {
            requiredPermsLauncher.launch(perms.toTypedArray())
        }
    }
}
