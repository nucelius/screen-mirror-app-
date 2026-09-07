package com.screenmirror.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class WifiDirectHelper(
  private val context: Context,
  private val onPeersChanged: (List<WifiP2pDevice>) -> Unit = {},
  private val onConnectionChanged: (WifiP2pInfo) -> Unit = {}
  ) {
  companion object {
    private const val TAG = "WifiDirectHelper"
  }

  private val manager: WifiP2pManager =
  context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
  private val channel: WifiP2pManager.Channel =
  manager.initialize(context.applicationContext, context.mainLooper, null)

  private var registered = false

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      when (intent.action) {
        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
          try {
            manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
              onPeersChanged(peers.deviceList.toList())
            }
          } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to request peers", e)
          }
        }
        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
          try {
            manager.requestConnectionInfo(channel) { info ->
              if (info != null && info.groupFormed) {
                onConnectionChanged(info)
              }
            }
          } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to request connection info", e)
          }
        }
      }
    }
  }

  fun register() {
    if (registered) return
    val filter = IntentFilter().apply {
      addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
      addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
      addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
      addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    context.registerReceiver(receiver, filter)
    registered = true
  }

  fun unregister() {
    if (!registered) return
    try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
    registered = false
  }

  fun discoverPeers() {
    try {
      manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
          Log.i(TAG, "Peer discovery started")
        }
        override fun onFailure(reason: Int) {
          Log.e(TAG, "Peer discovery failed: $reason")
        }
      })
    } catch (e: SecurityException) {
      Log.e(TAG, "Missing permission to discover peers", e)
    }
  }

  fun connect(device: WifiP2pDevice, onResult: (Boolean) -> Unit) {
    val config = WifiP2pConfig().apply {
      deviceAddress = device.deviceAddress
      wps.setup = WpsInfo.PBC
    }
    try {
      manager.connect(channel, config, object : WifiP2pManager.ActionListener {
        override fun onSuccess() { onResult(true) }
        override fun onFailure(reason: Int) {
          Log.e(TAG, "Connect failed: $reason")
          onResult(false)
        }
      })
    } catch (e: SecurityException) {
      Log.e(TAG, "Missing permission to connect", e)
      onResult(false)
    }
  }

  fun createGroup(onResult: (Boolean) -> Unit) {
    try {
      manager.createGroup(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() { onResult(true) }
        override fun onFailure(reason: Int) {
          Log.e(TAG, "Create group failed: $reason")
          onResult(false)
        }
      })
    } catch (e: SecurityException) {
      Log.e(TAG, "Missing permission to create group", e)
      onResult(false)
    }
  }

  fun removeGroup() {
    try {
      manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {}
      })
    } catch (e: Exception) {}
  }
}
