package com.chezney.btcscreen;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small settings screen: optional Bitfinex read-only API key for the PnL
 * line, then starts the floating widget. The key is stored only in this
 * app's private SharedPreferences on the device.
 */
public class MainActivity extends Activity {

    static final String PREFS = "btc_screen";
    static final String PREF_BFX_KEY = "bfx_key";
    static final String PREF_BFX_SECRET = "bfx_secret";

    private static final int REQ_OVERLAY = 1;

    private EditText keyInput;
    private EditText secretInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                    new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        buildForm();
    }

    private void buildForm() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean hasKey = prefs.getString(PREF_BFX_KEY, "").length() > 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("BTC Screen");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText(hasKey
                ? "Bitfinex key saved ✓ — leave blank to keep it, or paste a new one."
                : "Optional: paste a READ-ONLY Bitfinex API key to show your open-position "
                        + "PnL in the widget. It is stored only on this phone. "
                        + "Leave blank to show just the BTC price.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);

        keyInput = new EditText(this);
        keyInput.setHint("Bitfinex API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(keyInput);

        secretInput = new EditText(this);
        secretInput.setHint("Bitfinex API secret");
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(secretInput);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(16), 0, 0);

        if (hasKey) {
            Button clear = new Button(this);
            clear.setText("Remove key");
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove(PREF_BFX_KEY).remove(PREF_BFX_SECRET).apply();
                    Toast.makeText(MainActivity.this, "Key removed", Toast.LENGTH_SHORT).show();
                    startWidgetFlow();
                }
            });
            buttons.addView(clear);
        }

        Button start = new Button(this);
        start.setText("Start widget");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = keyInput.getText().toString().trim();
                String secret = secretInput.getText().toString().trim();
                if (key.length() > 0 && secret.length() > 0) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_BFX_KEY, key)
                            .putString(PREF_BFX_SECRET, secret)
                            .apply();
                    Toast.makeText(MainActivity.this, "Key saved on this phone",
                            Toast.LENGTH_SHORT).show();
                } else if (key.length() > 0 || secret.length() > 0) {
                    Toast.makeText(MainActivity.this,
                            "Enter BOTH key and secret (or leave both blank)",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                startWidgetFlow();
            }
        });
        buttons.addView(start);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void startWidgetFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Allow \"Display over other apps\" so the BTC price can float on screen",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
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
            }
        }
    }

    private void startWidget() {
        // restart so the service picks up a changed/removed key
        stopService(new Intent(this, FloatingWidgetService.class));
        startForegroundService(new Intent(this, FloatingWidgetService.class));
        finish();
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
