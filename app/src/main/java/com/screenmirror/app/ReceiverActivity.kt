package com.screenmirror.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.SurfaceView
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers a SenderService instance on the local WiFi network via NSD,
 * connects to it over TCP, and decodes the incoming H.264 stream directly
 * onto a SurfaceView using MediaCodec's built-in surface rendering.
 */
class ReceiverActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var decoder: MediaCodec? = null
    private var socket: Socket? = null
    private val running = AtomicBoolean(false)
    private var receiveThread: Thread? = null
    private var connectedToService = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.statusText)

        acquireMulticastLock()
        startDiscovery()
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("screenMirrorReceiverLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun startDiscovery() {
        statusText.text = "กำลังค้นหาอุปกรณ์..."
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_screenmirror") && !connectedToService) {
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.e("Receiver", "Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            connectedToService = true
                            runOnUiThread {
                                statusText.text = "เจออุปกรณ์: ${info.serviceName} กำลังเชื่อมต่อ..."
                            }
                            val host = info.host.hostAddress ?: return
                            connectToSender(host, info.port)
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdManager!!.discoverServices(
            "_screenmirror._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener
        )
    }

    private fun connectToSender(host: String, port: Int) {
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
        // Simple wait loop until the SurfaceView's Surface is ready to draw on
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
                    decoder!!.releaseOutputBuffer(outIndex, true) // true = render to surface
                    outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            Log.w("Receiver", "Stream ended: ${e.message}")
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { receiveThread?.interrupt() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        try { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
