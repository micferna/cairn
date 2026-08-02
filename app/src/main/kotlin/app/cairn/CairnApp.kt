package app.cairn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CairnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // minSdk 26 : les canaux de notification existent toujours, pas de garde.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "cairn_tracking"
    }
}
