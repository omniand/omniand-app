package dev.omniand.launcher.sms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import dev.omniand.launcher.wrappers.WrapperInstaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WrapperNotificationRelay {
    private const val APP_ID = "messages"
    private const val PACKAGE = "dev.omniand.generated.messages"
    private const val SERVICE = "dev.omniand.wrapper.runtime.NotificationRelayService"
    private const val DESCRIPTOR = "dev.omniand.wrapper.NOTIFICATIONS/1"

    fun publish(
        context: Context,
        threadId: String,
        notificationId: Int,
        title: String,
        preview: String,
        timestamp: Long,
    ): Boolean =
        call(context, 1) {
            writeString(APP_ID)
            writeString(threadId)
            writeInt(notificationId)
            writeString(title)
            writeString(preview)
            writeLong(timestamp)
        }

    fun cancelThread(context: Context, threadId: String): Boolean =
        call(context, 2) {
            writeString(APP_ID)
            writeString(threadId)
        }

    fun cancelAll(context: Context): Boolean = call(context, 3) { writeString(APP_ID) }

    private fun call(context: Context, code: Int, payload: Parcel.() -> Unit): Boolean {
        if (!WrapperInstaller.isTrustedWrapper(context, APP_ID)) return false
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
                        Intent().setClassName(PACKAGE, SERVICE),
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
}
