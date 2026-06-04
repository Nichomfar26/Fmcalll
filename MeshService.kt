package com.fmcall.serval.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fmcall.serval.FMcallApplication
import com.fmcall.serval.R
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.mesh.MeshStatus
import com.fmcall.serval.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class MeshService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.fmcall.serval.MESH_START"
        const val ACTION_STOP     = "com.fmcall.serval.MESH_STOP"
    }

    @Inject lateinit var meshManager: MeshNetworkManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startMeshForeground()
        }
        return START_STICKY
    }

    private fun startMeshForeground() {
        startForeground(NOTIFICATION_ID, buildNotification("Réseau Mesh actif", "Démarrage..."))
        observeMeshStatus()
        observeNodeCount()
    }

    private fun observeMeshStatus() {
        serviceScope.launch {
            meshManager.meshStatus.collectLatest { status ->
                val text = when (status) {
                    MeshStatus.ONLINE   -> "Réseau Mesh actif"
                    MeshStatus.STARTING -> "Démarrage du réseau..."
                    MeshStatus.OFFLINE  -> "Réseau hors ligne"
                    MeshStatus.ERROR    -> "Erreur réseau"
                }
                updateNotification(text)
            }
        }
    }

    private fun observeNodeCount() {
        serviceScope.launch {
            meshManager.connectedNodes.collectLatest { nodes ->
                val nodeText = "${nodes.size} nœud${if (nodes.size > 1) "s" else ""} connecté${if (nodes.size > 1) "s" else ""}"
                updateNotification("Réseau Mesh actif", nodeText)
            }
        }
    }

    private fun buildNotification(title: String, content: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, MeshService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, FMcallApplication.CHANNEL_MESH_SERVICE)
            .setSmallIcon(R.drawable.ic_mesh_signal)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, "Arrêter", stopPi)
            .build()
    }

    private fun updateNotification(title: String, content: String = "") {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        meshManager.stop()
    }
}

// ── Boot Receiver ─────────────────────────────────────────────────────────────

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, MeshService::class.java).apply {
                action = MeshService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
