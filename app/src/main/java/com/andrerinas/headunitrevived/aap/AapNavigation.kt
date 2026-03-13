package com.andrerinas.headunitrevived.aap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.NavigationStatus
import com.andrerinas.headunitrevived.contract.NavigationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    private var lastTurnDetail: NavigationStatus.NextTurnDetail? = null
    private var currentStreet: String = ""

    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).build()
                    lastTurnDetail = detail
                    currentStreet = detail.road.takeIf { it.isNotBlank() } ?: ""
                    AppLog.d("Nav: NextTurnDetail road=${detail.road} nextturn=${detail.nextturn}")
                    sendNavigationBroadcast(distanceMeters = null, timeSeconds = null, detail = detail)
                    if (settings.showNavigationNotifications) {
                        val actionText = nextEventToAction(detail.nextturn)
                        val street = currentStreet.ifBlank { detail.road.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                        showNotification(distanceMeters = null, action = actionText, street = street)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).build()
                    val distanceMeters = if (event.hasDistance()) event.distance else null
                    val timeSeconds = if (event.hasTime()) event.time else null
                    val detail = lastTurnDetail
                    val actionText = detail?.let { nextEventToAction(it.nextturn) } ?: context.getString(R.string.nav_action_unknown)
                    val street = currentStreet.ifBlank { detail?.road?.takeIf { r -> r.isNotBlank() } ?: "" }.ifBlank { "—" }
                    sendNavigationBroadcast(distanceMeters = distanceMeters, timeSeconds = timeSeconds, detail = detail)
                    if (settings.showNavigationNotifications) {
                        showNotification(
                            distanceMeters = distanceMeters,
                            action = actionText,
                            street = street
                        )
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            else -> {
                AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun sendNavigationBroadcast(
        distanceMeters: Int?,
        timeSeconds: Int?,
        detail: NavigationStatus.NextTurnDetail?
    ) {
        val road = (detail?.road?.takeIf { it.isNotBlank() } ?: currentStreet).ifBlank { "—" }
        val nextEventType = detail?.nextturn?.number ?: 0
        val turnSide = detail?.side?.number
        val turnNumber = detail?.takeIf { it.hasTrunnumer() }?.trunnumer
        val turnAngle = detail?.takeIf { it.hasTurnangel() }?.turnangel
        val actionText = detail?.let { nextEventToAction(it.nextturn) } ?: context.getString(R.string.nav_action_unknown)
        val intent = NavigationUpdateIntent(
            distanceMeters = distanceMeters,
            timeSeconds = timeSeconds,
            road = road,
            nextEventType = nextEventType,
            actionText = actionText,
            turnSide = turnSide,
            turnNumber = turnNumber,
            turnAngle = turnAngle
        )
        context.applicationContext.sendBroadcast(intent)
    }

    private fun showNotification(distanceMeters: Int?, action: String, street: String) {
        val appContext = context.applicationContext
        val title = if (distanceMeters != null && distanceMeters >= 0) {
            context.getString(R.string.nav_notification_title_format, distanceMeters, action)
        } else {
            action
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(appContext, NAV_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.nav_notification_street_format, street))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    AapProjectionActivity.intent(appContext),
                    pendingIntentFlags
                )
            )
            .build()
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NAV_NOTIFICATION_ID, notification)
    }

    private fun nextEventToAction(nextEvent: NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (nextEvent) {
            NavigationStatus.NextTurnDetail.NextEvent.UNKNOWN -> context.getString(R.string.nav_action_unknown)
            NavigationStatus.NextTurnDetail.NextEvent.DEPARTE -> context.getString(R.string.nav_action_depart)
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> context.getString(R.string.nav_action_name_change)
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> context.getString(R.string.nav_action_slight_turn)
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> context.getString(R.string.nav_action_turn)
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> context.getString(R.string.nav_action_sharp_turn)
            NavigationStatus.NextTurnDetail.NextEvent.UTURN -> context.getString(R.string.nav_action_uturn)
            NavigationStatus.NextTurnDetail.NextEvent.ONRAMPE -> context.getString(R.string.nav_action_on_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> context.getString(R.string.nav_action_off_ramp)
            NavigationStatus.NextTurnDetail.NextEvent.FORME -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> context.getString(R.string.nav_action_merge)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> context.getString(R.string.nav_action_roundabout_enter)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> context.getString(R.string.nav_action_roundabout_exit)
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> context.getString(R.string.nav_action_roundabout)
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHTE -> context.getString(R.string.nav_action_straight)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> context.getString(R.string.nav_action_ferry)
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAINE -> context.getString(R.string.nav_action_ferry_train)
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> context.getString(R.string.nav_action_destination)
        }
    }

    companion object {
        const val NAV_CHANNEL_ID = "headunit_navigation"
        private const val NAV_NOTIFICATION_ID = 2

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAV_CHANNEL_ID,
                    context.getString(R.string.nav_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nav_notification_channel_description)
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }
    }
}
