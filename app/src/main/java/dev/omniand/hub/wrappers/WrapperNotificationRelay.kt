package dev.omniand.hub.wrappers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Publishes app-owned notifications through the trusted generated wrapper for that app. */
object WrapperNotificationRelay {
    private const val SERVICE = "dev.omniand.wrapper.runtime.NotificationRelayService"
    private const val DESCRIPTOR = "dev.omniand.wrapper.NOTIFICATIONS/2"

    fun publish(
        context: Context,
        appId: String,
        notificationId: Int,
        channelId: String,
        channelName: String,
        title: String,
        text: String,
        publicTitle: String = title,
        publicText: String = "New notification",
        route: String? = null,
        threadId: String? = null,
        timestamp: Long = 0,
        timeoutMillis: Long = 0,
    ): Boolean =
        call(context, appId, TRANSACTION_PUBLISH) {
            writeString(appId)
            writeInt(notificationId)
            writeString(channelId)
            writeString(channelName)
            writeString(title)
            writeString(text)
            writeString(publicTitle)
            writeString(publicText)
            writeString(route)
            writeString(threadId)
            writeLong(timestamp)
            writeLong(timeoutMillis)
        }

    fun cancel(context: Context, appId: String, notificationId: Int): Boolean =
        call(context, appId, TRANSACTION_CANCEL) {
            writeString(appId)
            writeInt(notificationId)
        }

    private fun call(
        context: Context,
        appId: String,
        code: Int,
        payload: Parcel.() -> Unit,
    ): Boolean {
        if (!WrapperInstaller.isTrustedWrapper(context, appId)) return false
        val latch = CountDownLatch(1)
        var result = false
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DESCRIPTOR)
                        data.payload()
                        result =
                            binder.transact(code, data, reply, 0) &&
                                reply.readException().let { reply.readInt() == 1 }
                    } catch (_: Exception) {
                        result = false
                    } finally {
                        data.recycle()
                        reply.recycle()
                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    latch.countDown()
                }
            }
        val bound =
            runCatching {
                    context.bindService(
                        Intent().setClassName(WrapperInstaller.packageName(appId), SERVICE),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                .getOrDefault(false)
        if (!bound) return false
        return try {
            latch.await(750, TimeUnit.MILLISECONDS)
            result
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private const val TRANSACTION_PUBLISH = 1
    private const val TRANSACTION_CANCEL = 2
}
