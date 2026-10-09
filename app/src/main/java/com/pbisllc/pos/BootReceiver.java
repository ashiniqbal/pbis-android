package com.pbisllc.pos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start the print bridge when the terminal boots, so cloud and KDS jobs print right away. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && !new Prefs(c).startUrl().isEmpty()) BridgeService.start(c);
    }
}
