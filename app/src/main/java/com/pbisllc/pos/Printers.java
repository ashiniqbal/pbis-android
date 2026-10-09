package com.pbisllc.pos;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Sends raw printer bytes (ESC/POS or Star Line) to a printer.
 *
 * Address formats (the "Address" field of the printer in PBIS):
 *   builtin                     the terminal's own printer (Landi C20 Pro, Sunmi, iMin, PAX…)
 *   192.168.0.150[:9100]        network printer (Ethernet / Wi-Fi)
 *   usb  |  usb:04b8:0202       USB printer (first one, or by vendor:product id in hex)
 *   bt:00:11:22:33:44:55        paired Bluetooth printer by MAC
 *   bt:InnerPrinter             paired Bluetooth printer by name
 *   /dev/ttyS1                  serial port (built-in printers on some terminals)
 */
final class Printers {
    static final String ACTION_USB_PERMISSION = "com.pbisllc.pos.USB_PERMISSION";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] SERIAL_CANDIDATES = { "/dev/ttyS1", "/dev/ttyS3", "/dev/ttyS4", "/dev/ttyS2", "/dev/ttyACM0", "/dev/ttyUSB0", "/dev/usb/lp0" };
    private static final Object LOCK = new Object();

    private final Context ctx;
    private final Prefs prefs;

    Printers(Context c) { ctx = c.getApplicationContext(); prefs = new Prefs(c); }

    /** Print bytes to an address, `copies` times. Throws with a human-readable message. */
    void print(String address, int port, byte[] data, int copies) throws Exception {
        if (data == null || data.length == 0) throw new Exception("Empty print job");
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) throw new Exception("This printer has no address. In PBIS set it to builtin, an IP, usb, bt:MAC or a serial port.");
        if (a.equalsIgnoreCase("builtin") || a.equalsIgnoreCase("internal") || a.equalsIgnoreCase("auto")) a = resolveBuiltin();
        copies = Math.max(1, Math.min(5, copies));
        synchronized (LOCK) {
            for (int i = 0; i < copies; i++) send(a, port, data);
        }
    }

    private void send(String a, int port, byte[] data) throws Exception {
        String low = a.toLowerCase(Locale.ROOT);
        if (low.startsWith("usb")) { usb(a, data); return; }
        if (low.startsWith("bt:")) { bluetooth(a.substring(3).trim(), data); return; }
        if (a.startsWith("/dev/")) { serial(a, data); return; }
        if (low.startsWith("tcp:")) a = a.substring(4);
        String host = a; int p = port > 0 ? port : 9100;
        int c = a.lastIndexOf(':');
        if (c > 0 && a.indexOf(':') == c) { host = a.substring(0, c); p = Integer.parseInt(a.substring(c + 1)); }
        tcp(host, p, data);
    }

    /* ------------------------------------------------------------ built-in */

    /** Find the device's own printer: saved choice, USB printer, InnerPrinter BT, serial port. */
    String resolveBuiltin() throws Exception {
        String saved = prefs.builtin();
        if (!saved.isEmpty() && !saved.equalsIgnoreCase("builtin")) return saved;
        UsbDevice d = findUsb(null, true);
        if (d != null) return "usb:" + hex4(d.getVendorId()) + ":" + hex4(d.getProductId());
        String inner = innerBluetooth();
        if (inner != null) return "bt:" + inner;
        for (String s : SERIAL_CANDIDATES) { File f = new File(s); if (f.exists() && f.canWrite()) return s; }
        d = findUsb(null, false);
        if (d != null) return "usb:" + hex4(d.getVendorId()) + ":" + hex4(d.getProductId());
        throw new Exception("No built-in printer found. Open PBIS POS settings (long-press the screen with 3 fingers or use the menu) → Printers, test the detected printers and pick the built-in one.");
    }

    /** Everything we can see, for the settings screen and GET /printers. */
    JSONArray detect() {
        JSONArray out = new JSONArray();
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) for (UsbDevice d : um.getDeviceList().values()) {
                if (bulkOut(d, false) == null) continue;
                JSONObject o = new JSONObject();
                o.put("type", "usb");
                o.put("address", "usb:" + hex4(d.getVendorId()) + ":" + hex4(d.getProductId()));
                String name = Build.VERSION.SDK_INT >= 21 ? (d.getProductName() == null ? "" : d.getProductName()) : "";
                String maker = Build.VERSION.SDK_INT >= 21 ? (d.getManufacturerName() == null ? "" : d.getManufacturerName()) : "";
                o.put("name", (maker + " " + name).trim().isEmpty() ? "USB device " + hex4(d.getVendorId()) + ":" + hex4(d.getProductId()) : (maker + " " + name).trim());
                o.put("printer_class", isPrinterClass(d));
                o.put("permission", um.hasPermission(d));
                out.put(o);
            }
        } catch (Exception ignored) { }
        try {
            for (BluetoothDevice b : bonded()) {
                JSONObject o = new JSONObject();
                o.put("type", "bluetooth");
                o.put("address", "bt:" + b.getAddress());
                o.put("name", safeName(b));
                out.put(o);
            }
        } catch (Exception ignored) { }
        for (String s : SERIAL_CANDIDATES) {
            File f = new File(s);
            if (!f.exists()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("type", "serial");
                o.put("address", s);
                o.put("name", "Serial port " + s + (f.canWrite() ? "" : " (no access)"));
                o.put("writable", f.canWrite());
                out.put(o);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /* ----------------------------------------------------------------- TCP */

    private void tcp(String host, int port, byte[] data) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setSoTimeout(10000);
            OutputStream os = s.getOutputStream();
            os.write(data);
            os.flush();
            Thread.sleep(150); // let the printer drain before closing (impact printers are slow)
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("Printer at " + host + ":" + port + " did not answer. Check it is switched on and on the same network.");
        } catch (java.net.ConnectException e) {
            throw new Exception("Printer at " + host + ":" + port + " refused the connection.");
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /* ----------------------------------------------------------------- USB */

    private static String hex4(int v) { return String.format(Locale.ROOT, "%04x", v & 0xffff); }

    private static boolean isPrinterClass(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        return false;
    }

    /** Bulk OUT endpoint on a printer-like interface (printer, CDC data or vendor class). */
    private static Object[] bulkOut(UsbDevice d, boolean printerOnly) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            int cls = itf.getInterfaceClass();
            boolean ok = cls == UsbConstants.USB_CLASS_PRINTER || (!printerOnly && (cls == UsbConstants.USB_CLASS_CDC_DATA || cls == UsbConstants.USB_CLASS_VENDOR_SPEC));
            if (!ok) continue;
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection() == UsbConstants.USB_DIR_OUT) return new Object[] { itf, ep };
            }
        }
        return null;
    }

    private UsbDevice findUsb(String vidPid, boolean printerOnly) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (vidPid != null) {
                if ((hex4(d.getVendorId()) + ":" + hex4(d.getProductId())).equalsIgnoreCase(vidPid) && bulkOut(d, false) != null) return d;
            } else if (bulkOut(d, printerOnly) != null) {
                return d;
            }
        }
        return null;
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    void requestUsbPermission(UsbDevice d) {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null || um.hasPermission(d)) return;
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        um.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, i, flags));
    }

    /** Ask once for every USB printer we can see (called when the app opens). */
    void requestAllUsbPermissions() {
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return;
        for (UsbDevice d : um.getDeviceList().values()) if (bulkOut(d, false) != null && !um.hasPermission(d)) { requestUsbPermission(d); return; }
    }

    private void usb(String a, byte[] data) throws Exception {
        String id = a.length() > 4 && a.charAt(3) == ':' ? a.substring(4).trim() : null;
        UsbDevice d = findUsb(id, id == null);
        if (d == null && id == null) d = findUsb(null, false);
        if (d == null) throw new Exception(id == null ? "No USB printer is connected to this device." : "USB printer " + id + " is not connected.");
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (!um.hasPermission(d)) {
            requestUsbPermission(d);
            throw new Exception("Allow PBIS POS to use the USB printer (tap OK on the screen), then print again.");
        }
        Object[] io = bulkOut(d, false);
        if (io == null) throw new Exception("That USB device is not a printer.");
        UsbInterface itf = (UsbInterface) io[0];
        UsbEndpoint ep = (UsbEndpoint) io[1];
        UsbDeviceConnection conn = um.openDevice(d);
        if (conn == null) throw new Exception("Could not open the USB printer.");
        try {
            if (!conn.claimInterface(itf, true)) throw new Exception("The USB printer is busy (another app is using it).");
            int chunk = 16384;
            for (int off = 0; off < data.length; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                byte[] part = new byte[len];
                System.arraycopy(data, off, part, 0, len);
                int sent = conn.bulkTransfer(ep, part, len, 10000);
                if (sent < 0) throw new Exception("USB write failed — is the printer out of paper or the cover open?");
            }
            conn.releaseInterface(itf);
        } finally {
            conn.close();
        }
    }

    /* ----------------------------------------------------------- Bluetooth */

    private List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return out;
            out.addAll(ad.getBondedDevices());
        } catch (SecurityException ignored) { }
        return out;
    }

    private static String safeName(BluetoothDevice b) {
        try { String n = b.getName(); return n == null ? b.getAddress() : n; } catch (SecurityException e) { return b.getAddress(); }
    }

    /** Sunmi / iMin and similar terminals expose their printer as a paired "InnerPrinter". */
    private String innerBluetooth() {
        for (BluetoothDevice b : bonded()) {
            String n = safeName(b).toLowerCase(Locale.ROOT);
            if (n.contains("innerprinter") || "00:11:22:33:44:55".equals(b.getAddress())) return b.getAddress();
        }
        return null;
    }

    private void bluetooth(String target, byte[] data) throws Exception {
        BluetoothDevice dev = null;
        for (BluetoothDevice b : bonded()) {
            if (b.getAddress().equalsIgnoreCase(target) || safeName(b).equalsIgnoreCase(target)) { dev = b; break; }
        }
        if (dev == null) throw new Exception("Bluetooth printer " + target + " is not paired. Pair it in Android Settings → Bluetooth first (and turn Bluetooth on).");
        BluetoothSocket sock = null;
        try {
            sock = dev.createRfcommSocketToServiceRecord(SPP);
            try {
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null && bm.getAdapter() != null) bm.getAdapter().cancelDiscovery();
            } catch (SecurityException ignored) { }
            sock.connect();
            OutputStream os = sock.getOutputStream();
            os.write(data);
            os.flush();
            Thread.sleep(Math.min(3000, 200 + data.length / 20)); // SPP has no flush acknowledgement
        } catch (SecurityException e) {
            throw new Exception("Allow PBIS POS to use Bluetooth (Android Settings → Apps → PBIS POS → Permissions → Nearby devices).");
        } finally {
            if (sock != null) try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /* -------------------------------------------------------------- serial */

    private void serial(String path, byte[] data) throws Exception {
        String p = path;
        int at = p.indexOf('@'); // "/dev/ttyS1@115200" — baud is set by the terminal's firmware
        if (at > 0) p = p.substring(0, at);
        File f = new File(p);
        if (!f.exists()) throw new Exception("Serial port " + p + " does not exist on this device.");
        if (!f.canWrite()) throw new Exception("No permission to write to " + p + ". Use the USB or Bluetooth address of the printer instead, or RawBT.");
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
            os.flush();
        }
    }
}
