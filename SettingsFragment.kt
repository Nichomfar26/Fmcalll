package com.fmcall.serval.ui.settings

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.fmcall.serval.databinding.FragmentSettingsBinding
import com.fmcall.serval.ui.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.userProfile.collectLatest { profile ->
                profile?.let {
                    binding.tvProfileName.text = it.displayName
                    binding.tvProfileMeshId.text = it.meshId
                    binding.tvChannelValue.text = "Canal ${it.meshChannel}"
                    binding.switchWifi.isChecked = it.wifiMeshEnabled
                    binding.switchBluetooth.isChecked = it.bluetoothMeshEnabled
                    binding.switchRelay.isChecked = it.relayModeEnabled
                }
            }
        }

        binding.btnEditProfile.setOnClickListener { showEditProfileDialog() }

        binding.switchWifi.setOnCheckedChangeListener { _, checked ->
            // Update mesh config
        }
        binding.switchBluetooth.setOnCheckedChangeListener { _, checked ->
            // Update mesh config
        }
        binding.switchRelay.setOnCheckedChangeListener { _, checked ->
            // Update mesh config
        }

        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowChannel.setOnClickListener { showChannelDialog() }
        binding.rowLogs.setOnClickListener { showConnectionLogs() }
        binding.rowKeys.setOnClickListener { showKeyManagement() }
    }

    private fun showEditProfileDialog() {
        val dialog = android.app.AlertDialog.Builder(requireContext())
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Nom d'affichage"
            setText(viewModel.userProfile.value?.displayName ?: "")
        }
        dialog.setTitle("Modifier le profil")
            .setView(input)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.updateProfile(name, viewModel.userProfile.value?.meshChannel ?: 6)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("Français", "English")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Langue")
            .setItems(languages) { _, which ->
                // Save language preference
            }
            .show()
    }

    private fun showChannelDialog() {
        val channels = (1..11).map { "Canal $it" }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Canal Mesh")
            .setItems(channels) { _, which ->
                val channel = which + 1
                viewModel.updateProfile(
                    viewModel.userProfile.value?.displayName ?: "",
                    channel
                )
                binding.tvChannelValue.text = "Canal $channel"
            }
            .show()
    }

    private fun showConnectionLogs() {
        // Navigate to logs screen
    }

    private fun showKeyManagement() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Clés cryptographiques")
            .setMessage("Clé publique:\n${viewModel.userProfile.value?.publicKey?.take(32)}...\n\nChiffrement: Ed25519 + AES-256-GCM")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
