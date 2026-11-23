package com.opms.presence;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Turns the camera app's photo into a small, upright JPEG data URL, the same size the web page would send. */
final class SelfieProcessor {
    static final int MAX_SIDE = 800;

    private SelfieProcessor() {}

    static String toDataUrl(File file) throws IOException {
        if (file == null || !file.exists() || file.length() == 0) throw new IOException("The camera did not save a photo.");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("The photo could not be read.");

        // Decode at roughly twice the final size, then scale down smoothly.
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (decoded == null) throw new IOException("The photo could not be read.");

        Matrix m = new Matrix();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(decoded.getWidth(), decoded.getHeight()));
        m.postScale(scale, scale);
        int rotation = exifRotation(file);
        if (rotation != 0) m.postRotate(rotation);

        Bitmap out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        if (out != decoded) decoded.recycle();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 88, bytes);
        out.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    private static int exifRotation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
