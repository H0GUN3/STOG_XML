package com.stog.app.trip;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Deterministic preparation failure and mirrored-orientation regression checks. */
@RunWith(AndroidJUnit4.class)
public final class CoverInputTest {
    @Test public void exifReadFailureDeletesCopiedPrivateFileAndBitmap() throws Exception {
        Context context = ApplicationProvider.getApplicationContext(); File root = new File(context.getCacheDir(), "r2b-cover-input-red"); clear(root); root.mkdirs(); File source = jpeg(context, "r2b-cover-input-source.jpg", 4, 2);
        try {
            try { CoverInput.copyAndPreview(context.getContentResolver(), Uri.fromFile(source), "image/jpeg", root, ignored -> { throw new IOException("deterministic exif failure"); }); fail("Expected exif failure"); }
            catch (IOException expected) { assertEquals("deterministic exif failure", expected.getMessage()); }
            File[] files = root.listFiles(); assertTrue("failed preparation must not retain private copy", files == null || files.length == 0);
        } finally { source.delete(); clear(root); }
    }

    @Test public void mirroredExifOrientationsMoveAsymmetricPixelsToExactCorners() throws Exception {
        Context context = ApplicationProvider.getApplicationContext(); File root = new File(context.getCacheDir(), "r2b-cover-orientation"); clear(root); root.mkdirs(); File source = asymmetricPng(context, "r2b-cover-orientation-source.png");
        try {
            assertOrientation(context, root, source, androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                3, 2, new int[]{0xff0000ff, 0xffff0000, 0xffffff00, 0xffffffff});
            assertOrientation(context, root, source, androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL,
                3, 2, new int[]{0xffffffff, 0xffffff00, 0xffff0000, 0xff0000ff});
            assertOrientation(context, root, source, androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE,
                2, 3, new int[]{0xffff0000, 0xffffffff, 0xff0000ff, 0xffffff00});
            assertOrientation(context, root, source, androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE,
                2, 3, new int[]{0xffffff00, 0xff0000ff, 0xffffffff, 0xffff0000});
        } finally { source.delete(); clear(root); }
    }

    private static void assertOrientation(Context context, File root, File source, int orientation,
            int width, int height, int[] corners) throws Exception {
        CoverInput.Prepared prepared = null;
        try {
            prepared = CoverInput.copyAndPreview(context.getContentResolver(), Uri.fromFile(source), "image/png", root, ignored -> orientation);
            Bitmap preview = prepared.preview(); assertEquals(width, preview.getWidth()); assertEquals(height, preview.getHeight());
            assertColor(corners[0], preview.getPixel(0, 0)); assertColor(corners[1], preview.getPixel(width - 1, 0));
            assertColor(corners[2], preview.getPixel(0, height - 1)); assertColor(corners[3], preview.getPixel(width - 1, height - 1));
        } finally { if (prepared != null) { prepared.preview().recycle(); prepared.file().delete(); } }
    }

    private static File asymmetricPng(Context context, String name) throws IOException {
        File file = new File(context.getCacheDir(), name); Bitmap bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff, 0xff000000, 0xffffff00}, 0, 3, 0, 0, 3, 2);
        try (FileOutputStream output = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot create fixture"); }
        finally { bitmap.recycle(); }
        return file;
    }

    private static void assertColor(int expected, int actual) {
        assertTrue("expected color " + Integer.toHexString(expected) + " but was " + Integer.toHexString(actual),
            Math.abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)) <= 8
                && Math.abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)) <= 8
                && Math.abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)) <= 8);
    }

    private static File jpeg(Context context, String name, int width, int height) throws IOException {
        File file = new File(context.getCacheDir(), name); Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565); bitmap.eraseColor(0xff335f53);
        try (FileOutputStream output = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException("Cannot create fixture"); }
        finally { bitmap.recycle(); }
        return file;
    }
    private static void clear(File directory) { File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete(); }
}
