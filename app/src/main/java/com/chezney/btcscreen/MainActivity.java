package com.chezney.btcscreen;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Invisible launcher activity. Its only job is to make sure the app can draw
 * over other apps, then hand off to FloatingWidgetService and go away.
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
            return; // continue in onRequestPermissionsResult
        }
        proceed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // Notification permission is optional — the widget works without it.
        proceed();
    }

    private void proceed() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Allow \"Display over other apps\" so the BTC price can float on screen",
                    Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }
        startWidget();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startWidget();
            } else {
                Toast.makeText(this, "Permission denied — cannot show the floating price",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startWidget() {
        startForegroundService(new Intent(this, FloatingWidgetService.class));
        finish();
    }
}
