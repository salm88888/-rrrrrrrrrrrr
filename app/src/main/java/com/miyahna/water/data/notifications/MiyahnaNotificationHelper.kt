package com.miyahna.water.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.miyahna.water.MainActivity
import com.miyahna.water.R
import com.miyahna.water.data.MiyahnaDomainState
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.NotificationPriority
import java.time.Instant

/**
 * Posts real Android OS notifications on a HIGH importance channel for unread
 * operational notifications and manager directives, with persistent deduplication
 * so alerts appear in the system tray even when the user exits the app.
 */
class MiyahnaNotificationHelper(private val context: Context) {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun ensureChannelExists() {
        ensureChannel(context)
    }

    fun markNotificationsSeen(ids: Iterable<String>) {
        val current = getNotifiedIdsLocked().toMutableList()
        var changed = false
        for (id in ids) {
            if (id.isNotBlank() && id !in current) {
                current.add(id)
                changed = true
            }
        }
        if (changed) {
            saveNotifiedIdsLocked(current)
        }
    }

    fun cancelNotification(notificationId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId.hashCode())
        }
    }

    fun cancelAllOperationalNotifications() {
        runCatching {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    fun notifyNewUnreadNotifications(state: MiyahnaDomainState) {
        if (!state.isLoaded) return
        ensureChannelExists()

        val activeRoleWire = resolveActiveRoleWire(state) ?: return
        val seenIds = getNotifiedIdsLocked().toHashSet()
        val nowMs = System.currentTimeMillis()

        val candidates = selectNotificationsToAlert(
            notifications = state.notifications,
            directives = state.directives,
            activeRoleWire = activeRoleWire,
            alreadyNotifiedIds = seenIds,
            nowEpochMs = nowMs
        )
        if (candidates.isEmpty()) return

        if (!hasPostNotificationPermission()) {
            return
        }

        val newlyPostedIds = mutableListOf<String>()
        for (notif in candidates) {
            val posted = postSystemNotification(notif)
            if (posted) {
                newlyPostedIds.add(notif.id)
            }
        }
        if (newlyPostedIds.isNotEmpty()) {
            markNotificationsSeen(newlyPostedIds)
        }
    }

    private fun hasPostNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun postSystemNotification(notif: AppNotification): Boolean {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
            putExtra(EXTRA_TARGET_ROLE, notif.targetRole)
            notif.actionTab?.let { putExtra(EXTRA_ACTION_TAB, it) }
        }
        val requestCode = notif.id.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priorityCompat = when (notif.priority) {
            NotificationPriority.URGENT,
            NotificationPriority.ACTION_REQUIRED -> NotificationCompat.PRIORITY_HIGH
            NotificationPriority.INFO -> NotificationCompat.PRIORITY_DEFAULT
        }

        val builder = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notif.title)
            .setContentText(notif.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notif.message))
            .setPriority(priorityCompat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)

        return runCatching {
            NotificationManagerCompat.from(context).notify(requestCode, builder.build())
            true
        }.getOrDefault(false)
    }

    private fun getNotifiedIdsLocked(): List<String> {
        val raw = prefs.getString(KEY_NOTIFIED_IDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun saveNotifiedIdsLocked(ids: List<String>) {
        val trimmed = ids.distinct().takeLast(MAX_STORED_NOTIFIED_IDS)
        prefs.edit().putString(KEY_NOTIFIED_IDS, trimmed.joinToString("\n")).apply()
    }

    companion object {
        const val ALERTS_CHANNEL_ID = "miyahna_operational_alerts"
        const val EXTRA_OPEN_NOTIFICATIONS = "extra_open_notifications"
        const val EXTRA_TARGET_ROLE = "extra_target_role"
        const val EXTRA_ACTION_TAB = "extra_action_tab"

        private const val PREFS_NAME = "miyahna_notif_prefs"
        private const val KEY_NOTIFIED_IDS = "notified_ids_fifo"
        private const val MAX_STORED_NOTIFIED_IDS = 400
        private const val MAX_ALERT_AGE_MS = 48 * 3600 * 1000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERTS_CHANNEL_ID,
                    context.getString(R.string.notification_channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_alerts_desc)
                    enableVibration(true)
                    enableLights(true)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(channel)
            }
        }

        fun resolveActiveRoleWire(state: MiyahnaDomainState): String? {
            return when (state.savedRole) {
                AppRole.DRIVER -> if (state.driverSession != null) AppRole.DRIVER.wire else null
                AppRole.MANAGER -> AppRole.MANAGER.wire
                null -> if (state.cloudUser?.isAdmin == true) AppRole.MANAGER.wire else null
            }
        }

        /**
         * Pure selection helper for deciding which unread notifications or newly
         * sent directives should fire an Android system notification.
         */
        fun selectNotificationsToAlert(
            notifications: List<AppNotification>,
            directives: List<ManagerDirective>,
            activeRoleWire: String,
            alreadyNotifiedIds: Set<String>,
            nowEpochMs: Long = System.currentTimeMillis()
        ): List<AppNotification> {
            val cutoffMs = nowEpochMs - MAX_ALERT_AGE_MS
            val unreadMatching = notifications.filter { notif ->
                !notif.isRead &&
                    notif.targetRole == activeRoleWire &&
                    notif.id !in alreadyNotifiedIds &&
                    isRecent(notif.createdAt, cutoffMs)
            }

            if (activeRoleWire != AppRole.DRIVER.wire) {
                return unreadMatching.take(5)
            }

            // Also synthesize alert if a ManagerDirective arrived directly without its notification
            val coveredDirectiveIds = notifications.mapNotNull { it.directiveId }.toHashSet()
            val fallbackDirectives = directives
                .filter { dir ->
                    dir.status == DirectiveStatus.SENT &&
                        dir.id !in coveredDirectiveIds &&
                        "dir-alert-${dir.id}" !in alreadyNotifiedIds &&
                        isRecent(dir.createdAt, cutoffMs)
                }
                .map { dir ->
                    AppNotification(
                        id = "dir-alert-${dir.id}",
                        createdAt = dir.createdAt,
                        targetRole = AppRole.DRIVER.wire,
                        actorRole = AppRole.MANAGER.wire,
                        actorName = dir.createdBy,
                        category = "directive",
                        priority = if (dir.priority == "urgent") NotificationPriority.URGENT else NotificationPriority.ACTION_REQUIRED,
                        title = "توجيه إداري: ${dir.title}",
                        message = dir.message,
                        directiveId = dir.id,
                        tripId = dir.tripId,
                        tripNumber = dir.tripNumber,
                        isRead = false,
                        actionTab = "trip"
                    )
                }

            return (unreadMatching + fallbackDirectives).take(5)
        }

        private fun isRecent(iso: String, cutoffMs: Long): Boolean {
            val epoch = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return true
            return epoch >= cutoffMs
        }
    }
}
