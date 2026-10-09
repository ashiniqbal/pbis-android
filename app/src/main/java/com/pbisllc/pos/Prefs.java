package com.pbisllc.pos;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings (site URL, cloud bridge token, chosen built-in printer). */
final class Prefs {
    private static final String NAME = "pbis";
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE); }

    /** Page the app opens, e.g. https://pos.example.com/pos/ */
    String startUrl() { return sp.getString("start_url", ""); }
    void startUrl(String v) { sp.edit().putString("start_url", v == null ? "" : v.trim()).apply(); }

    /** Site origin, e.g. https://pos.example.com — only pages from here may print. */
    String origin() {
        String u = startUrl();
        try {
            java.net.URL url = new java.net.URL(u);
            int port = url.getPort();
            return url.getProtocol() + "://" + url.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) { return ""; }
    }

    /** Optional: print bridge token (cloud queue) from Back office → Settings → Printers. */
    String token() { return sp.getString("token", ""); }
    void token(String v) { sp.edit().putString("token", v == null ? "" : v.trim()).apply(); }

    /** Target used for the address "builtin" — chosen in settings, or "" for auto-detect. */
    String builtin() { return sp.getString("builtin", ""); }
    void builtin(String v) { sp.edit().putString("builtin", v == null ? "" : v.trim()).apply(); }

    boolean kiosk() { return sp.getBoolean("kiosk", true); }
    void kiosk(boolean v) { sp.edit().putBoolean("kiosk", v).apply(); }

    int port() { return 17788; }
}
