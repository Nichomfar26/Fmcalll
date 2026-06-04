package com.fmcall.serval.service

import android.content.Context
import com.fmcall.serval.data.FMcallDatabase
import com.fmcall.serval.data.model.*
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64
import android.util.Log

// ═══════════════════════════════════════════════════════════════
// PENDING CALL QUEUE
// Files d'attente pour appels vocaux/vidéo quand le pair est offline.
// Dès que le pair se reconnecte, l'appel est relancé automatiquement.
// ═══════════════════════════════════════════════════════════════

@Singleton
class PendingCallQueue @Inject constructor(
    private val meshManager: MeshNetworkManager
) {
    private val pendingVoiceCalls = mutableMapOf<String, PendingCall>()
    private val pendingVideoCalls = mutableMapOf<String, PendingCall>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class PendingCall(
        val meshId:    String,
        val peerName:  String,
        val isVideo:   Boolean,
        val queuedAt:  Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + 3_600_000  // 1 heure
    )

    fun addPendingVoiceCall(meshId: String, peerName: String) {
        pendingVoiceCalls[meshId] = PendingCall(meshId, peerName, isVideo = false)
        Log.d("PendingQueue", "Voice call queued for $meshId ($peerName)")
        watchForReconnection(meshId, isVideo = false)
    }

    fun addPendingVideoCall(meshId: String, peerName: String) {
        pendingVideoCalls[meshId] = PendingCall(meshId, peerName, isVideo = true)
        Log.d("PendingQueue", "Video call queued for $meshId ($peerName)")
        watchForReconnection(meshId, isVideo = true)
    }

    fun removePendingVoiceCall(meshId: String) { pendingVoiceCalls.remove(meshId) }
    fun removePendingVideoCall(meshId: String) { pendingVideoCalls.remove(meshId) }

    fun hasPendingCall(meshId: String) =
        pendingVoiceCalls.containsKey(meshId) || pendingVideoCalls.containsKey(meshId)

    private fun watchForReconnection(meshId: String, isVideo: Boolean) {
        scope.launch {
            // Wait for the peer to appear in the mesh
            val peer = meshManager.connectedNodes
                .filter { nodes -> nodes.any { it.meshId == meshId } }
                .first()
                .find { it.meshId == meshId } ?: return@launch

            Log.d("PendingQueue", "$meshId back online — launching queued ${if (isVideo) "video" else "voice"} call")

            if (isVideo) {
                pendingVideoCalls.remove(meshId)
                // Trigger via broadcast to VideoCallActivity
                notifyPeerOnline(peer, isVideo = true)
            } else {
                pendingVoiceCalls.remove(meshId)
                notifyPeerOnline(peer, isVideo = false)
            }
        }
    }

    private fun notifyPeerOnline(node: MeshNode, isVideo: Boolean) {
        // Post a sticky event that PendingCallObserver picks up
        _onlinePeerEvents.tryEmit(OnlinePeerEvent(node, isVideo))
    }

    private val _onlinePeerEvents = MutableSharedFlow<OnlinePeerEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val onlinePeerEvents: SharedFlow<OnlinePeerEvent> = _onlinePeerEvents.asSharedFlow()

    data class OnlinePeerEvent(val node: MeshNode, val isVideo: Boolean)
}

// ═══════════════════════════════════════════════════════════════
// OFFLINE MESSAGE QUEUE
// Stocke les messages/fichiers quand le destinataire est hors ligne.
// Les livre automatiquement dès qu'il se reconnecte.
// ═══════════════════════════════════════════════════════════════

@Singleton
class OfflineMessageQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FMcallDatabase,
    private val meshManager: MeshNetworkManager,
    private val crypto: CryptoManager
) {
    companion object {
        private const val TAG = "OfflineMsgQueue"
        private const val MAX_RETRIES   = 10
        private const val RETRY_DELAY   = 5_000L    // 5s entre tentatives
        private const val MSG_EXPIRES   = 86_400_000L  // 24h TTL
    }

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queued = mutableListOf<QueuedMessage>()
    private val retryJobs = mutableMapOf<String, Job>()

    data class QueuedMessage(
        val message:    Message,
        val retries:    Int = 0,
        val queuedAt:   Long = System.currentTimeMillis(),
        val expiresAt:  Long = System.currentTimeMillis() + MSG_EXPIRES
    )

    init {
        startDeliveryWatcher()
        startExpiryPruner()
    }

    // ── Queue a message for offline delivery ─────────────────────────────────

    fun enqueue(message: Message) {
        synchronized(queued) {
            queued.add(QueuedMessage(message))
        }
        Log.d(TAG, "Queued message ${message.messageId} for ${message.recipientId}")

        // If peer comes online, deliver immediately
        watchForRecipientOnline(message.recipientId)
    }

    // ── Watch for peer to come online ────────────────────────────────────────

    private fun watchForRecipientOnline(recipientId: String) {
        if (retryJobs.containsKey(recipientId)) return  // already watching

        retryJobs[recipientId] = scope.launch {
            Log.d(TAG, "Watching for $recipientId to come online...")
            meshManager.connectedNodes
                .filter { nodes -> nodes.any { it.meshId == recipientId } }
                .first()

            Log.d(TAG, "$recipientId is online — delivering queued messages")
            deliverPendingMessages(recipientId)
            retryJobs.remove(recipientId)
        }
    }

    // ── Deliver all queued messages for a peer ───────────────────────────────

    private suspend fun deliverPendingMessages(recipientId: String) {
        val toDeliver = synchronized(queued) {
            queued.filter { it.message.recipientId == recipientId && !isExpired(it) }
        }

        toDeliver.forEach { qMsg ->
            if (qMsg.retries >= MAX_RETRIES) {
                markFailed(qMsg)
                return@forEach
            }

            val success = sendOverMesh(qMsg.message)
            if (success) {
                synchronized(queued) { queued.remove(qMsg) }
                db.messageDao().updateStatus(qMsg.message.messageId, MessageStatus.DELIVERED)
                Log.d(TAG, "✓ Delivered ${qMsg.message.messageId}")
            } else {
                synchronized(queued) {
                    val idx = queued.indexOf(qMsg)
                    if (idx >= 0) queued[idx] = qMsg.copy(retries = qMsg.retries + 1)
                }
                delay(RETRY_DELAY)
            }
        }
    }

    // ── Background delivery watcher (retry on reconnection) ─────────────────

    private fun startDeliveryWatcher() {
        scope.launch {
            meshManager.connectedNodes.collectLatest { nodes ->
                val onlineMeshIds = nodes.map { it.meshId }.toSet()
                val pendingRecipients = synchronized(queued) {
                    queued.map { it.message.recipientId }.distinct()
                }
                pendingRecipients
                    .filter { it in onlineMeshIds }
                    .forEach { recipientId ->
                        launch { deliverPendingMessages(recipientId) }
                    }
            }
        }
    }

    // ── Expiry pruner ────────────────────────────────────────────────────────

    private fun startExpiryPruner() {
        scope.launch {
            while (true) {
                delay(3_600_000)  // every hour
                val expired = synchronized(queued) {
                    queued.filter { isExpired(it) }.also { queued.removeAll(it) }
                }
                expired.forEach { markFailed(it) }
                Log.d(TAG, "Pruned ${expired.size} expired messages")
            }
        }
    }

    // ── Send message over mesh ────────────────────────────────────────────────

    private suspend fun sendOverMesh(msg: Message): Boolean {
        val sessionKey = meshManager.getSessionKey(msg.recipientId) ?: crypto.generateSessionKey()
        val payload    = msg.encryptedContent.toByteArray()
        val privKey    = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
        val packet = MeshPacket(
            type          = PacketType.MESSAGE,
            sourceId      = meshManager.localProfile.meshId,
            destinationId = msg.recipientId,
            payload       = payload,
            signature     = crypto.signData(payload, privKey)
        )
        return meshManager.sendPacket(packet)
    }

    private suspend fun markFailed(qMsg: QueuedMessage) {
        synchronized(queued) { queued.remove(qMsg) }
        db.messageDao().updateStatus(qMsg.message.messageId, MessageStatus.FAILED)
    }

    private fun isExpired(qMsg: QueuedMessage) =
        System.currentTimeMillis() > qMsg.expiresAt

    fun getPendingCount(recipientId: String) =
        synchronized(queued) { queued.count { it.message.recipientId == recipientId } }

    fun getAllPending() = synchronized(queued) { queued.toList() }
}

// ═══════════════════════════════════════════════════════════════
// VIDEO CALL SERVICE (foreground)
// ═══════════════════════════════════════════════════════════════

class VideoCallService : android.app.Service() {
    companion object {
        const val NOTIF_ID = 1003
        const val ACTION_VIDEO_START  = "VIDEO_START"
        const val ACTION_VIDEO_ANSWER = "VIDEO_ANSWER"
        const val ACTION_VIDEO_REJECT = "VIDEO_REJECT"
        const val ACTION_VIDEO_HANGUP = "VIDEO_HANGUP"
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
}
