package works.resolve.pathfinder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.slf4j.LoggerFactory

/** Keeps the process in the foreground-service state for the browser leg of an interactive login. */
class LoginForegroundService : Service() {
    private val log = LoggerFactory.getLogger(LoginForegroundService::class.java)

    override fun onCreate() {
        super.onCreate()
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.login_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.login_notification_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        log.info("Login foreground service started")
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        log.warn("Login foreground service timed out")
        stopSelf()
    }

    override fun onDestroy() {
        log.info("Login foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "login"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LoginForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoginForegroundService::class.java))
        }
    }
}

/** Starts and stops [LoginForegroundService] around the external leg of an OAuth login. */
fun interface LoginProcessGuard {
    fun setActive(active: Boolean)
}
