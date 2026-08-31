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
 * Private Binder endpoint through which the Platform asks a generated wrapper to own its
 * notifications. Every transaction verifies the caller's package and embedded Platform signing
 * certificate; malformed payloads fail without publishing a notification.
 */
public final class NotificationRelayService extends Service {
    private static final String DESCRIPTOR = "dev.omniand.wrapper.NOTIFICATIONS/2";
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
                            int id = data.readInt();
                            String channel = data.readString();
                            String channelName = data.readString();
                            String title = data.readString();
                            String text = data.readString();
                            String publicTitle = data.readString();
                            String publicText = data.readString();
                            String route = data.readString();
                            String threadId = data.readString();
                            long timestamp = data.readLong();
                            long timeoutMillis = data.readLong();
                            ok =
                                    publish(
                                            id,
                                            channel,
                                            channelName,
                                            title,
                                            text,
                                            publicTitle,
                                            publicText,
                                            route,
                                            threadId,
                                            timestamp,
                                            timeoutMillis);
                        } else if (code == 2) {
                            cancel(data.readInt());
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

    private boolean publish(
            int id,
            String channel,
            String channelName,
            String title,
            String text,
            String publicTitle,
            String publicText,
            String route,
            String threadId,
            long timestamp,
            long timeoutMillis) {
        if (id < 0
                || channel == null
                || !channel.matches("[a-z0-9][a-z0-9-]{0,39}")
                || channelName == null
                || channelName.isEmpty()
                || channelName.length() > 80
                || title == null
                || title.isEmpty()
                || title.length() > 160
                || text == null
                || text.length() > 500
                || publicTitle == null
                || publicTitle.isEmpty()
                || publicTitle.length() > 160
                || publicText == null
                || publicText.length() > 500
                || (route != null && route.length() > 2200)
                || (threadId != null && threadId.length() > 100)
                || timestamp < 0
                || timeoutMillis < 0
                || timeoutMillis > 120_000) return false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 26)
            manager()
                    .createNotificationChannel(
                            new NotificationChannel(
                                    channel, channelName, NotificationManager.IMPORTANCE_HIGH));
        Intent click = new Intent(this, MainActivity.class);
        if (route != null) click.putExtra("route", route);
        if (threadId != null) click.putExtra("threadId", threadId);
        PendingIntent pending =
                PendingIntent.getActivity(
                        this,
                        id,
                        click,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int icon = getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.ic_dialog_info;
        NotificationCompat.Builder publicBuilder =
                new NotificationCompat.Builder(this, channel)
                        .setSmallIcon(icon)
                        .setContentTitle(publicTitle)
                        .setContentText(publicText);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channel)
                        .setSmallIcon(icon)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicBuilder.build())
                        .setContentIntent(pending);
        if (timestamp > 0) builder.setWhen(timestamp);
        if (timeoutMillis > 0) builder.setTimeoutAfter(timeoutMillis);
        android.app.Notification notification = builder.build();
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
