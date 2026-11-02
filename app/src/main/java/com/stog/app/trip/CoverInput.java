package com.stog.app.trip;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Foreground-only copy and sampled preview of one user-selected document. */
final class CoverInput {
    static final long MAX_BYTES = 8L * 1024L * 1024L;
    record Prepared(File file, String mediaType, String sha256, Bitmap preview) {}

    private CoverInput() { }

    interface OrientationReader { int orientation(File file) throws IOException; }
    private static final OrientationReader EXIF_ORIENTATION = file -> { try (FileInputStream input = new FileInputStream(file)) { return new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); } };

    static Prepared copyAndPreview(ContentResolver resolver, Uri uri, String mediaType, File directory) throws IOException { return copyAndPreview(resolver, uri, mediaType, directory, EXIF_ORIENTATION); }
    static Prepared copyAndPreview(ContentResolver resolver, Uri uri, String mediaType, File directory, OrientationReader orientationReader) throws IOException {
        if (!("image/jpeg".equals(mediaType) || "image/png".equals(mediaType))) throw new IOException("Unsupported image type");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Private directory unavailable");
        File target = new File(directory, "cover-" + UUID.randomUUID());
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
        Bitmap preview = null;
        try {
            try (InputStream input = resolver.openInputStream(uri); FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IOException("Selected document unavailable");
                byte[] buffer = new byte[8192]; long total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("Cover exceeds limit");
                    digest.update(buffer, 0, read); output.write(buffer, 0, read);
                }
                if (total == 0) throw new IOException("Empty cover");
            }
            preview = sampledPreview(target, orientationReader);
            if (preview == null) throw new IOException("Image cannot be decoded");
            return new Prepared(target, mediaType, hex(digest.digest()), preview);
        } catch (IOException | RuntimeException exception) {
            if (preview != null && !preview.isRecycled()) preview.recycle();
            target.delete();
            throw exception;
        }
    }

    private static Bitmap sampledPreview(File file, OrientationReader orientationReader) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inSampleSize = sample; decode.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), decode);
        if (bitmap == null) return null;
        try {
            int orientation = orientationReader.orientation(file);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
                case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
                case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
                case ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90); matrix.postScale(-1, 1); }
                case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
                case ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90); matrix.postScale(-1, 1); }
                case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270);
                default -> { return bitmap; }
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (IOException | RuntimeException exception) {
            if (!bitmap.isRecycled()) bitmap.recycle();
            throw exception;
        }
    }

    private static String hex(byte[] value) { StringBuilder text = new StringBuilder(value.length * 2); for (byte item : value) text.append(String.format(java.util.Locale.ROOT, "%02x", item)); return text.toString(); }
}
