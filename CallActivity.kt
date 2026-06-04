package com.fmcall.serval.ui.calls

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.fmcall.serval.data.model.*
import com.fmcall.serval.databinding.ActivityCallBinding
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.service.CallState
import com.fmcall.serval.service.VoiceCallService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ─────────────────────────────────────────
// CALL ACTIVITY
// ─────────────────────────────────────────
@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private val viewModel: CallViewModel by viewModels()

    private val peerMeshId by lazy { intent.getStringExtra(VoiceCallService.EXTRA_PEER_MESH_ID) ?: "" }
    private val peerName   by lazy { intent.getStringExtra(VoiceCallService.EXTRA_PEER_NAME) ?: "Inconnu" }
    private val isIncoming by lazy { intent.getBooleanExtra("is_incoming", false) }

    private var callSeconds = 0
    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeCallState()
    }

    private fun setupUI() {
        binding.tvPeerName.text = peerName
        binding.tvMeshId.text = peerMeshId
        binding.tvInitials.text = peerName.take(2).uppercase()

        if (isIncoming) {
            showIncomingUI()
        } else {
            showOutgoingUI()
        }

        binding.btnHangup.setOnClickListener { hangup() }
        binding.btnAnswer.setOnClickListener { answer() }
        binding.btnReject.setOnClickListener { reject() }
        binding.btnMute.setOnClickListener { viewModel.toggleMute(); updateMuteButton() }
        binding.btnSpeaker.setOnClickListener { viewModel.toggleSpeaker(); updateSpeakerButton() }
    }

    private fun showIncomingUI() {
        binding.incomingActions.visibility = View.VISIBLE
        binding.activeCallActions.visibility = View.GONE
        binding.tvCallStatus.text = "Appel entrant • Mesh"
        startPulseAnimation()
    }

    private fun showOutgoingUI() {
        binding.incomingActions.visibility = View.GONE
        binding.activeCallActions.visibility = View.VISIBLE
        binding.tvCallStatus.text = "Connexion en cours..."
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            viewModel.callState.collectLatest { state ->
                when (state) {
                    CallState.CONNECTED -> {
                        binding.incomingActions.visibility = View.GONE
                        binding.activeCallActions.visibility = View.VISIBLE
                        binding.tvCallStatus.text = "🔒 Chiffré • Wi-Fi Direct"
                        startCallTimer()
                    }
                    CallState.CALLING -> {
                        binding.tvCallStatus.text = "En attente de réponse..."
                    }
                    CallState.NO_ANSWER -> {
                        binding.tvCallStatus.text = "Pas de réponse"
                        delayedFinish()
                    }
                    CallState.IDLE -> finish()
                    else -> {}
                }
            }
        }
    }

    private fun startCallTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                callSeconds++
                val h = callSeconds / 3600
                val m = (callSeconds % 3600) / 60
                val s = callSeconds % 60
                binding.tvDuration.text = if (h > 0)
                    "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            }
        }
    }

    private fun answer() {
        val intent = Intent(this, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_CALL_ANSWER
        }
        startService(intent)
    }

    private fun reject() {
        val intent = Intent(this, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_CALL_REJECT
        }
        startService(intent)
        finish()
    }

    private fun hangup() {
        val intent = Intent(this, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_CALL_HANGUP
        }
        startService(intent)
        finish()
    }

    private fun updateMuteButton() {
        binding.btnMute.alpha = if (viewModel.isMuted.value) 0.5f else 1.0f
    }

    private fun updateSpeakerButton() {
        binding.btnSpeaker.alpha = if (viewModel.speakerOn.value) 1.0f else 0.5f
    }

    private fun startPulseAnimation() {
        val animator = android.animation.ObjectAnimator.ofFloat(
            binding.avatarContainer, "alpha", 0.6f, 1.0f
        ).apply {
            duration = 800
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }
        animator.start()
    }

    private fun delayedFinish() {
        lifecycleScope.launch {
            delay(2000)
            finish()
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Don't go back during active call
        if (viewModel.callState.value != CallState.CONNECTED) super.onBackPressed()
    }
}

// ─────────────────────────────────────────
// CALL VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class CallViewModel @Inject constructor(
    private val meshManager: MeshNetworkManager
) : ViewModel() {

    val callState: StateFlow<CallState> = MutableStateFlow(CallState.CALLING)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleSpeaker() {
        _speakerOn.value = !_speakerOn.value
        // Set audio routing
        val am = android.app.Application::class.java
        // AudioManager routing handled by VoiceCallService
    }
}

// ─────────────────────────────────────────
// CALLS FRAGMENT (call history)
// ─────────────────────────────────────────
@AndroidEntryPoint
class CallsFragment : androidx.fragment.app.Fragment() {

    private val viewModel: CallsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?
    ): android.view.View {
        val binding = com.fmcall.serval.databinding.FragmentCallsBinding
            .inflate(inflater, container, false)

        val adapter = CallLogAdapter { callLog ->
            startCall(callLog.peerId, callLog.peerName)
        }

        binding.rvCallLogs.adapter = adapter
        binding.rvCallLogs.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())

        lifecycleScope.launch {
            viewModel.callLogs.collectLatest { adapter.submitList(it) }
        }

        binding.fabNewCall.setOnClickListener {
            showContactPicker()
        }

        return binding.root
    }

    private fun startCall(meshId: String, name: String) {
        val intent = Intent(requireContext(), VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_CALL_OUTGOING
            putExtra(VoiceCallService.EXTRA_PEER_MESH_ID, meshId)
            putExtra(VoiceCallService.EXTRA_PEER_NAME, name)
        }
        requireContext().startService(intent)
    }

    private fun showContactPicker() {
        // Navigate to contacts for new call
        findNavController().navigate(com.fmcall.serval.R.id.action_calls_to_contacts)
    }
}

// ─────────────────────────────────────────
// CALLS VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class CallsViewModel @Inject constructor(
    private val db: com.fmcall.serval.data.FMcallDatabase
) : ViewModel() {
    val callLogs = db.callLogDao().observeRecent()
}

// ─────────────────────────────────────────
// CALL LOG ADAPTER
// ─────────────────────────────────────────
class CallLogAdapter(
    private val onCallClick: (CallLog) -> Unit
) : androidx.recyclerview.widget.ListAdapter<CallLog,
        androidx.recyclerview.widget.RecyclerView.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CallLog>() {
        override fun areItemsTheSame(a: CallLog, b: CallLog) = a.callId == b.callId
        override fun areContentsTheSame(a: CallLog, b: CallLog) = a == b
    }) {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int):
            androidx.recyclerview.widget.RecyclerView.ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(com.fmcall.serval.R.layout.item_call_log, parent, false)
        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(
        holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int
    ) {
        val log = getItem(position)
        holder.itemView.apply {
            val tvName = findViewById<android.widget.TextView>(com.fmcall.serval.R.id.tv_peer_name)
            val tvMeta = findViewById<android.widget.TextView>(com.fmcall.serval.R.id.tv_call_meta)
            val btnCall = findViewById<android.widget.ImageButton>(com.fmcall.serval.R.id.btn_call)

            tvName.text = log.peerName
            tvMeta.text = "${if (log.direction == CallDirection.INCOMING) "↙" else "↗"} " +
                    "${log.peerId} • ${formatDuration(log.duration)}"
            btnCall.setOnClickListener { onCallClick(log) }
        }
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds == 0L -> "Manqué"
        seconds < 60  -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

private fun android.app.Fragment.findNavController() =
    androidx.navigation.fragment.NavHostFragment.findNavController(
        this as androidx.fragment.app.Fragment
    )

private val android.app.Fragment.lifecycleScope get() =
    (this as androidx.fragment.app.Fragment).viewLifecycleOwner.lifecycleScope

private val android.view.View.LayoutInflater get() =
    android.view.LayoutInflater::class.java
