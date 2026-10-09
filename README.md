# PBIS POS for Android

A full-screen PBIS POS / KDS / customer-display app for Android tablets and Android
POS terminals (Landi C20 Pro, Sunmi T2/D2/V2, iMin, PAX, Elo…) with the **PBIS Print
Bridge built in**. It prints to:

| Printer | Address to enter in PBIS |
|---|---|
| The terminal's own printer (Landi C20 Pro, Sunmi, iMin…) | `builtin` |
| Network / Wi-Fi printer (e.g. kitchen TM-U220B on 192.168.0.150) | `192.168.0.150` (port 9100) |
| USB printer plugged into the tablet/terminal | `usb` or `usb:04b8:0202` |
| Paired Bluetooth printer | `bt:00:11:22:33:44:55` |
| Serial port printer | `/dev/ttyS1` |

In PBIS set each printer's **Connection** to **“PBIS Print Bridge — this device (PC or Android app)”**.
The app listens on `http://127.0.0.1:17788` exactly like the desktop bridge, so the
same printer set-up works on Windows, Mac, Linux and Android.

Optional: paste a **Print Bridge token** (Back office → Settings → Printers) in the app
settings and the terminal also prints jobs sent from other devices — e.g. iPads and the
KDS printing to the kitchen printer connected to this terminal (connection
“PBIS Print Bridge — cloud queue”).

## Install on a device

1. Get the APK (see *Build* below) and copy it to the device, or download it in the device's browser.
2. Allow “Install unknown apps” for the browser / file manager when Android asks.
3. Open **PBIS POS**, enter your page (e.g. `https://pos.your-site.com/pos/`), tap **Save and open**.
4. Allow notifications and “Nearby devices” (Bluetooth) when asked. When a USB printer is detected Android asks to allow access — tick *Always* and **OK**.
5. In the app settings → *Printers on this device* tap **Test** next to each printer, then **Use as built-in** for the one inside the terminal.

Open the settings again any time from POS → Device → **Android app settings**.

## Build

No Android Studio needed — let GitHub build it:

1. Create a private GitHub repository and upload the contents of this folder.
2. GitHub runs **Actions → Build PBIS POS Android app** automatically.
3. Open the finished run and download the **pbis-pos-android** artifact (the APK).

Or with Android Studio: *Open* this folder → *Build → Build APK(s)*.
Command line (Android SDK + Gradle 8.7+ installed): `gradle assembleRelease`.

The APK is signed with a debug key unless you provide a keystore through the
`PBIS_KEYSTORE`, `PBIS_KEYSTORE_PASSWORD`, `PBIS_KEY_ALIAS` and `PBIS_KEY_PASSWORD`
environment variables (or GitHub secrets). Use your own key for Play Store / MDM.

## Without the app

On Android you can also print from Chrome:

* **RawBT** (Google Play) — connection “RawBT app”. RawBT drives the built-in printers of many
  terminals plus USB, Bluetooth and network printers.
* **USB direct** — connection “USB direct” for USB printers (Chrome's WebUSB).
* **Epson ePOS / Star CloudPRNT** — network printers that need no app at all.
