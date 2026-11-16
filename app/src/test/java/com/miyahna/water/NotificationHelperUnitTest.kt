package com.miyahna.water

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.miyahna.water.data.MiyahnaDomainState
import com.miyahna.water.data.notifications.MiyahnaNotificationHelper
import com.miyahna.water.domain.AppNotification
import com.miyahna.water.domain.AppRole
import com.miyahna.water.domain.DirectiveStatus
import com.miyahna.water.domain.DriverSession
import com.miyahna.water.domain.ManagerDirective
import com.miyahna.water.domain.NotificationPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperUnitTest {

    @Test
    fun selectNotificationsFiltersByActiveRoleReadStateAndDeduplication() {
        val nowIso = Instant.now().toString()
        val notifications = listOf(
            AppNotification(
                id = "n-driver-unread",
                createdAt = nowIso,
                targetRole = "driver",
                actorRole = "manager",
                actorName = "المدير",
                category = "trip_approved",
                priority = NotificationPriority.ACTION_REQUIRED,
                title = "رحلة جديدة",
                message = "محطة العرقين"
            ),
            AppNotification(
                id = "n-driver-read",
                createdAt = nowIso,
                targetRole = "driver",
                actorRole = "manager",
                actorName = "المدير",
                category = "trip_approved",
                priority = NotificationPriority.INFO,
                title = "مقروءة",
                message = "تمت قراءتها",
                isRead = true
            ),
            AppNotification(
                id = "n-driver-already-seen",
                createdAt = nowIso,
                targetRole = "driver",
                actorRole = "manager",
                actorName = "المدير",
                category = "directive",
                priority = NotificationPriority.URGENT,
                title = "تنبيه سابق",
                message = "سبق إشعار الجهاز"
            ),
            AppNotification(
                id = "n-manager-unread",
                createdAt = nowIso,
                targetRole = "manager",
                actorRole = "driver",
                actorName = "السائق",
                category = "order_added",
                priority = NotificationPriority.INFO,
                title = "طلب ميداني",
                message = "بانتظار الإدارة"
            )
        )

        val selectedForDriver = MiyahnaNotificationHelper.selectNotificationsToAlert(
            notifications = notifications,
            directives = emptyList(),
            activeRoleWire = AppRole.DRIVER.wire,
            alreadyNotifiedIds = setOf("n-driver-already-seen")
        )
        assertEquals(listOf("n-driver-unread"), selectedForDriver.map { it.id })

        val selectedForManager = MiyahnaNotificationHelper.selectNotificationsToAlert(
            notifications = notifications,
            directives = emptyList(),
            activeRoleWire = AppRole.MANAGER.wire,
            alreadyNotifiedIds = emptySet()
        )
        assertEquals(listOf("n-manager-unread"), selectedForManager.map { it.id })
    }

    @Test
    fun selectNotificationsSynthesizesAlertForUncoveredSentDirective() {
        val nowIso = Instant.now().toString()
        val directive = ManagerDirective(
            id = "dir-99",
            createdAt = nowIso,
            createdBy = "المدير العام",
            title = "التوجه لمحطة السد",
            message = "أولوية قصوى للموقع",
            priority = "urgent",
            status = DirectiveStatus.SENT
        )

        val alerts = MiyahnaNotificationHelper.selectNotificationsToAlert(
            notifications = emptyList(),
            directives = listOf(directive),
            activeRoleWire = AppRole.DRIVER.wire,
            alreadyNotifiedIds = emptySet()
        )
        assertEquals(1, alerts.size)
        assertEquals("dir-alert-dir-99", alerts.first().id)
        assertEquals("توجيه إداري: التوجه لمحطة السد", alerts.first().title)
    }

    @Test
    fun resolveActiveRoleWireRequiresActiveDriverSession() {
        val driverWithoutSession = MiyahnaDomainState(isLoaded = true, savedRole = AppRole.DRIVER, driverSession = null)
        assertNull(MiyahnaNotificationHelper.resolveActiveRoleWire(driverWithoutSession))

        val driverWithSession = driverWithoutSession.copy(
            driverSession = DriverSession(id = "d1", name = "أحمد", username = "ahmed", phone = "777000111", loggedInAt = "2026-03-01T08:00:00Z")
        )
        assertEquals("driver", MiyahnaNotificationHelper.resolveActiveRoleWire(driverWithSession))
    }

    @Test
    fun helperCreatesHighImportanceChannelPostsNotificationOnceWhenPermitted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val helper = MiyahnaNotificationHelper(app)
        val nowIso = Instant.now().toString()

        val state = MiyahnaDomainState(
            isLoaded = true,
            savedRole = AppRole.MANAGER,
            notifications = listOf(
                AppNotification(
                    id = "notif-bg-1",
                    createdAt = nowIso,
                    targetRole = "manager",
                    actorRole = "driver",
                    actorName = "سالم",
                    category = "trip_completed_driver",
                    priority = NotificationPriority.ACTION_REQUIRED,
                    title = "أنهى السائق الرحلة",
                    message = "تم التسليم بنجاح"
                )
            )
        )

        helper.notifyNewUnreadNotifications(state)

        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(MiyahnaNotificationHelper.ALERTS_CHANNEL_ID)
        assertTrue(channel != null)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)

        val shadowNm = shadowOf(nm)
        assertEquals(1, shadowNm.allNotifications.size)

        // Calling again with same state must NOT duplicate the notification
        helper.notifyNewUnreadNotifications(state)
        assertEquals(1, shadowNm.allNotifications.size)
    }
}
