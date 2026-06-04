package com.fmcall.serval.ui.messages

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.fmcall.serval.R
import com.fmcall.serval.data.FMcallDatabase
import com.fmcall.serval.data.model.*
import com.fmcall.serval.databinding.*
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.security.CryptoManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─────────────────────────────────────────
// MESSAGES FRAGMENT
// ─────────────────────────────────────────
@AndroidEntryPoint
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MessagesViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = ConversationAdapter { conv ->
            val bundle = Bundle().apply {
                putString("conversationId", conv.conversationId)
                putString("peerName", conv.peerName)
                putString("peerId", conv.peerId)
            }
            findNavController().navigate(R.id.action_messages_to_chat, bundle)
        }

        binding.rvConversations.adapter = adapter
        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.conversations.collectLatest { adapter.submitList(it) }
        }

        binding.fabNewMessage.setOnClickListener {
            findNavController().navigate(R.id.action_messages_to_contacts)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─────────────────────────────────────────
// CHAT FRAGMENT
// ─────────────────────────────────────────
@AndroidEntryPoint
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()

    private val conversationId by lazy { arguments?.getString("conversationId") ?: "" }
    private val peerName by lazy { arguments?.getString("peerName") ?: "" }
    private val peerId   by lazy { arguments?.getString("peerId") ?: "" }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvChatName.text = peerName
        viewModel.setConversation(conversationId, peerId)

        val adapter = MessageAdapter()
        binding.rvMessages.adapter = adapter
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.layoutManager = layoutManager

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collectLatest {
                adapter.submitList(it)
                binding.rvMessages.smoothScrollToPosition(maxOf(0, it.size - 1))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.peerStatus.collectLatest { status ->
                binding.tvChatStatus.text = status
            }
        }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage(); true
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnCall.setOnClickListener {
            val intent = android.content.Intent(requireContext(),
                com.fmcall.serval.ui.calls.CallActivity::class.java).apply {
                putExtra(com.fmcall.serval.service.VoiceCallService.EXTRA_PEER_MESH_ID, peerId)
                putExtra(com.fmcall.serval.service.VoiceCallService.EXTRA_PEER_NAME, peerName)
            }
            startActivity(intent)
        }

        binding.btnAttach.setOnClickListener {
            openFilePicker()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        viewModel.sendMessage(text)
        binding.etMessage.text?.clear()
    }

    private fun openFilePicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == REQUEST_FILE && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri -> viewModel.sendFile(uri) }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object { private const val REQUEST_FILE = 42 }
}

// ─────────────────────────────────────────
// MESSAGES VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val db: FMcallDatabase
) : ViewModel() {
    val conversations = db.conversationDao().observeAll()
}

// ─────────────────────────────────────────
// CHAT VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val db: FMcallDatabase,
    private val meshManager: MeshNetworkManager,
    private val crypto: CryptoManager
) : ViewModel() {

    private val _conversationId = MutableStateFlow("")
    private val _peerId = MutableStateFlow("")

    val messages: Flow<List<Message>> = _conversationId
        .flatMapLatest { id -> if (id.isBlank()) emptyFlow() else db.messageDao().observeConversation(id) }

    val peerStatus: StateFlow<String> = meshManager.connectedNodes
        .map { nodes ->
            val node = nodes.find { it.meshId == _peerId.value }
            if (node != null) "🟢 En ligne • ${node.hopCount} saut(s) • ${node.transportType.name}"
            else "⚫ Hors ligne"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setConversation(convId: String, peerId: String) {
        _conversationId.value = convId
        _peerId.value = peerId
        viewModelScope.launch {
            db.conversationDao().markRead(convId)
        }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val sessionKey = meshManager.getSessionKey(_peerId.value) ?: crypto.generateSessionKey()
            val encrypted = crypto.encryptMessage(text, sessionKey)

            val msg = Message(
                conversationId = _conversationId.value,
                senderId = meshManager.localProfile.meshId,
                recipientId = _peerId.value,
                encryptedContent = encrypted,
                contentType = MessageType.TEXT,
                status = MessageStatus.SENDING,
                isOutgoing = true
            )
            db.messageDao().insert(msg)

            // Send over mesh
            val payload = encrypted.toByteArray()
            val privKey = android.util.Base64.decode(
                meshManager.localProfile.privateKey, android.util.Base64.NO_WRAP)
            val packet = com.fmcall.serval.data.model.MeshPacket(
                type = com.fmcall.serval.data.model.PacketType.MESSAGE,
                sourceId = meshManager.localProfile.meshId,
                destinationId = _peerId.value,
                payload = payload,
                signature = crypto.signData(payload, privKey)
            )

            val sent = meshManager.sendPacket(packet)
            db.messageDao().updateStatus(
                msg.messageId,
                if (sent) MessageStatus.SENT else MessageStatus.FAILED
            )
        }
    }

    fun sendFile(uri: android.net.Uri) {
        // File transfer implementation
        viewModelScope.launch {
            val msg = Message(
                conversationId = _conversationId.value,
                senderId = meshManager.localProfile.meshId,
                recipientId = _peerId.value,
                encryptedContent = "",
                contentType = MessageType.FILE,
                status = MessageStatus.SENDING,
                isOutgoing = true,
                filePath = uri.toString()
            )
            db.messageDao().insert(msg)
        }
    }
}

// ─────────────────────────────────────────
// CONVERSATIONS ADAPTER
// ─────────────────────────────────────────
class ConversationAdapter(
    private val onClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.VH>(
    object : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(a: Conversation, b: Conversation) = a.conversationId == b.conversationId
        override fun areContentsTheSame(a: Conversation, b: Conversation) = a == b
    }
) {
    inner class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val conv = getItem(position)
        with(holder.binding) {
            tvName.text = conv.peerName
            tvLastMessage.text = conv.lastMessage
            tvTime.text = formatTime(conv.lastMessageTime)
            tvInitials.text = conv.peerName.take(2).uppercase()

            if (conv.unreadCount > 0) {
                tvUnreadCount.visibility = View.VISIBLE
                tvUnreadCount.text = conv.unreadCount.toString()
                tvName.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tvUnreadCount.visibility = View.GONE
                tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            root.setOnClickListener { onClick(conv) }
        }
    }

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return ""
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(ts))
    }
}

// ─────────────────────────────────────────
// MESSAGES ADAPTER
// ─────────────────────────────────────────
class MessageAdapter : ListAdapter<Message, MessageAdapter.VH>(
    object : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.messageId == b.messageId
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }
) {
    companion object {
        private const val VIEW_SENT     = 0
        private const val VIEW_RECEIVED = 1
    }

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int) =
        if (getItem(position).isOutgoing) VIEW_SENT else VIEW_RECEIVED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutId = if (viewType == VIEW_SENT)
            R.layout.item_message_sent else R.layout.item_message_received
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        val tvContent = holder.itemView.findViewById<android.widget.TextView>(R.id.tv_message_content)
        val tvTime    = holder.itemView.findViewById<android.widget.TextView>(R.id.tv_message_time)
        val tvStatus  = holder.itemView.findViewById<android.widget.TextView?>(R.id.tv_message_status)

        // In a real app, decrypt with session key here
        tvContent.text = try {
            // Display placeholder for encrypted content
            "[Message chiffré]"
        } catch (e: Exception) { "⚠️ Erreur déchiffrement" }

        tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

        tvStatus?.text = when (msg.status) {
            MessageStatus.SENDING   -> "⏳"
            MessageStatus.SENT      -> "✓"
            MessageStatus.DELIVERED -> "✓✓"
            MessageStatus.READ      -> "✓✓"
            MessageStatus.FAILED    -> "✗"
        }
    }
}
