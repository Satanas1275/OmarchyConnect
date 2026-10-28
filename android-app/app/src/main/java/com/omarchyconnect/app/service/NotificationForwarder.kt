package com.omarchyconnect.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.omarchyconnect.app.core.Link
import com.omarchyconnect.app.core.Prefs

/**
 * Capture les notifications du téléphone pour les relayer vers le PC.
 *
 * Nécessite l'autorisation "Accès aux notifications" (réglages système).
 * Le service ne conserve aucune notification : chaque événement est émis
 * puis oublié.
 */
class NotificationForwarder : NotificationListenerService() {

    override fun onListenerConnected() {
        Link.log("ok", "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!Prefs.forwardNotifications) return
        if (sbn.isOngoing) return
        if (sbn.packageName == packageName) return
        if (sbn.notification == null) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        if (title.isBlank()) return
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Throwable) {
            sbn.packageName
        }

        Link.pushNotification(sbn.packageName, app, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Pas de désémission côté PC pour l'instant (le PC expire de toute façon).
    }
}
