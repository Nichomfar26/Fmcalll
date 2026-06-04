package com.fmcall.serval

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class FMcallApplication : Application() {

    companion object {
        const val CHANNEL_MESH_SERVICE   = "fmcall_mesh_service"
        const val CHANNEL_INCOMING_CALL  = "fmcall_incoming_call"
        const val CHANNEL_MESSAGES       = "fmcall_messages"
        const val CHANNEL_FILE_TRANSFER  = "fmcall_file_transfer"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_MESH_SERVICE,
                "Service Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintien du réseau Mesh actif"
                setShowBadge(false)
            })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "Appels entrants",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications d'appels Mesh entrants"
                enableVibration(true)
                setShowBadge(true)
            })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Nouveaux messages chiffrés"
                setShowBadge(true)
            })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_FILE_TRANSFER,
                "Transferts de fichiers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Envoi et réception de fichiers"
                setShowBadge(false)
            })
        }
    }
}
