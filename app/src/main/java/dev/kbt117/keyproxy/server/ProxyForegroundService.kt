package dev.kbt117.keyproxy.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kbt117.keyproxy.MainActivity
import dev.kbt117.keyproxy.R
import dev.kbt117.keyproxy.di.ApplicationScope
import dev.kbt117.keyproxy.domain.model.ProxySettings
import dev.kbt117.keyproxy.domain.model.ServerState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [ProxyServer] alive while the UI is backgrounded.
 *
 * ## Why a foreground service
 *
 * A listening socket in a plain `Service` is reclaimed as soon as Android needs
 * memory, which for a proxy shows up as "worked for a minute, then connection
 * refused". A foreground service with a visible notification is the supported
 * way to opt out of that.
 *
 * ## Why `specialUse`
 *
 * API 34+ requires a declared `foregroundServiceType` plus the matching
 * `FOREGROUND_SERVICE_SPECIAL_USE` permission. None of the standard types fit:
 * `dataSync` is capped at ~6 h/day from API 35, and this is not media playback,
 * navigation, or a connected peripheral. `specialUse` plus the
 * `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification in the manifest is the
 * correct declaration, and it is what the Play Console asks you to explain.
 *
 * ## Android 16
 *
 * Android 16 tightens background-work quotas, but a user-visible foreground
 * service that the user explicitly started and can stop from the notification
 * remains the sanctioned pattern. The Stop action below is what makes it
 * "user-controllable" in the sense the platform checks for.
 */
@AndroidEntryPoint
class ProxyForegroundService : Service() {

    @Inject lateinit var proxyServer: ProxyServer

    @Inject lateinit var serverToggle: ServerToggle

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /** Not a bound service - the proxy is reached over HTTP, not over a Binder. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serverToggle.userWantsRunning = false
            appScope.launch { proxyServer.stop() }
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent means Android restarted us after a kill. Honour the
        // user's last explicit choice rather than resurrecting a proxy they
        // had switched off.
        if (intent == null && !serverToggle.userWantsRunning) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen synchronously and immediately: Android ANRs a service that
        // was started with startForegroundService() and does not promote itself
        // within a few seconds.
        promoteToForeground(placeholderEndpoint())

        serverToggle.userWantsRunning = true
        appScope.launch {
            val state = proxyServer.start()
            if (state is ServerState.Running) {
                updateNotification(endpointFor(state.port))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Belt and braces: if the service is torn down for any reason, do not
        // leave a socket bound that nothing owns.
        appScope.launch { proxyServer.stop() }
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    private fun promoteToForeground(endpoint: String) {
        val notification = buildNotification(endpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires the type to be passed explicitly, or declared in
            // the manifest. We do both; passing it here is what the platform
            // validates first.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(endpoint: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(endpoint))
    }

    private fun buildNotification(endpoint: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // getService (not getForegroundService) because this only ever *stops*
        // an already-running service, which is allowed from the background.
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, endpoint))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop_action), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            // A low-importance channel: visible in the shade, never intrusive.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Endpoint text used for the very first notification frame.
     *
     * The real port is only known once the engine has bound, so the initial
     * notification is built from the configured default and replaced by
     * [updateNotification] as soon as [ProxyServer.start] returns.
     */
    private fun placeholderEndpoint(): String =
        endpointFor(ProxySettings.DEFAULT_PORT)

    private fun endpointFor(port: Int): String =
        "http://${ProxyServer.HOST}:$port${ProxyServer.DEFAULT_PATH}"

    companion object {
        const val ACTION_STOP = "dev.kbt117.keyproxy.action.STOP"

        private const val CHANNEL_ID = "proxy_server"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1

        fun startIntent(context: Context): Intent =
            Intent(context, ProxyForegroundService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxyForegroundService::class.java).setAction(ACTION_STOP)

        /**
         * `true` when notifications are enabled for this app.
         *
         * The brief forbids dangerous permissions, so `POST_NOTIFICATIONS` is
         * not requested. On API 33+ that means the foreground notification stays
         * hidden until the user turns it on in Settings - the proxy still runs,
         * but the user loses the visible indicator and the Stop shortcut, so the
         * Dashboard surfaces a hint when this returns `false`.
         */
        fun areNotificationsVisible(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
