package com.pbisllc.pos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;

/** PNG receipt image → printer raster commands (for image-only jobs and raster-only printers). */
final class Raster {
    private Raster() { }

    static byte[] fromPng(byte[] png, boolean star, int maxDots) throws Exception {
        Bitmap src = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (src == null) throw new Exception("Could not read the receipt image");
        int dots = maxDots > 0 ? maxDots : 576;
        Bitmap bmp = src.getWidth() > dots ? Bitmap.createScaledBitmap(src, dots, Math.max(1, Math.round(src.getHeight() * (dots / (float) src.getWidth()))), true) : src;
        int w = bmp.getWidth(), h = bmp.getHeight(), bw = (w + 7) / 8;
        byte[][] rows = new byte[h][bw];
        int[] line = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(line, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = line[x];
                int a = Color.alpha(c);
                double lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
                lum = 255 - (255 - lum) * (a / 255.0);
                if (lum < 128) rows[y][x >> 3] |= (byte) (0x80 >> (x & 7));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (star) {
            out.write(new byte[] { 0x1b, 0x2a, 0x72, 0x52 });           // raster mode start
            out.write(new byte[] { 0x1b, 0x2a, 0x72, 0x41 });
            out.write("\u001b*rE13\u0000".getBytes("ISO-8859-1"));        // EOT: feed + cut
            for (byte[] r : rows) { out.write(new byte[] { 0x62, (byte) (bw & 255), (byte) (bw >> 8) }); out.write(r); }
            out.write(new byte[] { 0x1b, 0x0c, 0x00 });
            out.write(new byte[] { 0x1b, 0x2a, 0x72, 0x42 });
        } else {
            out.write(new byte[] { 0x1b, 0x40 });
            for (int y = 0; y < h; y += 255) {
                int n = Math.min(255, h - y);
                out.write(new byte[] { 0x1d, 0x76, 0x30, 0x00, (byte) (bw & 255), (byte) (bw >> 8), (byte) (n & 255), (byte) (n >> 8) });
                for (int i = 0; i < n; i++) out.write(rows[y + i]);
            }
            out.write(new byte[] { 0x1b, 0x64, 0x04, 0x1d, 0x56, 0x42, 0x00 });
        }
        return out.toByteArray();
    }
}
