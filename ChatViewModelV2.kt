package com.fmcall.serval.ui.messages

import androidx.lifecycle.*
import com.fmcall.serval.data.FMcallDatabase
import com.fmcall.serval.data.model.*
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.security.CryptoManager
import com.fmcall.serval.service.OfflineMessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import android.util.Base64

/**
 * ChatViewModel enrichi avec :
 * - File d'attente offline : si le destinataire est hors ligne,
 *   le message est sauvegardé et livré automatiquement dès reconnexion
 * - Indicateur de statut en temps réel (en ligne, hors ligne, en train d'écrire)
 * - Déchiffrement des messages reçus
 */
@HiltViewModel
class ChatViewModelV2 @Inject constructor(
    private val db: FMcallDatabase,
    private val meshManager: MeshNetworkManager,
    private val crypto: CryptoManager,
    private val offlineQueue: OfflineMessageQueue
) : ViewModel() {

    private val _conversationId = MutableStateFlow("")
    private val _peerId = MutableStateFlow("")

    // ── Messages stream ───────────────────────────────────────────────────────
    val messages: Flow<List<MessageUi>> = _conversationId
        .flatMapLatest { id ->
            if (id.isBlank()) emptyFlow()
            else db.messageDao().observeConversation(id).map { list ->
                list.map { msg -> MessageUi(
                    messageId    = msg.messageId,
                    displayText  = decryptDisplay(msg),
                    isOutgoing   = msg.isOutgoing,
                    timestamp    = msg.timestamp,
                    status       = msg.status,
                    contentType  = msg.contentType,
                    fileName     = msg.fileName,
                    fileSize     = msg.fileSize
                )}
            }
        }

    // ── Peer status ───────────────────────────────────────────────────────────
    data class PeerStatus(
        val isOnline:     Boolean,
        val statusText:   String,
        val pendingCount: Int
    )

    val peerStatus: StateFlow<PeerStatus> = combine(
        meshManager.connectedNodes,
        _peerId
    ) { nodes, peerId ->
        val node = nodes.find { it.meshId == peerId }
        val pending = offlineQueue.getPendingCount(peerId)
        if (node != null) {
            PeerStatus(
                isOnline   = true,
                statusText = "🟢 En ligne • ${node.hopCount} saut(s) • ${node.transportType.name}",
                pendingCount = pending
            )
        } else {
            val pendingText = if (pending > 0) " • $pending message(s) en attente" else ""
            PeerStatus(
                isOnline   = false,
                statusText = "⚫ Hors ligne$pendingText — livraison auto à reconnexion",
                pendingCount = pending
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
        PeerStatus(false, "⚫ Hors ligne", 0))

    // ── Init ──────────────────────────────────────────────────────────────────
    fun setConversation(convId: String, peerId: String) {
        _conversationId.value = convId
        _peerId.value = peerId
        viewModelScope.launch { db.conversationDao().markRead(convId) }
        listenForIncomingMessages(peerId)
    }

    // ── Send message (online or queued) ───────────────────────────────────────
    fun sendMessage(text: String) {
        viewModelScope.launch {
            val peerId = _peerId.value
            val sessionKey = meshManager.getSessionKey(peerId) ?: crypto.generateSessionKey()
            val encrypted  = crypto.encryptMessage(text, sessionKey)

            val msg = Message(
                conversationId  = _conversationId.value,
                senderId        = meshManager.localProfile.meshId,
                recipientId     = peerId,
                encryptedContent = encrypted,
                contentType     = MessageType.TEXT,
                status          = MessageStatus.SENDING,
                isOutgoing      = true
            )
            db.messageDao().insert(msg)
            updateConversationLastMsg(text)

            // Check if peer is online
            val peerOnline = meshManager.getNodeByMeshId(peerId) != null

            if (peerOnline) {
                // Send immediately
                val sent = sendOverMesh(msg)
                db.messageDao().updateStatus(
                    msg.messageId,
                    if (sent) MessageStatus.SENT else MessageStatus.FAILED
                )
                if (!sent) {
                    // Fallback to offline queue
                    offlineQueue.enqueue(msg)
                    db.messageDao().updateStatus(msg.messageId, MessageStatus.SENDING)
                }
            } else {
                // Peer offline → queue for later delivery
                db.messageDao().updateStatus(msg.messageId, MessageStatus.SENDING)
                offlineQueue.enqueue(msg)
                // The OfflineMessageQueue will update status to DELIVERED when sent
            }
        }
    }

    // ── Send file ─────────────────────────────────────────────────────────────
    fun sendFile(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val peerId = _peerId.value
            val cr     = context.contentResolver
            val cursor = cr.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.SIZE)
            cursor?.moveToFirst()
            val fileName = cursor?.getString(nameIndex ?: 0) ?: "fichier"
            val fileSize = cursor?.getLong(sizeIndex ?: 0) ?: 0L
            cursor?.close()

            val sessionKey = meshManager.getSessionKey(peerId) ?: crypto.generateSessionKey()
            val msg = Message(
                conversationId  = _conversationId.value,
                senderId        = meshManager.localProfile.meshId,
                recipientId     = peerId,
                encryptedContent = "",
                contentType     = MessageType.FILE,
                status          = MessageStatus.SENDING,
                isOutgoing      = true,
                filePath        = uri.toString(),
                fileName        = fileName,
                fileSize        = fileSize
            )
            db.messageDao().insert(msg)
            updateConversationLastMsg("📎 $fileName")

            val peerOnline = meshManager.getNodeByMeshId(peerId) != null
            if (peerOnline) {
                sendFileOverMesh(msg, uri, context, sessionKey)
            } else {
                offlineQueue.enqueue(msg)
            }
        }
    }

    // ── Listen for incoming messages ──────────────────────────────────────────
    private fun listenForIncomingMessages(peerId: String) {
        viewModelScope.launch {
            meshManager.incomingPackets
                .filter { it.type == PacketType.MESSAGE && it.sourceId == peerId }
                .collect { packet ->
                    val sessionKey = meshManager.getSessionKey(peerId)
                    val decrypted = if (sessionKey != null)
                        crypto.decryptMessage(String(packet.payload), sessionKey)
                    else String(packet.payload)

                    val msg = Message(
                        conversationId  = _conversationId.value,
                        senderId        = peerId,
                        recipientId     = meshManager.localProfile.meshId,
                        encryptedContent = String(packet.payload),
                        contentType     = MessageType.TEXT,
                        status          = MessageStatus.READ,
                        isOutgoing      = false
                    )
                    db.messageDao().insert(msg)
                    updateConversationLastMsg(decrypted)

                    // Send delivery receipt
                    sendDeliveryReceipt(peerId, packet.packetId)
                }
        }
    }

    private suspend fun sendDeliveryReceipt(peerId: String, packetId: String) {
        val payload = "DELIVERED|$packetId".toByteArray()
        val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
        meshManager.sendPacket(MeshPacket(
            type          = PacketType.HEARTBEAT,
            sourceId      = meshManager.localProfile.meshId,
            destinationId = peerId,
            payload       = payload,
            signature     = crypto.signData(payload, privKey),
            ttl           = 3
        ))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun sendOverMesh(msg: Message): Boolean {
        val payload = msg.encryptedContent.toByteArray()
        val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
        return meshManager.sendPacket(MeshPacket(
            type          = PacketType.MESSAGE,
            sourceId      = meshManager.localProfile.meshId,
            destinationId = msg.recipientId,
            payload       = payload,
            signature     = crypto.signData(payload, privKey)
        ))
    }

    private suspend fun sendFileOverMesh(
        msg: Message, uri: android.net.Uri,
        context: android.content.Context, sessionKey: ByteArray
    ) {
        val CHUNK_SIZE = 8192
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return
            val data = stream.readBytes()
            stream.close()

            var offset = 0
            var chunkIndex = 0
            val totalChunks = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)

            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(offset + CHUNK_SIZE, data.size))
                val encrypted = crypto.encryptMessage(Base64.encodeToString(chunk, Base64.NO_WRAP), sessionKey)
                val header = "${msg.messageId}|$chunkIndex|$totalChunks|${msg.fileName}".toByteArray()
                val payload = header + "|".toByteArray() + encrypted.toByteArray()

                meshManager.sendPacket(MeshPacket(
                    type          = PacketType.FILE_CHUNK,
                    sourceId      = meshManager.localProfile.meshId,
                    destinationId = msg.recipientId,
                    payload       = payload,
                    signature     = crypto.signData(payload, privKey)
                ))
                offset += CHUNK_SIZE
                chunkIndex++
                delay(50) // Throttle to avoid flooding mesh
            }
            db.messageDao().updateStatus(msg.messageId, MessageStatus.DELIVERED)
        } catch (e: Exception) {
            db.messageDao().updateStatus(msg.messageId, MessageStatus.FAILED)
        }
    }

    private suspend fun updateConversationLastMsg(preview: String) {
        val conv = com.fmcall.serval.data.model.Conversation(
            conversationId  = _conversationId.value,
            peerId          = _peerId.value,
            peerName        = "",
            lastMessage     = preview,
            lastMessageTime = System.currentTimeMillis()
        )
        db.conversationDao().upsert(conv)
    }

    private fun decryptDisplay(msg: Message): String {
        if (msg.contentType == MessageType.FILE) return "📎 ${msg.fileName ?: "fichier"}"
        val sessionKey = meshManager.getSessionKey(
            if (msg.isOutgoing) msg.recipientId else msg.senderId
        ) ?: return msg.encryptedContent
        return try {
            crypto.decryptMessage(msg.encryptedContent, sessionKey)
        } catch (e: Exception) { "🔒 [chiffré]" }
    }
}

// ── UI data class for messages ────────────────────────────────────────────────
data class MessageUi(
    val messageId:   String,
    val displayText: String,
    val isOutgoing:  Boolean,
    val timestamp:   Long,
    val status:      MessageStatus,
    val contentType: MessageType,
    val fileName:    String? = null,
    val fileSize:    Long = 0L
)
