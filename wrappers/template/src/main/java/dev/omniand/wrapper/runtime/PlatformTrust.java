package dev.omniand.wrapper.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.security.MessageDigest;

/** Verifies that a wrapper entry point was invoked by the Platform build that generated it. */
final class PlatformTrust {
    private static final String PLATFORM_PACKAGE = "dev.omniand.launcher";

    private PlatformTrust() {}

    static boolean isTrusted(Context context, String callerPackage) {
        if (!PLATFORM_PACKAGE.equals(callerPackage)) return false;
        try {
            ApplicationInfo wrapper =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    context.getPackageName(), PackageManager.GET_META_DATA);
            String expected = wrapper.metaData.getString("dev.omniand.PLATFORM_CERT");
            return expected != null && expected.equals(fingerprint(context, callerPackage));
        } catch (Exception error) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static String fingerprint(Context context, String packageName) {
        try {
            int flags =
                    Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES;
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, flags);
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
}
