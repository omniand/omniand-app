package dev.omniand.hub.background

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.omniand.hub.MainActivity
import dev.omniand.hub.R
import dev.omniand.hub.pairing.DeviceIdentity
import dev.omniand.hub.server.PlatformServer
import dev.omniand.hub.tunnel.RelayTunnelClient
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Persists opt-in hosting and coordinates its foreground-service lifecycle. */
object BackgroundHostingManager {
    private const val PREFS = "background-hosting"
    private const val ENABLED = "enabled"

    @Volatile private var serviceRunning = false

    fun mode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("mode", null)
            ?: if (prefs.getBoolean(ENABLED, false)) "always-on" else "disabled"
    }

    // Legacy startup callers restore only permanent hosting.
    fun isEnabled(context: Context): Boolean = mode(context) == "always-on"

    @Volatile private var temporary = false
    private val inactivity = SessionInactivityPolicy()

    fun isTemporarySession(): Boolean = temporary

    fun setMode(context: Context, mode: String) {
        require(mode in setOf("disabled", "on-demand", "always-on"))
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("mode", mode)
            .apply()
        stopSession(context)
        if (mode == "always-on") start(context)
        WakeRegistration.enqueue(context)
    }

    fun approveSession(context: Context) {
        check(mode(context) == "on-demand")
        if (temporary) return
        temporary = true
        inactivity.start(android.os.SystemClock.elapsedRealtime())
        try {
            start(context)
        } catch (error: Exception) {
            temporary = false
            throw error
        }
    }

    fun stopSession(context: Context) {
        temporary = false
        inactivity.stop()
        sessionAlarm(context, null)
        context.stopService(Intent(context, BackgroundHostingService::class.java))
    }

    /** Presence changes reset only the absence deadline; tunnel reconnects never touch it. */
    @Synchronized
    fun presenceChanged(context: Context) {
        if (!temporary) return
        inactivity.presence(
            PresenceTracker.connectedClients(),
            android.os.SystemClock.elapsedRealtime(),
        )
        sessionAlarm(context, inactivity.deadline())
    }

    fun checkInactivity(context: Context) {
        if (temporary && inactivity.expired(android.os.SystemClock.elapsedRealtime()))
            stopSession(context)
    }

    private fun sessionAlarm(context: Context, deadline: Long?) {
        val alarm = context.getSystemService(android.app.AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                context,
                7403,
                Intent(context, SessionTimeoutReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarm.cancel(pending)
        if (deadline != null)
            alarm.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadline,
                pending,
            )
    }

    fun isServiceRunning(): Boolean = serviceRunning

    fun isBatteryExempt(context: Context): Boolean =
        context
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** Applies the preference immediately; disabling also tears down every active lease. */
    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) "always-on" else "disabled")
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BackgroundHostingService::class.java),
        )
    }

    fun reconnect(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BackgroundHostingService::class.java)
                .setAction(BackgroundHostingService.ACTION_RECONNECT),
        )
    }

    fun requestAccess(context: Context) {
        context.startActivity(
            Intent(context, BackgroundHostingSetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    internal fun serviceStarted(context: Context) {
        serviceRunning = true
        presenceChanged(context)
        PresenceTracker.refreshWakeLock(context)
    }

    internal fun serviceStopped() {
        serviceRunning = false
        temporary = false
        inactivity.stop()
        PresenceTracker.releaseWakeLock()
    }
}

internal enum class BackgroundHostingPreferenceTransition {
    ENABLE,
    DISABLE,
    KEEP_ENABLED,
    KEEP_DISABLED;

    companion object {
        fun from(previous: Boolean, requested: Boolean): BackgroundHostingPreferenceTransition =
            when {
                !previous && requested -> ENABLE
                previous && !requested -> DISABLE
                requested -> KEEP_ENABLED
                else -> KEEP_DISABLED
            }
    }
}

/** Holds one bounded partial wake-lock lease while authenticated desktop streams are healthy. */
object PresenceTracker {
    private const val HEARTBEAT_MILLIS = 30_000L
    private const val LEASE_MILLIS = 90_000L
    private val subscriptions = CopyOnWriteArraySet<Subscription>()
    private val policy = PresenceLeasePolicy()
    private var wakeLock: PowerManager.WakeLock? = null

    fun subscribe(context: Context): InputStream =
        Subscription(context.applicationContext) { subscription ->
                if (subscriptions.remove(subscription)) policy.disconnected()
                refreshWakeLock(context.applicationContext)
            }
            .also {
                subscriptions.add(it)
                policy.connected()
                refreshWakeLock(context.applicationContext)
            }

    fun connectedClients(): Int = policy.connectedClients()

    @Synchronized
    fun refreshWakeLock(context: Context) {
        BackgroundHostingManager.presenceChanged(context)
        if (
            !policy.shouldHold(
                BackgroundHostingManager.mode(context) != "disabled",
                BackgroundHostingManager.isServiceRunning(),
            )
        ) {
            releaseWakeLock()
            return
        }
        val lock =
            wakeLock
                ?: context
                    .getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniAnd:desktop-presence")
                    .apply {
                        setReferenceCounted(false)
                        wakeLock = this
                    }
        lock.acquire(LEASE_MILLIS)
    }

    @Synchronized
    fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    @Synchronized fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    /** Emits an SSE heartbeat and renews the safety-bounded lease every 30 seconds. */
    private class Subscription(
        private val context: Context,
        private val onClose: (Subscription) -> Unit,
    ) : InputStream() {
        private val closed = AtomicBoolean(false)
        private var current = ": connected\nretry: 3000\n\n".toByteArray()
        private var offset = 0
        private var nextHeartbeat =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray): Int = read(target, 0, target.size)

        override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
            if (length == 0) return 0
            while (offset >= current.size) {
                if (closed.get()) return -1
                val remaining = nextHeartbeat - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                if (closed.get()) return -1
                refreshWakeLock(context)
                current = ": heartbeat\n\n".toByteArray()
                offset = 0
                nextHeartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS)
            }
            val count = minOf(length, current.size - offset)
            current.copyInto(target, targetOffset, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) onClose(this)
        }
    }
}

/** Pure reference policy used by the Android wake-lock coordinator. */
internal class PresenceLeasePolicy {
    private var connected = 0

    @Synchronized
    fun connected() {
        connected++
    }

    @Synchronized
    fun disconnected() {
        connected = (connected - 1).coerceAtLeast(0)
    }

    @Synchronized fun connectedClients(): Int = connected

    @Synchronized
    fun shouldHold(enabled: Boolean, serviceRunning: Boolean): Boolean =
        connected > 0 && enabled && serviceRunning
}

/** Keeps the phone-hosted HTTP endpoint alive after the Hub activity leaves the foreground. */
class BackgroundHostingService : Service() {
    private var serverAvailable = false
    private val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelClient: RelayTunnelClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    private fun startHosting() {
        serverAvailable = PlatformServer.start(applicationContext)
        if (serverAvailable) {
            tunnelClient =
                RelayTunnelClient(
                        applicationContext,
                        tunnelScope,
                        DeviceIdentity(applicationContext).relayUrl()
                            ?: dev.omniand.hub.BuildConfig.RELAY_URL,
                    )
                    .also {
                        it.start()
                    }
            BackgroundHostingManager.serviceStarted(applicationContext)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        tunnelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                BackgroundHostingManager.checkInactivity(applicationContext)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (BackgroundHostingManager.isTemporarySession())
                BackgroundHostingManager.stopSession(this)
            else BackgroundHostingManager.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (
            !BackgroundHostingManager.isEnabled(this) &&
                !BackgroundHostingManager.isTemporarySession()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!serverAvailable) startHosting()
        if (!serverAvailable) return START_NOT_STICKY
        if (intent?.action == ACTION_RECONNECT) {
            tunnelClient?.stop()
            tunnelClient =
                RelayTunnelClient(
                        applicationContext,
                        tunnelScope,
                        DeviceIdentity(applicationContext).relayUrl()
                            ?: dev.omniand.hub.BuildConfig.RELAY_URL,
                    )
                    .also { it.start() }
        }
        return if (BackgroundHostingManager.isTemporarySession()) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        tunnelClient?.stop()
        tunnelClient = null
        tunnelScope.cancel()
        BackgroundHostingManager.serviceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                2,
                Intent(this, BackgroundHostingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hub_foreground)
            .setContentTitle("OmniAnd server is available")
            .setContentText(
                if (BackgroundHostingManager.isTemporarySession())
                    "Remote session ends after five minutes without desktop clients."
                else "Desktop clients can connect while background hosting is enabled."
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Open Hub", open).build())
            .addAction(
                Notification.Action.Builder(
                        null,
                        if (BackgroundHostingManager.isTemporarySession()) "Stop session"
                        else "Stop hosting",
                        stop,
                    )
                    .build()
            )
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Background hosting",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    companion object {
        private const val CHANNEL = "background-hosting"
        private const val NOTIFICATION_ID = 7401
        private const val ACTION_STOP = "dev.omniand.hub.STOP_BACKGROUND_HOSTING"
        internal const val ACTION_RECONNECT = "dev.omniand.hub.RECONNECT_TUNNEL"
    }
}

/** Restores only a previously enabled opt-in after Android finishes booting. */
class BackgroundHostingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action == Intent.ACTION_BOOT_COMPLETED &&
                BackgroundHostingManager.isEnabled(context)
        ) {
            BackgroundHostingManager.start(context)
        }
    }
}

/** Requests notification permission first, then opens Android's Doze exemption prompt. */
class BackgroundHostingSetupActivity : Activity() {
    private var batteryOpened = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
        } else {
            requestBatteryAccess()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 91) requestBatteryAccess()
    }

    override fun onResume() {
        super.onResume()
        if (batteryOpened) finish()
    }

    private fun requestBatteryAccess() {
        if (BackgroundHostingManager.isBatteryExempt(this)) {
            finish()
            return
        }
        batteryOpened = true
        runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }
}

/** Requests notification permission independently from background-hosting enrollment. */
class NotificationSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 92)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}

/** Rechecks the monotonic deadline after Doze; stale alarms cannot stop a connected session. */
class SessionTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundHostingManager.checkInactivity(context)
    }
}
