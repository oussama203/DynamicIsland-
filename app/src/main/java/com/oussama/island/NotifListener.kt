package com.oussama.island

import android.app.Notification
import android.graphics.Color
import android.graphics.PorterDuff
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandService.instance?.setupMedia()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val svc = IslandService.instance ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val isCall = n.category == Notification.CATEGORY_CALL
        val skip = (n.flags and Notification.FLAG_ONGOING_EVENT != 0) ||
                (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) ||
                n.category == Notification.CATEGORY_TRANSPORT
        if (!isCall && skip) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val icon = try {
            n.smallIcon?.loadDrawable(this)?.mutate()?.apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        } catch (e: Exception) { null }

        if (isCall) svc.showCall(sbn.key, title, text, n.actions?.toList() ?: emptyList())
        else svc.showNotification(title, text, icon, n.contentIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandService.instance?.callEnded(sbn.key)
    }
}
