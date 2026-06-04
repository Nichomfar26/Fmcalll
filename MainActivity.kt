package com.fmcall.serval.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.fmcall.serval.R
import com.fmcall.serval.databinding.ActivityMainBinding
import com.fmcall.serval.mesh.MeshStatus
import com.fmcall.serval.service.MeshService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var navController: NavController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startMeshService()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        observeMeshStatus()
        requestPermissionsIfNeeded()
    }

    private fun setupNavigation() {
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun observeMeshStatus() {
        lifecycleScope.launch {
            viewModel.meshStatus.collectLatest { status ->
                updateMeshIndicator(status)
            }
        }

        lifecycleScope.launch {
            viewModel.nodeCount.collectLatest { count ->
                binding.tvNodeCount.text = "$count nœud${if (count > 1) "s" else ""}"
            }
        }
    }

    private fun updateMeshIndicator(status: MeshStatus) {
        when (status) {
            MeshStatus.ONLINE  -> {
                binding.meshStatusDot.setBackgroundResource(R.drawable.dot_green)
                binding.tvMeshStatus.text = getString(R.string.mesh_online)
            }
            MeshStatus.STARTING -> {
                binding.meshStatusDot.setBackgroundResource(R.drawable.dot_yellow)
                binding.tvMeshStatus.text = getString(R.string.mesh_starting)
            }
            MeshStatus.OFFLINE -> {
                binding.meshStatusDot.setBackgroundResource(R.drawable.dot_red)
                binding.tvMeshStatus.text = getString(R.string.mesh_offline)
            }
            MeshStatus.ERROR -> {
                binding.meshStatusDot.setBackgroundResource(R.drawable.dot_red)
                binding.tvMeshStatus.text = getString(R.string.mesh_error)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
            required += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
            required += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) startMeshService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java).apply {
            action = MeshService.ACTION_START
        }
        startForegroundService(intent)
        viewModel.initializeMesh()
    }

    private fun showPermissionDeniedDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.permissions_required)
            .setMessage(R.string.permissions_message)
            .setPositiveButton(R.string.retry) { _, _ -> requestPermissionsIfNeeded() }
            .setNegativeButton(R.string.quit) { _, _ -> finish() }
            .show()
    }
}
