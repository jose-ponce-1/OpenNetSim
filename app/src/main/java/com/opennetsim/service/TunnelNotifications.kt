package com.opennetsim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.opennetsim.R
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.ui.MainActivity
import com.opennetsim.ui.PresetTexts

/**
 * Canal y notificación persistente del servicio en primer plano, en el idioma activo de la app. Volver a
 * crear el canal con el mismo id solo actualiza su nombre y descripción.
 */
class TunnelNotifications(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    /** Actualiza la notificación visible con [config]. */
    fun show(config: ThrottleConfig) {
        manager?.notify(NOTIFICATION_ID, build(config))
    }

    fun build(config: ThrottleConfig): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            TunnelIntents.stop(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = context.getString(
            R.string.notification_text,
            context.getString(PresetTexts.nameRes(config.preset)),
            config.formattedDownBandwidth(),
            config.formattedUpBandwidth(),
            config.latencyMs
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_opennetsim)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "open_net_sim_vpn_channel"
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1
    }
}
