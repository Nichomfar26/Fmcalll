package com.fmcall.serval.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.fmcall.serval.R
import com.fmcall.serval.data.model.*
import com.fmcall.serval.databinding.ActivityVideoCallBinding
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.security.CryptoManager
import com.fmcall.serval.service.VideoCallService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import android.util.Base64

@AndroidEntryPoint
class VideoCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_MESH_ID = "peer_mesh_id"
        const val EXTRA_PEER_NAME    = "peer_name"
        const val EXTRA_IS_INCOMING  = "is_incoming"
    }

    private lateinit var binding: ActivityVideoCallBinding
    private val viewModel: VideoCallViewModel by viewModels()

    private val peerMeshId by lazy { intent.getStringExtra(EXTRA_PEER_MESH_ID) ?: "" }
    private val peerName   by lazy { intent.getStringExtra(EXTRA_PEER_NAME) ?: "Inconnu" }
    private val isIncoming by lazy { intent.getBooleanExtra(EXTRA_IS_INCOMING, false) }

    private var callSeconds = 0
    private var timerJob: Job? = null
    private var isMuted   = false
    private var isCameraOff = false
    private var isFrontCamera = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startVideoSession()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUI()
        observeCallState()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        binding.tvPeerName.text = peerName
        binding.tvMeshId.text   = peerMeshId
        binding.tvInitials.text = peerName.take(2).uppercase()

        if (isIncoming) {
            binding.incomingActions.visibility = View.VISIBLE
            binding.activeActions.visibility   = View.GONE
            binding.tvCallStatus.text = "Appel vidéo entrant"
        } else {
            binding.incomingActions.visibility = View.GONE
            binding.activeActions.visibility   = View.VISIBLE
            binding.tvCallStatus.text = "Connexion vidéo..."
        }

        // Active call buttons
        binding.btnHangup.setOnClickListener     { hangup() }
        binding.btnMute.setOnClickListener       { toggleMute() }
        binding.btnCameraOff.setOnClickListener  { toggleCamera() }
        binding.btnFlipCamera.setOnClickListener { flipCamera() }

        // Incoming call buttons
        binding.btnAnswer.setOnClickListener     { answerCall() }
        binding.btnReject.setOnClickListener     { rejectCall() }

        // Switch cameras on tap of local preview
        binding.localVideoPreview.setOnClickListener { flipCamera() }
    }

    private fun checkPermissionsAndStart() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (!isIncoming) startVideoSession()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startVideoSession() {
        viewModel.startVideoCall(peerMeshId, peerName, isIncoming)
        if (!isIncoming) startCallTimer()
    }

    private fun answerCall() {
        binding.incomingActions.visibility = View.GONE
        binding.activeActions.visibility   = View.VISIBLE
        binding.tvCallStatus.text = "🔒 Vidéo chiffrée • Mesh"
        viewModel.answerVideoCall()
        startVideoSession()
        startCallTimer()
    }

    private fun rejectCall() {
        viewModel.rejectVideoCall()
        finish()
    }

    private fun hangup() {
        viewModel.hangupVideoCall()
        finish()
    }

    private fun toggleMute() {
        isMuted = !isMuted
        viewModel.setMuted(isMuted)
        binding.btnMute.alpha = if (isMuted) 0.5f else 1.0f
        binding.btnMute.setImageResource(
            if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
        )
    }

    private fun toggleCamera() {
        isCameraOff = !isCameraOff
        viewModel.setCameraEnabled(!isCameraOff)
        binding.localVideoPreview.visibility = if (isCameraOff) View.INVISIBLE else View.VISIBLE
        binding.btnCameraOff.alpha = if (isCameraOff) 0.5f else 1.0f
    }

    private fun flipCamera() {
        isFrontCamera = !isFrontCamera
        viewModel.flipCamera(isFrontCamera)
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            viewModel.videoCallState.collectLatest { state ->
                when (state) {
                    VideoCallState.CONNECTED -> {
                        binding.tvCallStatus.text = "🔒 Vidéo chiffrée • Mesh"
                        binding.remoteVideoContainer.visibility = View.VISIBLE
                        binding.avatarFallback.visibility = View.GONE
                    }
                    VideoCallState.WAITING_PEER -> {
                        binding.tvCallStatus.text = "⏳ En attente de connexion..."
                        showWaitingUI()
                    }
                    VideoCallState.RINGING -> {
                        binding.tvCallStatus.text = "📳 Sonnerie..."
                    }
                    VideoCallState.NO_ANSWER -> {
                        binding.tvCallStatus.text = "Pas de réponse"
                        lifecycleScope.launch { delay(2000); finish() }
                    }
                    VideoCallState.PEER_OFFLINE -> {
                        binding.tvCallStatus.text = "⚫ Hors ligne — message en attente"
                        showOfflineUI()
                    }
                    VideoCallState.ENDED, VideoCallState.IDLE -> finish()
                    else -> {}
                }
            }
        }

        // Remote video frame
        lifecycleScope.launch {
            viewModel.remoteVideoFrame.collectLatest { bitmap ->
                bitmap?.let { binding.remoteVideoView.setImageBitmap(it) }
            }
        }

        // Local video frame
        lifecycleScope.launch {
            viewModel.localVideoFrame.collectLatest { bitmap ->
                bitmap?.let { binding.localVideoPreview.setImageBitmap(it) }
            }
        }
    }

    private fun showWaitingUI() {
        binding.waitingContainer.visibility = View.VISIBLE
        binding.tvWaitingMessage.text = "En attente que $peerName se connecte au réseau Mesh..."
        // Pulse animation
        val anim = android.animation.ObjectAnimator.ofFloat(
            binding.waitingIcon, "alpha", 0.3f, 1.0f
        ).apply {
            duration = 1000
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }
        anim.start()
    }

    private fun showOfflineUI() {
        binding.waitingContainer.visibility = View.VISIBLE
        binding.tvWaitingMessage.text =
            "$peerName est hors ligne.\nL'appel vidéo sera lancé automatiquement dès sa reconnexion."
        binding.btnCancelWait.visibility = View.VISIBLE
        binding.btnCancelWait.setOnClickListener {
            viewModel.cancelPendingCall()
            finish()
        }
    }

    private fun startCallTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                callSeconds++
                val m = (callSeconds / 60).toString().padStart(2, '0')
                val s = (callSeconds % 60).toString().padStart(2, '0')
                binding.tvDuration.text = "$m:$s"
            }
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        viewModel.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (viewModel.videoCallState.value == VideoCallState.CONNECTED) return
        super.onBackPressed()
    }
}

// ─────────────────────────────────────────
// VIDEO CALL VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class VideoCallViewModel @Inject constructor(
    private val meshManager: MeshNetworkManager,
    private val crypto: CryptoManager,
    private val pendingCallQueue: PendingCallQueue
) : ViewModel() {

    private val _videoCallState = MutableStateFlow(VideoCallState.IDLE)
    val videoCallState: StateFlow<VideoCallState> = _videoCallState.asStateFlow()

    private val _remoteVideoFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val remoteVideoFrame: StateFlow<android.graphics.Bitmap?> = _remoteVideoFrame.asStateFlow()

    private val _localVideoFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val localVideoFrame: StateFlow<android.graphics.Bitmap?> = _localVideoFrame.asStateFlow()

    private var peerMeshId = ""
    private var peerName   = ""
    private var sessionKey: ByteArray? = null
    private var frameJob: Job? = null
    private var waitingJob: Job? = null

    fun startVideoCall(meshId: String, name: String, isIncoming: Boolean) {
        peerMeshId = meshId
        peerName   = name
        sessionKey = crypto.generateSessionKey()

        if (isIncoming) {
            _videoCallState.value = VideoCallState.RINGING
            return
        }

        // Check if peer is online
        val peerNode = meshManager.getNodeByMeshId(meshId)
        if (peerNode != null) {
            _videoCallState.value = VideoCallState.RINGING
            sendVideoCallInvite()
            waitForAnswer()
        } else {
            // Peer offline — add to pending queue
            _videoCallState.value = VideoCallState.PEER_OFFLINE
            pendingCallQueue.addPendingVideoCall(meshId, name)
            watchForPeerReconnection(meshId)
        }
    }

    private fun sendVideoCallInvite() {
        viewModelScope.launch {
            val key = sessionKey ?: return@launch
            val payload = "VIDEO_INVITE|${Base64.encodeToString(key, Base64.NO_WRAP)}|$peerName".toByteArray()
            val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
            val packet = MeshPacket(
                type          = PacketType.CALL_INVITE,
                sourceId      = meshManager.localProfile.meshId,
                destinationId = peerMeshId,
                payload       = payload,
                signature     = crypto.signData(payload, privKey)
            )
            meshManager.sendPacket(packet)
        }
    }

    private fun waitForAnswer() {
        waitingJob = viewModelScope.launch {
            _videoCallState.value = VideoCallState.WAITING_PEER
            withTimeoutOrNull(45_000) {
                meshManager.incomingPackets
                    .filter { it.type == PacketType.CALL_ANSWER && it.sourceId == peerMeshId }
                    .first()
            }?.let {
                _videoCallState.value = VideoCallState.CONNECTED
                startVideoStreaming()
            } ?: run {
                _videoCallState.value = VideoCallState.NO_ANSWER
            }
        }
    }

    /**
     * Watch for peer to come back online.
     * When detected, automatically re-send the call invite.
     */
    private fun watchForPeerReconnection(meshId: String) {
        viewModelScope.launch {
            meshManager.connectedNodes
                .filter { nodes -> nodes.any { it.meshId == meshId } }
                .first()

            // Peer is back online!
            _videoCallState.value = VideoCallState.RINGING
            pendingCallQueue.removePendingVideoCall(meshId)
            sendVideoCallInvite()
            waitForAnswer()
        }
    }

    fun answerVideoCall() {
        viewModelScope.launch {
            val payload = "VIDEO_ANSWER".toByteArray()
            val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
            val packet = MeshPacket(
                type          = PacketType.CALL_ANSWER,
                sourceId      = meshManager.localProfile.meshId,
                destinationId = peerMeshId,
                payload       = payload,
                signature     = crypto.signData(payload, privKey)
            )
            meshManager.sendPacket(packet)
            _videoCallState.value = VideoCallState.CONNECTED
            startVideoStreaming()
        }
    }

    fun rejectVideoCall() {
        viewModelScope.launch {
            val payload = "VIDEO_REJECT".toByteArray()
            val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
            meshManager.sendPacket(MeshPacket(
                type          = PacketType.CALL_REJECT,
                sourceId      = meshManager.localProfile.meshId,
                destinationId = peerMeshId,
                payload       = payload,
                signature     = crypto.signData(payload, privKey)
            ))
            _videoCallState.value = VideoCallState.ENDED
        }
    }

    fun hangupVideoCall() {
        viewModelScope.launch {
            val payload = "HANGUP".toByteArray()
            val privKey = Base64.decode(meshManager.localProfile.privateKey, Base64.NO_WRAP)
            meshManager.sendPacket(MeshPacket(
                type          = PacketType.CALL_HANGUP,
                sourceId      = meshManager.localProfile.meshId,
                destinationId = peerMeshId,
                payload       = payload,
                signature     = crypto.signData(payload, privKey)
            ))
            stopStreaming()
            _videoCallState.value = VideoCallState.ENDED
        }
    }

    fun cancelPendingCall() {
        pendingCallQueue.removePendingVideoCall(peerMeshId)
        waitingJob?.cancel()
        _videoCallState.value = VideoCallState.IDLE
    }

    // ── Video Streaming ───────────────────────────────────────────────────────

    private fun startVideoStreaming() {
        val key = sessionKey ?: return

        // Receive remote video frames
        frameJob = viewModelScope.launch {
            meshManager.incomingPackets
                .filter { it.type == PacketType.VOICE_FRAME && it.sourceId == peerMeshId }
                .collect { packet ->
                    // Decrypt and decode frame
                    val decrypted = decryptVideoFrame(packet.payload, key)
                    val bitmap = decodeToBitmap(decrypted)
                    _remoteVideoFrame.value = bitmap
                }
        }

        // Listen for hangup
        viewModelScope.launch {
            meshManager.incomingPackets
                .filter { it.type == PacketType.CALL_HANGUP && it.sourceId == peerMeshId }
                .first()
            stopStreaming()
            _videoCallState.value = VideoCallState.ENDED
        }
    }

    private fun stopStreaming() {
        frameJob?.cancel()
        waitingJob?.cancel()
    }

    fun setMuted(muted: Boolean) { /* Control AudioRecord */ }
    fun setCameraEnabled(enabled: Boolean) { /* Control Camera capture */ }
    fun flipCamera(front: Boolean) { /* Switch camera */ }

    private fun encryptVideoFrame(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    private fun decryptVideoFrame(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    private fun decodeToBitmap(data: ByteArray): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
    } catch (e: Exception) { null }

    fun release() { stopStreaming() }
}

enum class VideoCallState {
    IDLE, RINGING, WAITING_PEER, CONNECTED, PEER_OFFLINE, NO_ANSWER, ENDED
}
