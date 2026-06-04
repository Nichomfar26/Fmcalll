package com.fmcall.serval.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// ─────────────────────────────────────────
// MESH NODE
// ─────────────────────────────────────────
@Entity(tableName = "mesh_nodes")
data class MeshNode(
    @PrimaryKey val nodeId: String = UUID.randomUUID().toString(),
    val meshId: String,          // FM#XXXX-XXXX
    val displayName: String,
    val publicKey: String,       // Base64 encoded public key
    val lastSeen: Long = System.currentTimeMillis(),
    val rssi: Int = 0,           // Signal strength dBm
    val hopCount: Int = 1,
    val transportType: TransportType = TransportType.WIFI_DIRECT,
    val isRelay: Boolean = false,
    val isTrusted: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 8765
) {
    val signalQuality: SignalQuality get() = when {
        rssi >= -50 -> SignalQuality.EXCELLENT
        rssi >= -60 -> SignalQuality.GOOD
        rssi >= -70 -> SignalQuality.FAIR
        else        -> SignalQuality.WEAK
    }
}

enum class TransportType { WIFI_DIRECT, WIFI_MESH, BLUETOOTH, HOTSPOT }
enum class SignalQuality  { EXCELLENT, GOOD, FAIR, WEAK }

// ─────────────────────────────────────────
// CONTACT
// ─────────────────────────────────────────
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val contactId: String = UUID.randomUUID().toString(),
    val meshId: String,
    val name: String,
    val publicKey: String,
    val avatarColor: Int = 0xFF00C853.toInt(),
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0L,
    val note: String = ""
)

// ─────────────────────────────────────────
// MESSAGE
// ─────────────────────────────────────────
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val messageId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,        // meshId
    val recipientId: String,     // meshId or group id
    val encryptedContent: String,
    val contentType: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENDING,
    val isOutgoing: Boolean = true,
    val hopPath: String = "",    // JSON array of hop node IDs
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L
)

enum class MessageType   { TEXT, IMAGE, FILE, AUDIO, LOCATION, CALL_LOG }
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }

// ─────────────────────────────────────────
// CONVERSATION
// ─────────────────────────────────────────
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val conversationId: String = UUID.randomUUID().toString(),
    val peerId: String,          // meshId of other party
    val peerName: String,
    val isGroup: Boolean = false,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false
)

// ─────────────────────────────────────────
// CALL LOG
// ─────────────────────────────────────────
@Entity(tableName = "call_logs")
data class CallLog(
    @PrimaryKey val callId: String = UUID.randomUUID().toString(),
    val peerId: String,
    val peerName: String,
    val direction: CallDirection,
    val status: CallStatus,
    val startTime: Long = System.currentTimeMillis(),
    val duration: Long = 0L,     // seconds
    val transportType: TransportType = TransportType.WIFI_DIRECT,
    val hopCount: Int = 1
)

enum class CallDirection { INCOMING, OUTGOING }
enum class CallStatus    { ANSWERED, MISSED, DECLINED, FAILED }

// ─────────────────────────────────────────
// USER PROFILE (stored locally)
// ─────────────────────────────────────────
data class UserProfile(
    val userId: String = UUID.randomUUID().toString(),
    val meshId: String,          // FM#XXXX-XXXX format
    val displayName: String,
    val privateKey: String,      // encrypted Base64
    val publicKey: String,       // Base64
    val meshChannel: Int = 6,
    val wifiMeshEnabled: Boolean = true,
    val bluetoothMeshEnabled: Boolean = true,
    val relayModeEnabled: Boolean = false,
    val language: String = "fr"
)

// ─────────────────────────────────────────
// MESH PACKET (protocol)
// ─────────────────────────────────────────
data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val type: PacketType,
    val sourceId: String,
    val destinationId: String,
    val ttl: Int = 8,            // Time to live (hop count limit)
    val payload: ByteArray,
    val signature: ByteArray,    // Ed25519 signature
    val timestamp: Long = System.currentTimeMillis(),
    val hopList: MutableList<String> = mutableListOf()
)

enum class PacketType {
    DISCOVERY_BEACON,
    DISCOVERY_RESPONSE,
    MESSAGE,
    CALL_INVITE,
    CALL_ANSWER,
    CALL_REJECT,
    CALL_HANGUP,
    VOICE_FRAME,
    FILE_CHUNK,
    FILE_ACK,
    RELAY_FORWARD,
    KEY_EXCHANGE,
    HEARTBEAT
}
