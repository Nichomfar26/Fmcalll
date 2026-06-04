package com.fmcall.serval.service

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.fmcall.serval.FMcallApplication
import com.fmcall.serval.R
import com.fmcall.serval.data.model.*
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.security.CryptoManager
import com.fmcall.serval.ui.calls.CallActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import javax.inject.Inject
import android.util.Log

@AndroidEntryPoint
class VoiceCallService : Service() {

    companion object {
        private const val TAG = "VoiceCallService"
        const val NOTIF_ID = 1002

        // Call actions
        const val ACTION_CALL_OUTGOING = "CALL_OUTGOING"
        const val ACTION_CALL_ANSWER   = "CALL_ANSWER"
        const val ACTION_CALL_REJECT   = "CALL_REJECT"
        const val ACTION_CALL_HANGUP   = "CALL_HANGUP"
        const val EXTRA_PEER_MESH_ID   = "peer_mesh_id"
        const val EXTRA_PEER_NAME      = "peer_name"

        // Audio config
        private const val SAMPLE_RATE     = 16000
        private const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE      = 320   // 20ms at 16kHz
    }

    @Inject lateinit var meshManager: MeshNetworkManager
    @Inject lateinit var crypto: CryptoManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isCallActive = false
    private var callStartTime = 0L

    // Call state
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentPeerMeshId: String = ""
    private var currentPeerName: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL_OUTGOING -> {
                currentPeerMeshId = intent.getStringExtra(EXTRA_PEER_MESH_ID) ?: ""
                currentPeerName   = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Inconnu"
                startOutgoingCall()
            }
            ACTION_CALL_ANSWER  -> answerCall()
            ACTION_CALL_REJECT  -> rejectCall()
            ACTION_CALL_HANGUP  -> hangupCall()
        }
        return START_STICKY
    }

    // ── Outgoing Call ─────────────────────────────────────────────────────────

    private fun startOutgoingCall() {
        _callState.value = CallState.CALLING
        startForeground(NOTIF_ID, buildCallNotification("Appel en cours...", currentPeerName))

        scope.launch {
            val sessionKey = crypto.generateSessionKey()
            val payload = buildCallInvitePayload(sessionKey)

            val packet = MeshPacket(
                type = PacketType.CALL_INVITE,
                sourceId = meshManager.localProfile.meshId,
                destinationId = currentPeerMeshId,
                payload = payload,
                signature = signPacket(payload)
            )
            meshManager.sendPacket(packet)

            // Wait for answer (30s timeout)
            withTimeoutOrNull(30_000) {
                meshManager.incomingPackets
                    .filter { it.type == PacketType.CALL_ANSWER && it.sourceId == currentPeerMeshId }
                    .first()
            }?.let {
                startAudioSession(sessionKey)
            } ?: run {
                _callState.value = CallState.NO_ANSWER
                stopSelf()
            }
        }

        showCallActivity()
    }

    // ── Incoming Call ─────────────────────────────────────────────────────────

    fun handleIncomingCallInvite(packet: MeshPacket) {
        currentPeerMeshId = packet.sourceId
        currentPeerName = meshManager.getNodeByMeshId(packet.sourceId)?.displayName ?: "Inconnu"
        _callState.value = CallState.RINGING

        startForeground(NOTIF_ID, buildIncomingCallNotification())
        showIncomingCallActivity()
        vibrateRingtone()
    }

    private fun answerCall() {
        _callState.value = CallState.CONNECTED
        callStartTime = System.currentTimeMillis()

        val sessionKey = crypto.getSessionKey(currentPeerMeshId) ?: crypto.generateSessionKey()
        val answerPayload = "ANSWER".toByteArray()
        val packet = MeshPacket(
            type = PacketType.CALL_ANSWER,
            sourceId = meshManager.localProfile.meshId,
            destinationId = currentPeerMeshId,
            payload = answerPayload,
            signature = signPacket(answerPayload)
        )

        scope.launch {
            meshManager.sendPacket(packet)
            startAudioSession(sessionKey)
        }
        updateNotification("En communication", currentPeerName)
    }

    private fun rejectCall() {
        val payload = "REJECT".toByteArray()
        val packet = MeshPacket(
            type = PacketType.CALL_REJECT,
            sourceId = meshManager.localProfile.meshId,
            destinationId = currentPeerMeshId,
            payload = payload,
            signature = signPacket(payload)
        )
        scope.launch { meshManager.sendPacket(packet) }
        _callState.value = CallState.IDLE
        stopSelf()
    }

    fun hangupCall() {
        if (!isCallActive && _callState.value == CallState.IDLE) return

        val payload = "HANGUP".toByteArray()
        val packet = MeshPacket(
            type = PacketType.CALL_HANGUP,
            sourceId = meshManager.localProfile.meshId,
            destinationId = currentPeerMeshId,
            payload = payload,
            signature = signPacket(payload)
        )
        scope.launch { meshManager.sendPacket(packet) }

        stopAudioSession()
        _callState.value = CallState.IDLE
        stopSelf()
    }

    // ── Audio Session ─────────────────────────────────────────────────────────

    private fun startAudioSession(sessionKey: ByteArray) {
        isCallActive = true
        _callState.value = CallState.CONNECTED
        callStartTime = System.currentTimeMillis()

        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(FRAME_SIZE * 4)

        // Capture audio
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
        )

        // Playback audio
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord?.startRecording()
        audioTrack?.play()

        // Capture & send loop
        scope.launch(Dispatchers.IO) {
            val frame = ByteArray(FRAME_SIZE * 2)
            while (isCallActive) {
                val read = audioRecord?.read(frame, 0, frame.size) ?: break
                if (read > 0) {
                    val encrypted = encryptVoiceFrame(frame.copyOfRange(0, read), sessionKey)
                    val packet = MeshPacket(
                        type = PacketType.VOICE_FRAME,
                        sourceId = meshManager.localProfile.meshId,
                        destinationId = currentPeerMeshId,
                        payload = encrypted,
                        signature = ByteArray(64), // lightweight for audio
                        ttl = MAX_HOPS_AUDIO
                    )
                    meshManager.sendPacket(packet)
                }
            }
        }

        // Receive & play loop
        scope.launch(Dispatchers.IO) {
            meshManager.incomingPackets
                .filter { it.type == PacketType.VOICE_FRAME && it.sourceId == currentPeerMeshId }
                .collect { packet ->
                    val pcm = decryptVoiceFrame(packet.payload, sessionKey)
                    audioTrack?.write(pcm, 0, pcm.size)
                }
        }

        // Listen for hangup
        scope.launch {
            meshManager.incomingPackets
                .filter { it.type == PacketType.CALL_HANGUP && it.sourceId == currentPeerMeshId }
                .first()
            stopAudioSession()
            _callState.value = CallState.IDLE
        }
    }

    private fun stopAudioSession() {
        isCallActive = false
        try {
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        } catch (e: Exception) { Log.e(TAG, "Audio stop error", e) }
    }

    private fun encryptVoiceFrame(pcm: ByteArray, key: ByteArray): ByteArray {
        // Lightweight XOR encryption for voice frames (speed > security for audio)
        return ByteArray(pcm.size) { i -> (pcm[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    private fun decryptVoiceFrame(data: ByteArray, key: ByteArray): ByteArray {
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun buildCallNotification(title: String, content: String): Notification {
        val hangupIntent = Intent(this, VoiceCallService::class.java).apply { action = ACTION_CALL_HANGUP }
        val hangupPi = PendingIntent.getService(this, 0, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, FMcallApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .addAction(R.drawable.ic_call_end, "Raccrocher", hangupPi)
            .build()
    }

    private fun buildIncomingCallNotification(): Notification {
        val answerIntent = Intent(this, VoiceCallService::class.java).apply { action = ACTION_CALL_ANSWER }
        val rejectIntent = Intent(this, VoiceCallService::class.java).apply { action = ACTION_CALL_REJECT }
        val answerPi = PendingIntent.getService(this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val rejectPi = PendingIntent.getService(this, 2, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, FMcallApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Appel entrant")
            .setContentText(currentPeerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(buildFullScreenPendingIntent(), true)
            .addAction(R.drawable.ic_call_end, "Refuser", rejectPi)
            .addAction(R.drawable.ic_call, "Répondre", answerPi)
            .build()
    }

    private fun buildFullScreenPendingIntent(): PendingIntent {
        val i = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 3, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildCallNotification(title, content))
    }

    private fun showCallActivity() {
        val i = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_PEER_MESH_ID, currentPeerMeshId)
            putExtra(EXTRA_PEER_NAME, currentPeerName)
        }
        startActivity(i)
    }

    private fun showIncomingCallActivity() {
        val i = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_PEER_MESH_ID, currentPeerMeshId)
            putExtra(EXTRA_PEER_NAME, currentPeerName)
            putExtra("is_incoming", true)
        }
        startActivity(i)
    }

    private fun vibrateRingtone() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 500, 300, 500), 0
            ))
        }
    }

    private fun signPacket(data: ByteArray): ByteArray {
        val privKey = android.util.Base64.decode(
            meshManager.localProfile.privateKey, android.util.Base64.NO_WRAP)
        return crypto.signData(data, privKey)
    }

    private fun buildCallInvitePayload(sessionKey: ByteArray): ByteArray {
        val keyB64 = android.util.Base64.encodeToString(sessionKey, android.util.Base64.NO_WRAP)
        return "INVITE|$keyB64|${meshManager.localProfile.displayName}".toByteArray()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAudioSession()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_HOPS_AUDIO = 4
    }
}

enum class CallState { IDLE, CALLING, RINGING, CONNECTED, NO_ANSWER, FAILED }
