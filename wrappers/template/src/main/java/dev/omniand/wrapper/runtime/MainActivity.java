package dev.omniand.wrapper.runtime;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openPlatformAndFinish();
    }

    private void openPlatformAndFinish() {
        try {
            ApplicationInfo info =
                    getPackageManager()
                            .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String appId = info.metaData.getString("dev.omniand.APP_ID");
            Intent platform = new Intent();
            platform.setClassName("dev.omniand.launcher", "dev.omniand.hub.WebAppActivity");
            platform.putExtra("appId", appId);
            String route = getIntent().getStringExtra("route");
            String threadId = getIntent().getStringExtra("threadId");
            if (route != null && route.matches("^#/thread\\?id=[0-9]+$")) {
                platform.putExtra("route", route);
                platform.putExtra("threadId", threadId);
            }
            startActivity(platform);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Install OmniAnd to open this app", Toast.LENGTH_LONG).show();
        } catch (PackageManager.NameNotFoundException error) {
            Toast.makeText(this, "Invalid OmniAnd wrapper", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
