package com.fmcall.serval.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fmcall.serval.data.model.UserProfile
import com.fmcall.serval.mesh.MeshNetworkManager
import com.fmcall.serval.mesh.MeshStatus
import com.fmcall.serval.security.CryptoManager
import com.fmcall.serval.utils.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val meshManager: MeshNetworkManager,
    private val crypto: CryptoManager,
    private val prefs: PreferencesManager
) : ViewModel() {

    val meshStatus: StateFlow<MeshStatus> = meshManager.meshStatus

    val nodeCount: StateFlow<Int> = meshManager.connectedNodes
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val connectedNodes = meshManager.connectedNodes

    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    fun initializeMesh() {
        viewModelScope.launch {
            val profile = loadOrCreateProfile()
            _userProfile.value = profile
            meshManager.start(profile)
        }
    }

    private suspend fun loadOrCreateProfile(): UserProfile {
        val saved = prefs.getUserProfile()
        if (saved != null) return saved

        // First run: generate identity
        val keyPair = crypto.generateIdentityKeyPair()
        val meshId  = crypto.generateMeshId()
        val profile = UserProfile(
            meshId      = meshId,
            displayName = "Utilisateur FMcall",
            privateKey  = crypto.encryptPrivateKey(keyPair.privateKey),
            publicKey   = keyPair.publicKeyBase64,
            meshChannel = 6
        )
        prefs.saveUserProfile(profile)
        return profile
    }

    fun updateProfile(name: String, channel: Int) {
        viewModelScope.launch {
            val current = _userProfile.value ?: return@launch
            val updated = current.copy(displayName = name, meshChannel = channel)
            prefs.saveUserProfile(updated)
            _userProfile.value = updated
        }
    }
}
