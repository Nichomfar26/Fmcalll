package com.fmcall.serval.ui.contacts

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.*
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import com.fmcall.serval.R
import com.fmcall.serval.data.FMcallDatabase
import com.fmcall.serval.data.model.*
import com.fmcall.serval.databinding.*
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.service.VoiceCallService
import com.fmcall.serval.service.VideoCallService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import android.content.Intent

// ─────────────────────────────────────────
// CONTACTS FRAGMENT
// ─────────────────────────────────────────
@AndroidEntryPoint
class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContactsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = ContactAdapter(
            onCall        = { contact -> startVoiceCall(contact) },
            onVideoCall   = { contact -> startVideoCall(contact) },
            onMessage     = { contact -> openChat(contact) },
            onAddFromMesh = { node    -> viewModel.addContactFromNode(node) }
        )

        binding.rvContacts.adapter = adapter
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())

        // Search
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.search(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        })

        // Tab: Contacts | Nœuds détectés
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                viewModel.setTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.displayList.collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // FAB: ajouter manuellement
        binding.fabAddContact.setOnClickListener { showAddManualDialog() }
    }

    private fun startVoiceCall(contact: Contact) {
        val intent = Intent(requireContext(), VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_CALL_OUTGOING
            putExtra(VoiceCallService.EXTRA_PEER_MESH_ID, contact.meshId)
            putExtra(VoiceCallService.EXTRA_PEER_NAME, contact.name)
        }
        requireContext().startService(intent)
    }

    private fun startVideoCall(contact: Contact) {
        val intent = Intent(requireContext(), VideoCallActivity::class.java).apply {
            putExtra(VideoCallActivity.EXTRA_PEER_MESH_ID, contact.meshId)
            putExtra(VideoCallActivity.EXTRA_PEER_NAME, contact.name)
            putExtra(VideoCallActivity.EXTRA_IS_INCOMING, false)
        }
        startActivity(intent)
    }

    private fun openChat(contact: Contact) {
        val bundle = Bundle().apply {
            putString("conversationId", contact.contactId)
            putString("peerName", contact.name)
            putString("peerId", contact.meshId)
        }
        findNavController().navigate(R.id.action_contacts_to_chat, bundle)
    }

    private fun showAddManualDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val etName   = dialogView.findViewById<EditText>(R.id.et_contact_name)
        val etMeshId = dialogView.findViewById<EditText>(R.id.et_mesh_id)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Ajouter un contact")
            .setView(dialogView)
            .setPositiveButton("Ajouter") { _, _ ->
                val name   = etName.text.toString().trim()
                val meshId = etMeshId.text.toString().trim().uppercase()
                if (name.isNotEmpty() && meshId.startsWith("FM#")) {
                    viewModel.addContactManually(name, meshId)
                } else {
                    Toast.makeText(requireContext(),
                        "Identifiant invalide (format: FM#XXXX-XXXX)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─────────────────────────────────────────
// CONTACTS VIEW MODEL
// ─────────────────────────────────────────
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val db: FMcallDatabase,
    private val meshManager: MeshNetworkManager
) : ViewModel() {

    sealed class ContactItem {
        data class Saved(val contact: Contact, val isOnline: Boolean) : ContactItem()
        data class MeshNode(val node: com.fmcall.serval.data.model.MeshNode) : ContactItem()
    }

    private val _tab = MutableStateFlow(0)         // 0=contacts, 1=nœuds
    private val _search = MutableStateFlow("")

    val displayList: StateFlow<List<ContactItem>> = combine(
        _tab, _search,
        db.contactDao().observeAll(),
        meshManager.connectedNodes
    ) { tab, query, contacts, nodes ->

        val onlineMeshIds = nodes.map { it.meshId }.toSet()

        when (tab) {
            0 -> {
                // Contacts sauvegardés
                contacts
                    .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.meshId.contains(query) }
                    .sortedWith(compareByDescending<Contact> { it.meshId in onlineMeshIds }.thenBy { it.name })
                    .map { ContactItem.Saved(it, it.meshId in onlineMeshIds) }
            }
            else -> {
                // Nœuds Mesh détectés (non encore en contacts)
                val savedMeshIds = contacts.map { it.meshId }.toSet()
                nodes
                    .filter { it.meshId !in savedMeshIds }
                    .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
                    .map { ContactItem.MeshNode(it) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTab(tab: Int) { _tab.value = tab }
    fun search(q: String) { _search.value = q }

    fun addContactFromNode(node: com.fmcall.serval.data.model.MeshNode) {
        viewModelScope.launch {
            val contact = Contact(
                meshId    = node.meshId,
                name      = node.displayName,
                publicKey = node.publicKey,
                isFavorite = false
            )
            db.contactDao().upsert(contact)
        }
    }

    fun addContactManually(name: String, meshId: String) {
        viewModelScope.launch {
            val contact = Contact(
                meshId    = meshId,
                name      = name,
                publicKey = ""   // Clé récupérée lors de la première connexion
            )
            db.contactDao().upsert(contact)
        }
    }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            db.contactDao().upsert(contact.copy(isFavorite = !contact.isFavorite))
        }
    }
}

// ─────────────────────────────────────────
// CONTACTS ADAPTER
// ─────────────────────────────────────────
class ContactAdapter(
    private val onCall:        (Contact) -> Unit,
    private val onVideoCall:   (Contact) -> Unit,
    private val onMessage:     (Contact) -> Unit,
    private val onAddFromMesh: (MeshNode) -> Unit
) : ListAdapter<ContactsViewModel.ContactItem,
        RecyclerView.ViewHolder>(ContactDiffCallback()) {

    companion object {
        private const val TYPE_SAVED = 0
        private const val TYPE_NODE  = 1
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ContactsViewModel.ContactItem.Saved    -> TYPE_SAVED
        is ContactsViewModel.ContactItem.MeshNode -> TYPE_NODE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SAVED -> SavedContactVH(
                ItemContactBinding.inflate(inflater, parent, false))
            else -> NodeContactVH(
                ItemMeshNodeBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ContactsViewModel.ContactItem.Saved    -> (holder as SavedContactVH).bind(item)
            is ContactsViewModel.ContactItem.MeshNode -> (holder as NodeContactVH).bind(item)
        }
    }

    inner class SavedContactVH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ContactsViewModel.ContactItem.Saved) {
            val contact = item.contact
            b.tvName.text    = contact.name
            b.tvMeshId.text  = contact.meshId
            b.tvInitials.text = contact.name.take(2).uppercase()

            // Online indicator
            b.onlineDot.visibility = if (item.isOnline) View.VISIBLE else View.GONE
            b.tvStatus.text = if (item.isOnline) "🟢 En ligne" else "⚫ Hors ligne"
            b.tvStatus.setTextColor(
                if (item.isOnline) 0xFF00C853.toInt() else 0xFF888888.toInt()
            )

            b.btnCall.setOnClickListener      { onCall(contact) }
            b.btnVideoCall.setOnClickListener { onVideoCall(contact) }
            b.btnMessage.setOnClickListener   { onMessage(contact) }

            // Favorite star
            b.btnFavorite.setImageResource(
                if (contact.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )
        }
    }

    inner class NodeContactVH(val b: ItemMeshNodeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: ContactsViewModel.ContactItem.MeshNode) {
            val node = item.node
            b.tvNodeName.text  = node.displayName
            b.tvNodeMeshId.text = node.meshId
            b.tvNodeMeta.text  = "${node.transportType.name} • ${node.hopCount} saut(s) • ${node.rssi} dBm"
            b.btnAddContact.setOnClickListener { onAddFromMesh(node) }
        }
    }
}

class ContactDiffCallback : DiffUtil.ItemCallback<ContactsViewModel.ContactItem>() {
    override fun areItemsTheSame(a: ContactsViewModel.ContactItem, b: ContactsViewModel.ContactItem): Boolean {
        return when {
            a is ContactsViewModel.ContactItem.Saved && b is ContactsViewModel.ContactItem.Saved ->
                a.contact.contactId == b.contact.contactId
            a is ContactsViewModel.ContactItem.MeshNode && b is ContactsViewModel.ContactItem.MeshNode ->
                a.node.nodeId == b.node.nodeId
            else -> false
        }
    }
    override fun areContentsTheSame(a: ContactsViewModel.ContactItem, b: ContactsViewModel.ContactItem) = a == b
}
