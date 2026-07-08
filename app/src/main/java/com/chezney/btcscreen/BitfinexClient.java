package com.chezney.btcscreen;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal Bitfinex v2 authenticated REST client (read-only usage).
 * Only needs a key with read permissions — no trading or withdrawal rights.
 */
public final class BitfinexClient {

    private final String apiKey;
    private final String apiSecret;

    public BitfinexClient(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    /**
     * Unrealized profit/loss across open margin positions.
     * Returns {total PL in quote currency (USD), number of open positions}.
     */
    public double[] fetchPositionsPnl() throws Exception {
        JSONArray positions = new JSONArray(authPost("v2/auth/r/positions"));
        double total = 0;
        int count = 0;
        for (int i = 0; i < positions.length(); i++) {
            JSONArray p = positions.getJSONArray(i);
            // position array: [SYMBOL, STATUS, AMOUNT, BASE_PRICE, FUNDING,
            //                  FUNDING_TYPE, PL, PL_PERC, ...]
            if ("ACTIVE".equals(p.optString(1))) {
                total += p.optDouble(6, 0);
                count++;
            }
        }
        return new double[] {total, count};
    }

    private String authPost(String path) throws Exception {
        String nonce = String.valueOf(System.currentTimeMillis() * 1000L);
        String body = "{}";
        String payload = "/api/" + path + nonce + body;

        Mac mac = Mac.getInstance("HmacSHA384");
        mac.init(new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA384"));
        byte[] digest = mac.doFinal(payload.getBytes("UTF-8"));
        StringBuilder signature = new StringBuilder();
        for (byte b : digest) {
            signature.append(String.format("%02x", b));
        }

        HttpURLConnection conn =
                (HttpURLConnection) new URL("https://api.bitfinex.com/" + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("bfx-nonce", nonce);
        conn.setRequestProperty("bfx-apikey", apiKey);
        conn.setRequestProperty("bfx-signature", signature.toString());
        conn.setDoOutput(true);
        try {
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();

            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder response = new StringBuilder();
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
            }
            if (status >= 400) {
                throw new IOException("Bitfinex HTTP " + status + ": " + response);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }
}
