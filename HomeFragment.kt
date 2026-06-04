package com.fmcall.serval.ui.main

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fmcall.serval.R
import com.fmcall.serval.databinding.FragmentHomeBinding
import com.fmcall.serval.mesh.MeshStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ─────────────────────────────────────────
// HOME FRAGMENT
// ─────────────────────────────────────────
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeData()
    }

    private fun setupClickListeners() {
        binding.btnCalls.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_calls)
        }
        binding.btnMessages.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_messages)
        }
        binding.btnNetwork.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_nodes)
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
        binding.btnScan.setOnClickListener {
            startMeshScan()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.meshStatus.collectLatest { status ->
                binding.tvMeshStatus.text = when (status) {
                    MeshStatus.ONLINE   -> getString(R.string.mesh_online)
                    MeshStatus.STARTING -> getString(R.string.mesh_starting)
                    MeshStatus.OFFLINE  -> getString(R.string.mesh_offline)
                    MeshStatus.ERROR    -> getString(R.string.mesh_error)
                }
                binding.meshStatusIndicator.isActivated = status == MeshStatus.ONLINE
            }
        }

        lifecycleScope.launch {
            viewModel.nodeCount.collectLatest { count ->
                binding.tvNodeCount.text = "$count"
                binding.tvNodeLabel.text = if (count > 1)
                    getString(R.string.nodes_connected_plural) else getString(R.string.nodes_connected)
            }
        }

        lifecycleScope.launch {
            viewModel.userProfile.collectLatest { profile ->
                profile?.let {
                    binding.tvUserId.text = it.meshId
                    binding.tvUserName.text = it.displayName
                }
            }
        }
    }

    private fun startMeshScan() {
        binding.scanProgress.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false

        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            binding.scanProgress.visibility = View.GONE
            binding.btnScan.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
