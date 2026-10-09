package com.pbisllc.pos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PBIS Print Bridge for Android. Same protocol as the desktop bridge:
 *   - local:  http://127.0.0.1:17788  GET /health, GET /printers, POST /print
 *   - cloud:  polls <site>/wp-json/pbis/v1/bridge/jobs with the bridge token
 * Runs as a foreground service so printing keeps working with the screen off.
 */
public class BridgeService extends Service {
    static final String TAG = "PBISBridge";
    static final String VERSION = "1.3.0";
    private static final String CHANNEL = "bridge";

    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService pool;
    private Thread cloud;
    private PowerManager.WakeLock wake;
    private Printers printers;
    private Prefs prefs;
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 500; }
    };

    static void start(Context c) {
        Intent i = new Intent(c, BridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Log.w(TAG, "Could not start bridge: " + e.getMessage()); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        printers = new Printers(this);
        goForeground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) { wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pbis:bridge"); wake.setReferenceCounted(false); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            pool = Executors.newFixedThreadPool(4);
            new Thread(this::serve, "pbis-http").start();
        }
        restartCloud();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (pool != null) pool.shutdownNow();
        if (cloud != null) cloud.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Printing", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("PBIS POS printing is ready")
            .setContentText("Receipts and kitchen tickets print from this device.")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(7, n);
    }

    /* ------------------------------------------------------------- jobs */

    /** Print one job object (desktop-bridge format). Returns null on success, or an error message. */
    String printJob(JSONObject job) {
        try {
            JSONObject p = job.optJSONObject("printer");
            if (p == null) p = new JSONObject();
            String address = p.optString("address", "");
            int port = p.optInt("port", 9100);
            String model = p.optString("model", "") + " " + job.optString("lang", "");
            int copies = job.optInt("copies", p.optInt("copies", 1));
            byte[] data = null;
            String raw = job.optString("data", "");
            if (!raw.isEmpty()) data = Base64.decode(raw, Base64.DEFAULT);
            String png = job.optString("png", "");
            boolean rasterOnly = "star_raster".equals(job.optString("lang")) || (data == null && !png.isEmpty());
            if (rasterOnly && !png.isEmpty()) {
                boolean star = model.toLowerCase(Locale.ROOT).contains("star");
                int dots = p.optInt("paper_width", 80) == 58 ? 384 : 576;
                data = Raster.fromPng(Base64.decode(png, Base64.DEFAULT), star, dots);
            }
            if (data == null) return "Empty print job";
            if (wake != null) wake.acquire(30000);
            printers.print(address, port, data, copies);
            Log.i(TAG, "Printed " + job.optString("job") + " -> " + address);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Print failed: " + e.getMessage());
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private synchronized boolean firstTime(String key) {
        if (seen.containsKey(key)) return false;
        seen.put(key, System.currentTimeMillis());
        return true;
    }

    /* ------------------------------------------------------- local HTTP */

    private void serve() {
        try {
            server = new ServerSocket(prefs.port(), 16, InetAddress.getByName("127.0.0.1"));
            Log.i(TAG, "Listening on 127.0.0.1:" + prefs.port());
            while (running) {
                final Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "HTTP server stopped: " + e.getMessage());
        }
    }

    private boolean originAllowed(String origin) {
        if (origin == null || origin.isEmpty()) return true;
        String site = prefs.origin();
        if (site.isEmpty()) return true;
        return site.equalsIgnoreCase(origin.replaceAll("/+$", ""));
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            String method = req.length > 0 ? req[0] : "GET";
            String path = req.length > 1 ? req[1].split("\\?")[0] : "/";
            int len = 0;
            String origin = "";
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c < 0) continue;
                String k = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), v = lines[i].substring(c + 1).trim();
                if (k.equals("content-length")) len = Integer.parseInt(v);
                else if (k.equals("origin")) origin = v;
            }
            boolean ok = originAllowed(origin);
            StringBuilder cors = new StringBuilder();
            if (!origin.isEmpty() && ok) {
                cors.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n")
                    .append("Vary: Origin\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    .append("Access-Control-Allow-Headers: Content-Type\r\n")
                    .append("Access-Control-Allow-Private-Network: true\r\n")
                    .append("Access-Control-Max-Age: 600\r\n");
            }
            OutputStream out = s.getOutputStream();
            if (method.equals("OPTIONS")) { respond(out, 204, cors.toString(), ""); s.close(); return; }
            if (!ok) { respond(out, 403, "", "{\"ok\":false,\"error\":\"This website may not print here\"}"); s.close(); return; }
            if (len > 8 * 1024 * 1024) { respond(out, 413, cors.toString(), "{\"ok\":false,\"error\":\"Job too large\"}"); s.close(); return; }
            byte[] body = new byte[len];
            int read = 0;
            while (read < len) { int n = in.read(body, read, len - read); if (n < 0) break; read += n; }

            if (method.equals("GET") && (path.equals("/") || path.equals("/health"))) {
                JSONObject o = new JSONObject();
                o.put("ok", true); o.put("name", "PBIS Print Bridge"); o.put("version", VERSION); o.put("platform", "android");
                o.put("device", Build.MANUFACTURER + " " + Build.MODEL); o.put("cloud", !prefs.token().isEmpty());
                respond(out, 200, cors.toString(), o.toString());
            } else if (method.equals("GET") && path.equals("/printers")) {
                JSONObject o = new JSONObject();
                o.put("ok", true); o.put("printers", printers.detect()); o.put("builtin", prefs.builtin());
                respond(out, 200, cors.toString(), o.toString());
            } else if (method.equals("POST") && path.equals("/print")) {
                JSONObject job;
                try { job = new JSONObject(new String(body, StandardCharsets.UTF_8)); }
                catch (Exception e) { respond(out, 400, cors.toString(), "{\"ok\":false,\"error\":\"Invalid JSON\"}"); s.close(); return; }
                String key = "local:" + job.optString("job") + ":" + job.optInt("attempt", 0);
                if (!job.optString("job").isEmpty() && !firstTime(key)) { respond(out, 200, cors.toString(), "{\"ok\":true,\"duplicate\":true}"); s.close(); return; }
                String err = printJob(job);
                JSONObject o = new JSONObject();
                o.put("ok", err == null);
                if (err != null) o.put("error", err);
                respond(out, err == null ? 200 : 502, cors.toString(), o.toString());
            } else {
                respond(out, 404, cors.toString(), "{\"ok\":false,\"error\":\"Not found\"}");
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev3 = 0, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            prev3 = (prev3 << 8 | c) & 0xFFFFFFFF;
            if (prev3 == 0x0D0A0D0A) return b.toString("ISO-8859-1");
            if (b.size() > 32768) return null;
        }
        return null;
    }

    private static void respond(OutputStream out, int code, String extra, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 413 ? "Too Large" : "Bad Gateway";
        String h = "HTTP/1.1 " + code + " " + status + "\r\n" + extra
            + "Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + b.length + "\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    /* ------------------------------------------------------- cloud poll */

    private synchronized void restartCloud() {
        if (cloud != null) cloud.interrupt();
        if (prefs.token().isEmpty() || prefs.origin().isEmpty()) { cloud = null; return; }
        cloud = new Thread(this::pollLoop, "pbis-cloud");
        cloud.start();
    }

    private void pollLoop() {
        long delay = 2500;
        int failures = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                JSONObject r = http("GET", prefs.origin() + "/wp-json/pbis/v1/bridge/jobs", null);
                if (r != null) {
                    failures = 0;
                    delay = Math.max(800, r.optLong("interval", 2500));
                    JSONArray jobs = r.optJSONArray("jobs");
                    if (jobs != null) for (int i = 0; i < jobs.length(); i++) {
                        JSONObject job = jobs.getJSONObject(i);
                        String err = firstTime("cloud:" + job.optString("job")) ? printJob(job) : null;
                        JSONObject ack = new JSONObject();
                        ack.put("job", job.optString("job")); ack.put("ok", err == null); ack.put("error", err == null ? "" : err);
                        http("POST", prefs.origin() + "/wp-json/pbis/v1/bridge/ack", ack);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                failures++;
                delay = Math.min(30000, (long) (2500 * Math.pow(1.5, Math.min(failures, 8))));
                if (failures == 1 || failures % 20 == 0) Log.w(TAG, "Cloud poll failed: " + e.getMessage());
            }
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
        }
    }

    private JSONObject http(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "PBIS-Print-Bridge-Android/" + VERSION);
        c.setRequestProperty("X-PBIS-Bridge", prefs.token());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        if (code == 401) throw new Exception("Bridge token rejected — generate a new one in PBIS → Settings → Printers");
        if (code >= 400) throw new Exception("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) sb.append(line);
        }
        c.disconnect();
        return sb.length() > 0 && sb.charAt(0) == '{' ? new JSONObject(sb.toString()) : null;
    }
}
