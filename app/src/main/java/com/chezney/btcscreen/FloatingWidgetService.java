package com.chezney.btcscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Foreground service that draws a translucent, draggable BTC price bubble on
 * top of everything. While the bubble is being dragged, an "X" target fades in
 * at the bottom of the screen — dropping the bubble on it kills the service.
 */
public class FloatingWidgetService extends Service {

    private static final String CHANNEL_ID = "btc_widget";
    private static final String PRICE_URL =
            "https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT";
    // 289 five-minute candles = just over 24h of history, refreshed every 30s
    private static final String KLINES_URL =
            "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=289";
    private static final long POLL_MS = 2000;
    private static final long KLINES_REFRESH_MS = 30000;

    private static final String[] WINDOW_LABELS = {"5m", "15m", "1h", "4h", "24h"};
    private static final int[] WINDOW_CANDLES = {1, 3, 12, 48, 288}; // 5m candles back

    private WindowManager windowManager;
    private LinearLayout widgetView;
    private TextView priceText;
    private TextView labelText;
    private TextView pctText;
    private TextView dismissView;
    private WindowManager.LayoutParams widgetParams;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread pollThread;
    private volatile boolean running = true;
    private double lastPrice = -1;
    private double[] closeHistory; // closes of the last 289 5m candles, oldest first
    private long historyFetchedAt;
    private boolean expanded; // tap toggles: compact price-only vs full stats

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createWidget();
        createDismissTarget();
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- widget

    private void createWidget() {
        widgetView = new LinearLayout(this);
        widgetView.setOrientation(LinearLayout.VERTICAL);
        widgetView.setGravity(Gravity.CENTER);
        int padH = dp(14);
        int padV = dp(8);
        widgetView.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59000000); // ~35% black, keeps whatever is behind visible
        bg.setCornerRadius(dp(22));
        widgetView.setBackground(bg);

        labelText = new TextView(this);
        labelText.setText("BTC / USDT · Binance");
        labelText.setTextColor(0xB3FFFFFF);
        labelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        widgetView.addView(labelText);

        priceText = new TextView(this);
        priceText.setText("loading…");
        priceText.setTextColor(Color.WHITE);
        priceText.setTypeface(Typeface.DEFAULT_BOLD);
        priceText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        widgetView.addView(priceText);

        pctText = new TextView(this);
        pctText.setTextColor(0xB3FFFFFF);
        pctText.setTypeface(Typeface.MONOSPACE);
        pctText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pctText.setGravity(Gravity.CENTER_HORIZONTAL);
        pctText.setVisibility(View.GONE); // shown once candle history arrives
        widgetView.addView(pctText);

        widgetParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        widgetParams.gravity = Gravity.TOP | Gravity.START;
        widgetParams.x = dp(16);
        widgetParams.y = dp(120);

        widgetView.setOnTouchListener(new DragToDismissListener());
        applyExpandedState();
        windowManager.addView(widgetView, widgetParams);
    }

    /** Compact: small price only. Expanded: label + bigger price + % rows. */
    private void applyExpandedState() {
        labelText.setVisibility(expanded ? View.VISIBLE : View.GONE);
        pctText.setVisibility(expanded && pctText.length() > 0 ? View.VISIBLE : View.GONE);
        priceText.setTextSize(TypedValue.COMPLEX_UNIT_SP, expanded ? 18 : 14);
        int padH = dp(expanded ? 14 : 10);
        int padV = dp(expanded ? 8 : 5);
        widgetView.setPadding(padH, padV, padH, padV);
    }

    private void createDismissTarget() {
        dismissView = new TextView(this);
        dismissView.setText("✕");
        dismissView.setTextColor(Color.WHITE);
        dismissView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        dismissView.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC444444);
        dismissView.setBackground(bg);
        dismissView.setVisibility(View.GONE);

        int size = dp(64);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(48);
        windowManager.addView(dismissView, p);
    }

    /** Drag the bubble around; drop it on the bottom "X" to quit. */
    private class DragToDismissListener implements View.OnTouchListener {
        private int startX, startY;
        private float downRawX, downRawY;
        private boolean dragging;
        private final int touchSlop =
                ViewConfiguration.get(FloatingWidgetService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = widgetParams.x;
                    startY = widgetParams.y;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                        dismissView.setVisibility(View.VISIBLE);
                    }
                    if (dragging) {
                        widgetParams.x = startX + (int) dx;
                        widgetParams.y = startY + (int) dy;
                        windowManager.updateViewLayout(widgetView, widgetParams);
                        highlightDismiss(overDismiss(event.getRawX(), event.getRawY()));
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean kill = dragging && overDismiss(event.getRawX(), event.getRawY());
                    dismissView.setVisibility(View.GONE);
                    if (kill) {
                        stopSelf();
                    } else if (!dragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        // plain tap: toggle compact <-> expanded
                        expanded = !expanded;
                        applyExpandedState();
                    }
                    return true;
            }
            return false;
        }

        private boolean overDismiss(float rawX, float rawY) {
            Point screen = new Point();
            windowManager.getDefaultDisplay().getRealSize(screen);
            float cx = screen.x / 2f;
            float cy = screen.y - dp(48) - dp(32); // target center (bottom offset + half size)
            float radius = dp(72);
            return Math.hypot(rawX - cx, rawY - cy) < radius;
        }

        private void highlightDismiss(boolean hot) {
            GradientDrawable bg = (GradientDrawable) dismissView.getBackground();
            bg.setColor(hot ? 0xE6E53935 : 0xCC444444); // red when the drop would kill
            dismissView.setScaleX(hot ? 1.25f : 1f);
            dismissView.setScaleY(hot ? 1.25f : 1f);
        }
    }

    // ----------------------------------------------------------------- price

    // NOTE: no lambdas anywhere in this file — the standalone build pipeline
    // dexes with legacy `dx`, which cannot handle invokedynamic.
    private void startPolling() {
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);
                while (running) {
                    try {
                        double price = fetchPrice();
                        if (System.currentTimeMillis() - historyFetchedAt > KLINES_REFRESH_MS) {
                            try {
                                closeHistory = fetchCloseHistory();
                                historyFetchedAt = System.currentTimeMillis();
                            } catch (Exception ignored) {
                                // keep showing the last good history
                            }
                        }
                        final String formatted = usd.format(price);
                        final int color = price > lastPrice && lastPrice > 0 ? 0xFF4CAF50
                                : price < lastPrice ? 0xFFEF5350
                                : Color.WHITE;
                        lastPrice = price;
                        final CharSequence pcts = buildPctLine(price);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                priceText.setText(formatted);
                                priceText.setTextColor(color);
                                labelText.setText("BTC / USDT · Binance");
                                if (pcts != null) {
                                    pctText.setText(pcts);
                                }
                                applyExpandedState();
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                labelText.setText("BTC / USDT · reconnecting…");
                            }
                        });
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "btc-price-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private double fetchPrice() throws Exception {
        return new JSONObject(httpGet(PRICE_URL)).getDouble("price");
    }

    /** Closing price of each of the last 289 five-minute candles, oldest first. */
    private double[] fetchCloseHistory() throws Exception {
        JSONArray candles = new JSONArray(httpGet(KLINES_URL));
        double[] closes = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            closes[i] = candles.getJSONArray(i).getDouble(4); // index 4 = close
        }
        return closes;
    }

    /**
     * "5m +0.12  15m −0.34  1h +1.20  4h +2.05  24h −3.10" (values in %),
     * each number tinted green/red. Null until candle history is available.
     */
    private CharSequence buildPctLine(double price) {
        double[] history = closeHistory;
        if (history == null || history.length < WINDOW_CANDLES[WINDOW_CANDLES.length - 1] + 1) {
            return null;
        }
        SpannableStringBuilder line = new SpannableStringBuilder();
        for (int i = 0; i < WINDOW_LABELS.length; i++) {
            // close of the candle N slots back ≈ price that long ago
            double then = history[history.length - 1 - WINDOW_CANDLES[i]];
            double pct = (price - then) / then * 100.0;
            if (i > 0) {
                line.append(i == 2 ? "\n" : "  "); // two windows on row 1, three on row 2
            }
            line.append(WINDOW_LABELS[i]).append(' ');
            int start = line.length();
            line.append(String.format(Locale.US, "%+.2f%%", pct));
            int color = pct > 0 ? 0xFF4CAF50 : pct < 0 ? 0xFFEF5350 : 0xFFFFFFFF;
            line.setSpan(new ForegroundColorSpan(color), start, line.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return line;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------ foreground

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "BTC price widget", NotificationManager.IMPORTANCE_LOW));

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("BTC price floating on screen")
                .setContentText("Drag the bubble onto the ✕ to close")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
    }

    // ------------------------------------------------------------------ misc

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @Override
    public void onDestroy() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (windowManager != null) {
            if (widgetView != null) {
                windowManager.removeView(widgetView);
            }
            if (dismissView != null) {
                windowManager.removeView(dismissView);
            }
        }
        super.onDestroy();
    }
}
