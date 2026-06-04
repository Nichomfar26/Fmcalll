package com.fmcall.serval.ui.nodes

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.*
import com.fmcall.serval.data.model.*
import com.fmcall.serval.databinding.*
import com.fmcall.serval.mesh.MeshNetworkManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NodesViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = NodeAdapter()
        binding.rvNodes.adapter = adapter
        binding.rvNodes.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.nodes.collectLatest {
                adapter.submitList(it)
                binding.tvNodeCount.text = "${it.size} nœud(s) actif(s)"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meshStatus.collectLatest { status ->
                binding.tvMeshStatus.text = status
            }
        }

        binding.btnScan.setOnClickListener {
            binding.progressScan.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(2500)
                binding.progressScan.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

@HiltViewModel
class NodesViewModel @Inject constructor(
    private val meshManager: MeshNetworkManager
) : ViewModel() {

    val nodes: StateFlow<List<MeshNode>> = meshManager.connectedNodes

    val meshStatus: StateFlow<String> = combine(
        meshManager.meshStatus,
        meshManager.connectedNodes
    ) { status, nodes ->
        "Statut: $status • ${nodes.size} nœuds • Wi-Fi Direct"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
}

class NodeAdapter : ListAdapter<MeshNode, NodeAdapter.VH>(
    object : DiffUtil.ItemCallback<MeshNode>() {
        override fun areItemsTheSame(a: MeshNode, b: MeshNode) = a.nodeId == b.nodeId
        override fun areContentsTheSame(a: MeshNode, b: MeshNode) = a == b
    }
) {
    inner class VH(val binding: ItemNodeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = getItem(position)
        with(holder.binding) {
            tvNodeName.text   = node.displayName
            tvNodeMeshId.text = node.meshId
            tvNodeMeta.text   = "${node.transportType.name} • ${node.rssi} dBm • ${node.hopCount} saut(s)"
            tvHopBadge.text   = "${node.hopCount} saut(s)"
            tvSignalQuality.text = when (node.signalQuality) {
                SignalQuality.EXCELLENT -> "●●●●"
                SignalQuality.GOOD      -> "●●●○"
                SignalQuality.FAIR      -> "●●○○"
                SignalQuality.WEAK      -> "●○○○"
            }
        }
    }
}
