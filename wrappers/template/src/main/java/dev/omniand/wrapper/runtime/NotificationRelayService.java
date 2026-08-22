package dev.omniand.wrapper.runtime;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.core.app.NotificationCompat;
import java.security.MessageDigest;

/**
 * Private Binder endpoint through which the Platform asks a generated wrapper to own Messages
 * notifications. Every transaction verifies the caller's package and embedded Platform signing
 * certificate; malformed payloads fail without publishing a notification.
 */
public final class NotificationRelayService extends Service {
    private static final String DESCRIPTOR = "dev.omniand.wrapper.NOTIFICATIONS/1";
    private static final String CHANNEL = "incoming-messages";
    private String appId;
    private String platformCertificate;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ApplicationInfo info =
                    getPackageManager()
                            .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            appId = info.metaData.getString("dev.omniand.APP_ID");
            platformCertificate = info.metaData.getString("dev.omniand.PLATFORM_CERT");
        } catch (Exception ignored) {
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final Binder binder =
            new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    data.enforceInterface(DESCRIPTOR);
                    if (!trustedCaller()) {
                        reply.writeException(new SecurityException("Untrusted caller"));
                        return true;
                    }
                    try {
                        String requestedApp = data.readString();
                        if (!appId.equals(requestedApp))
                            throw new SecurityException("Wrong app id");
                        boolean ok;
                        if (code == 1) {
                            String thread = data.readString();
                            int id = data.readInt();
                            String title = data.readString();
                            String preview = data.readString();
                            long timestamp = data.readLong();
                            ok = publish(thread, id, title, preview, timestamp);
                        } else if (code == 2) {
                            String thread = data.readString();
                            cancel(thread.hashCode() & 0x7fffffff);
                            ok = true;
                        } else if (code == 3) {
                            manager().cancelAll();
                            ok = true;
                        } else return super.onTransact(code, data, reply, flags);
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                        return true;
                    } catch (Exception error) {
                        reply.writeException(error);
                        return true;
                    }
                }
            };

    private boolean trustedCaller() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String name : packages)
            if ("dev.omniand.launcher".equals(name)
                    && platformCertificate.equals(fingerprint(name))) return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    private String fingerprint(String packageName) {
        try {
            int flags =
                    Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES;
            PackageInfo info = getPackageManager().getPackageInfo(packageName, flags);
            byte[] certificate =
                    Build.VERSION.SDK_INT >= 28
                            ? info.signingInfo.getApkContentsSigners()[0].toByteArray()
                            : info.signatures[0].toByteArray();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private boolean publish(String thread, int id, String title, String preview, long timestamp) {
        if (!thread.matches("[0-9]+")
                || title == null
                || preview == null
                || title.length() > 160
                || preview.length() > 500) return false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 26)
            manager()
                    .createNotificationChannel(
                            new NotificationChannel(
                                    CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH));
        Intent click =
                new Intent()
                        .setClassName("dev.omniand.launcher", "dev.omniand.launcher.WebAppActivity")
                        .putExtra("appId", appId)
                        .putExtra("route", "#/thread?id=" + thread)
                        .putExtra("threadId", thread);
        PendingIntent pending =
                PendingIntent.getActivity(
                        this,
                        id,
                        click,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder publicBuilder =
                new NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.sym_action_chat)
                        .setContentTitle("Messages")
                        .setContentText("New message");
        android.app.Notification notification =
                new NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.sym_action_chat)
                        .setContentTitle(title)
                        .setContentText(preview)
                        .setWhen(timestamp)
                        .setAutoCancel(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicBuilder.build())
                        .setContentIntent(pending)
                        .build();
        manager().notify(id, notification);
        return true;
    }

    private NotificationManager manager() {
        return getSystemService(NotificationManager.class);
    }

    private void cancel(int id) {
        manager().cancel(id);
    }
}
