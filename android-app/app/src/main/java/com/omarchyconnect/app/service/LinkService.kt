package com.omarchyconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omarchyconnect.app.R
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.LinkStatus
import com.omarchyconnect.app.core.Prefs

/**
 * Service de premier plan :
 *  - maintient la connexion WebSocket en vie (reconnexion automatique)
 *  - observe le presse-papiers en temps réel et le pousse vers le PC
 *
 * Il ne stocke rien : tout passe par [Link] / [Prefs] (Keystore).
 */
class LinkService : Service() {

    companion object {
        const val CHANNEL_ID = "omarchy_link"
        const val NOTIF_ID = 4201

        fun start(context: Context) {
            val i = Intent(context, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }

    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(applicationContext)
            ?.toString()
            ?: return@OnPrimaryClipChangedListener
        Link.sendClipboard(text)
    }

    override fun onCreate() {
        super.onCreate()
        Link.init(this)

        ensureChannel()
        startForegroundCompat(buildNotification())
        registerClipboard()

        if (Prefs.paired && !Link.demo.value) Link.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // met à jour la notification (état de la connexion)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val status = Link.status.value
        val text = when {
            Link.demo.value -> "Demo mode"
            status == LinkStatus.ONLINE -> "Linked to ${Link.pcName.value.ifBlank { Link.domain.value }}"
            status == LinkStatus.PAIRING -> "Pairing…"
            status == LinkStatus.CONNECTING -> "Connecting to ${Link.domain.value}…"
            status == LinkStatus.ERROR -> "Link error"
            else -> "Link idle"
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("OmarchyConnect")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_link),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_link_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerClipboard() {
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
