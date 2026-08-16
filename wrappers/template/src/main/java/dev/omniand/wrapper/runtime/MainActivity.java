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
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String appId = info.metaData.getString("dev.omniand.APP_ID");
            Intent platform = new Intent();
            platform.setClassName("dev.omniand.launcher", "dev.omniand.launcher.WebAppActivity");
            platform.putExtra("appId", appId);
            startActivity(platform);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Install OmniAnd to open this app", Toast.LENGTH_LONG).show();
        } catch (PackageManager.NameNotFoundException error) {
            Toast.makeText(this, "Invalid OmniAnd wrapper", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
