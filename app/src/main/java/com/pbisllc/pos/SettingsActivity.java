package com.pbisllc.pos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** First-run and settings screen: site address, cloud token, printers on this device. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private Printers printers;
    private LinearLayout list;
    private EditText url, token;
    private Switch kiosk;
    private TextView builtinLabel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        printers = new Printers(this);
        BridgeService.start(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);

        TextView h = text("PBIS POS", 28, true);
        h.setTextColor(Color.parseColor("#0E131A"));
        root.addView(h);
        root.addView(text("Version " + BridgeService.VERSION + " · " + Build.MANUFACTURER + " " + Build.MODEL, 13, false));

        root.addView(section("Your PBIS page"));
        root.addView(text("The page this device opens. POS: https://your-site.com/pos/ · Kitchen: …/kds/ · Customer display: …/display/", 13, false));
        url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://pos.your-site.com/pos/");
        url.setText(prefs.startUrl());
        root.addView(url);

        kiosk = new Switch(this);
        kiosk.setText("Full screen (hide Android bars)");
        kiosk.setChecked(prefs.kiosk());
        root.addView(kiosk);

        root.addView(section("Cloud printing (optional)"));
        root.addView(text("Paste a Print Bridge token from Back office → Settings → Printers to let other tablets and the KDS print to printers connected to this device (connection “PBIS Print Bridge — cloud queue”).", 13, false));
        token = new EditText(this);
        token.setHint("pbb_…");
        token.setText(prefs.token());
        root.addView(token);

        root.addView(section("Printers on this device"));
        builtinLabel = text("", 13, false);
        root.addView(builtinLabel);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        Button refresh = button("Find printers again", false);
        refresh.setOnClickListener(v -> { printers.requestAllUsbPermissions(); render(); });
        root.addView(refresh);
        root.addView(text("In PBIS, set the printer’s connection to “PBIS Print Bridge — this device” and its address to builtin, usb, bt:MAC, a serial port, or the IP of a network printer (e.g. 192.168.0.150). Test a printer here, then tap “Use as built-in” for the one inside this terminal.", 13, false));

        Button save = button(getIntent().getBooleanExtra("first_run", false) ? "Save and open PBIS POS" : "Save", true);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(24);
        root.addView(save, lp);
        render();
    }

    private void save() {
        String u = url.getText().toString().trim();
        if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
        if (u.isEmpty()) { Toast.makeText(this, "Enter your PBIS page address", Toast.LENGTH_LONG).show(); return; }
        prefs.startUrl(u);
        prefs.token(token.getText().toString());
        prefs.kiosk(kiosk.isChecked());
        BridgeService.start(this); // re-reads token / origin
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void render() {
        list.removeAllViews();
        String b = prefs.builtin();
        builtinLabel.setText("Built-in printer: " + (b.isEmpty() ? "auto-detect" : b));
        JSONArray found = printers.detect();
        if (found.length() == 0) list.addView(text("No USB, Bluetooth or serial printers found. Network printers don’t need to be found — just use their IP in PBIS.", 14, false));
        for (int i = 0; i < found.length(); i++) {
            JSONObject p = found.optJSONObject(i);
            if (p == null) continue;
            final String addr = p.optString("address");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            TextView t = text(p.optString("name") + "\n" + addr, 14, false);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button test = button("Test", false);
            test.setOnClickListener(v -> test(addr));
            row.addView(test);
            Button use = button(addr.equals(b) ? "✓ Built-in" : "Use as built-in", false);
            use.setOnClickListener(v -> { prefs.builtin(addr.equals(prefs.builtin()) ? "" : addr); render(); });
            row.addView(use);
            list.addView(row);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button tb = button("Test “builtin”", false);
        tb.setOnClickListener(v -> test("builtin"));
        row.addView(tb);
        list.addView(row);
    }

    private void test(String addr) {
        new Thread(() -> {
            String msg;
            try {
                byte[] d = testTicket(addr);
                printers.print(addr, 9100, d, 1);
                msg = "Sent to " + addr;
            } catch (Exception e) {
                msg = e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); render(); });
        }).start();
    }

    static byte[] testTicket(String addr) {
        String s = "\u001b@\u001ba\u0001\u001b!0PBIS POS\n\u001b!\u0000Printer test\n" + addr + "\n" + Build.MANUFACTURER + " " + Build.MODEL + "\n\n"
            + "\u001ba\u0000If you can read this,\nprinting works.\n\n\n\n\u001dVB\u0000";
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private TextView section(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(24), 0, dp(4));
        t.setTextColor(Color.parseColor("#0160BF"));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, boolean primary) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        if (primary) { bt.setBackgroundColor(Color.parseColor("#0160BF")); bt.setTextColor(Color.WHITE); }
        return bt;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("unused")
    private void hide(View v) { v.setVisibility(View.GONE); }
}
