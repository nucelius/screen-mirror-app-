package com.screenmirror.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that:
 *  1. Captures the device screen via MediaProjection
 *  2. Encodes it to H.264 with MediaCodec
 *  3. Advertises itself on the local WiFi network via NSD (mDNS)
 *  4. Streams the encoded frames to whichever receiver connects, over a plain TCP socket
 *
 * Protocol (very simple, all big-endian ints):
 *   On connect: [int width][int height]
 *   Then repeated: [int frameSize][frameSize bytes of H264 data]
 */
class SenderService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_mirror_sender"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val PORT = 5679
        const val TAG = "SenderService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val running = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private var acceptThread: Thread? = null

    // Capture resolution / quality - tweak as needed
    private val width = 1280
    private val height = 720
    private val bitRate = 4_000_000
    private val frameRate = 30

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground(NOTIF_ID, buildNotification("กำลังแชร์หน้าจอ..."))

        if (resultData == null || resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Missing projection permission result")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        acquireMulticastLock()

        try {
            startEncoder()
            startServer()
            registerNsdService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sender", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("screenMirrorMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        val dpi = resources.displayMetrics.densityDpi

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenMirrorSender",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
    }

    private fun startServer() {
        running.set(true)
        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Waiting for receiver on port $PORT")
                while (running.get()) {
                    val client = serverSocket!!.accept()
                    Log.i(TAG, "Receiver connected: ${client.inetAddress}")
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Server error", e)
            }
        }
        acceptThread!!.start()
    }

    private fun handleClient(socket: Socket) {
        // MVP supports one active receiver at a time; a new connection replaces the old stream
        streamThread?.interrupt()
        streamThread = Thread {
            val out = DataOutputStream(socket.getOutputStream())
            try {
                out.writeInt(width)
                out.writeInt(height)
                out.flush()

                val bufferInfo = MediaCodec.BufferInfo()
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val outIndex = encoder!!.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        val encodedData = encoder!!.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            encodedData.get(chunk)

                            out.writeInt(chunk.size)
                            out.write(chunk)
                            out.flush()
                        }
                        encoder!!.releaseOutputBuffer(outIndex, false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client disconnected: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
        streamThread!!.start()
    }

    private fun registerNsdService() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ScreenMirror-${Build.MODEL}"
            serviceType = "_screenmirror._tcp."
            port = PORT
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager!!.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Mirror Sender", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { streamThread?.interrupt() } catch (_: Exception) {}
        try { acceptThread?.interrupt() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
