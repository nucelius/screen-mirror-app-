package com.screenmirror.app

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var wifiDirect: WifiDirectHelper

    private var decoder: MediaCodec? = null
    private var socket: Socket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null
    private var alreadyConnecting = false

    private val requiredPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
        if (results.values.all { it }) {
            wifiDirect.discoverPeers()
        } else {
            statusText.text = "ต้องอนุญาตสิทธิ์เพื่อค้นหาอุปกรณ์ WiFi Direct"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.statusText)

        wifiDirect = WifiDirectHelper(
            context = this,
            onPeersChanged = { peers -> runOnUiThread { showPeerPicker(peers) } },
            onConnectionChanged = { info -> onWifiDirectConnected(info) }
            )
    }

    override fun onStart() {
        super.onStart()
        wifiDirect.register()
        requestPermsThenDiscover()
    }

    override fun onStop() {
        wifiDirect.unregister()
        super.onStop()
    }

    private fun requestPermsThenDiscover() {
        statusText.text = "กำลังค้นหาอุปกรณ์..."
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
        if (perms.isEmpty()) {
            wifiDirect.discoverPeers()
        } else {
            requiredPermsLauncher.launch(perms.toTypedArray())
        }
    }

    private var pickerShown = false

    private fun showPeerPicker(peers: List<WifiP2pDevice>) {
        if (alreadyConnecting || pickerShown || peers.isEmpty()) return
        pickerShown = true
        val names = peers.map { it.deviceName.ifBlank { it.deviceAddress } }.toTypedArray()

        AlertDialog.Builder(this)
        .setTitle("เลือกอุปกรณ์ที่จะรับหน้าจอ")
        .setItems(names) { _, which ->
            alreadyConnecting = true
            val device = peers[which]
            statusText.text = "กำลังเชื่อมต่อกับ ${device.deviceName}..."
            wifiDirect.connect(device) { success ->
                if (!success) {
                    runOnUiThread {
                        statusText.text = "เชื่อมต่อไม่สำเร็จ ลองใหม่อีกครั้ง"
                        alreadyConnecting = false
                        pickerShown = false
                    }
                }
            }
        }
        .setOnCancelListener {
            pickerShown = false
        }
        .setNegativeButton("ค้นหาใหม่") { _, _ ->
            pickerShown = false
            wifiDirect.discoverPeers()
        }
        .show()
    }

    private fun onWifiDirectConnected(info: WifiP2pInfo) {
        val host = info.groupOwnerAddress?.hostAddress ?: return
        runOnUiThread { statusText.text = "เชื่อมต่อ WiFi Direct สำเร็จ กำลังรับสัญญาณ..." }
        connectToSender(host, SenderService.PORT)
    }

    private fun connectToSender(host: String, port: Int) {
        if (running.get()) return
        running.set(true)
        receiveThread = Thread {
            try {
                socket = Socket(host, port)
                val input = DataInputStream(socket!!.getInputStream())
                val width = input.readInt()
                val height = input.readInt()

                runOnUiThread { statusText.text = "เชื่อมต่อสำเร็จ (${width}x${height})" }

                waitForSurfaceAndDecode(input, width, height)
            } catch (e: Exception) {
                Log.e("Receiver", "Connection failed", e)
                runOnUiThread { statusText.text = "เชื่อมต่อไม่สำเร็จ: ${e.message}" }
            }
        }
        receiveThread!!.start()
    }

    private fun waitForSurfaceAndDecode(input: DataInputStream, width: Int, height: Int) {
        val holder = surfaceView.holder
        var attempts = 0
        while (!holder.surface.isValid && attempts < 100) {
            Thread.sleep(50)
            attempts++
        }
        if (!holder.surface.isValid) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder!!.configure(format, holder.surface, null, 0)
        decoder!!.start()

        try {
            while (running.get()) {
                val size = input.readInt()
                val chunk = ByteArray(size)
                input.readFully(chunk)

                val inIndex = decoder!!.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = decoder!!.getInputBuffer(inIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(chunk)
                    decoder!!.queueInputBuffer(inIndex, 0, chunk.size, System.nanoTime() / 1000, 0)
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    decoder!!.releaseOutputBuffer(outIndex, true)
                    outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            Log.w("Receiver", "Stream ended: ${e.message}")
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { receiveThread?.interrupt() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
