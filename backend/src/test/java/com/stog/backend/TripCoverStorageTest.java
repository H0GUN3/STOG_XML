package com.stog.backend;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripCoverStorageTest {
    @TempDir Path temporary;

    @Test
    void missingAndRepositoryResourceRootsFailClosed() throws Exception {
        assertThrows(IllegalStateException.class, () -> initialized(""));
        assertThrows(IllegalStateException.class, () -> initialized(temporary.resolve("missing").toString()));
        Path resources = temporary.resolve("src/main/resources/private");
        Files.createDirectories(resources);
        assertThrows(IllegalStateException.class, () -> initialized(resources.toString()));
    }

    @Test
    void pngIsReencodedAsFreshBoundedJpegWithWhiteAlphaAndNoSourceMetadata() throws Exception {
        TripCoverStorage storage = initialized(temporary.toString());
        BufferedImage source = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00000000);
        source.setRGB(source.getWidth() - 1, 0, Color.RED.getRGB());
        byte[] png = pngWithText(source, "GPS", "synthetic-location");

        TripCoverStorage.Stage staged = storage.stage(new ByteArrayInputStream(png), png.length, "image/png");
        try {
            assertEquals(3000, staged.inputWidth());
            assertEquals(1500, staged.inputHeight());
            assertEquals(2048, staged.displayWidth());
            assertEquals(1024, staged.displayHeight());
            assertEquals(512, staged.thumbnailWidth());
            assertEquals(256, staged.thumbnailHeight());
            assertFalse(Files.exists(staged.directory().resolve("source.bin")));
            byte[] displayBytes = Files.readAllBytes(staged.directory().resolve("display.jpg"));
            byte[] thumbnailBytes = Files.readAllBytes(staged.directory().resolve("thumbnail.jpg"));
            assertTrue(displayBytes.length <= TripCoverStorage.MAX_DISPLAY);
            assertTrue(thumbnailBytes.length <= TripCoverStorage.MAX_THUMBNAIL);
            assertFalse(new String(displayBytes, StandardCharsets.ISO_8859_1).contains("synthetic-location"));
            assertFalse(new String(thumbnailBytes, StandardCharsets.ISO_8859_1).contains("GPS"));
            BufferedImage display = ImageIO.read(new ByteArrayInputStream(displayBytes));
            Color flattened = new Color(display.getRGB(0, 0));
            assertTrue(flattened.getRed() > 235 && flattened.getGreen() > 235 && flattened.getBlue() > 235);
        } finally {
            storage.discard(staged);
        }
    }

    @Test
    void cleanupVisitsAtMostOneHundredEntriesIncludingUnownedAndInvalidNames() throws Exception {
        TripCoverStorage storage = initialized(temporary.toString());
        Path temp = temporary.resolve("temp");
        for (int index = 0; index < 40; index++) {
            Path invalid = temp.resolve("invalid-" + index);
            Files.createDirectory(invalid);
            Files.writeString(invalid.resolve(".staging"), "STAGING");
            Files.setLastModifiedTime(invalid, java.nio.file.attribute.FileTime.fromMillis(0));
        }
        for (int index = 0; index < 40; index++) {
            Path unowned = temp.resolve(UUID.randomUUID().toString());
            Files.createDirectory(unowned);
            Files.setLastModifiedTime(unowned, java.nio.file.attribute.FileTime.fromMillis(0));
        }
        for (int index = 0; index < 160; index++) {
            Path owned = temp.resolve(UUID.randomUUID().toString());
            Files.createDirectory(owned);
            Files.writeString(owned.resolve(".staging"), "STAGING");
            Files.setLastModifiedTime(owned, java.nio.file.attribute.FileTime.fromMillis(0));
        }

        TripCoverStorage.ReconcileBatch batch = storage.reconciliationBatch(Instant.now(), 100);

        assertEquals(50, batch.visitedEntries(), "invalid and unowned entries must consume the bounded visit budget");
        try (var remaining = Files.list(temp)) {
            assertTrue(remaining.count() >= 140, "one pass must visit no more than 100 of 240 total entries");
        }
    }

    @Test
    void finalTraversalContinuesPastAnUnchangedFirstBatch() throws Exception {
        TripCoverStorage storage = initialized(temporary.toString());
        Path covers = temporary.resolve("covers");
        Set<UUID> expected = new HashSet<>();
        for (int index = 0; index < 101; index++) {
            UUID key = UUID.randomUUID();
            expected.add(key);
            Path directory = covers.resolve(key.toString());
            Files.createDirectory(directory);
            Files.writeString(directory.resolve(".committed"), "COMMITTED");
        }

        Set<UUID> visited = new HashSet<>();
        storage.finalDirectories().forEach(directory -> visited.add(directory.storageKey()));
        storage.finalDirectories().forEach(directory -> visited.add(directory.storageKey()));

        assertEquals(expected, visited, "a retained first batch must not starve later directories");
    }

    @Test
    void rejectsMimeMismatchCorruptTrailingPolyglotAnimationAndHeaderBounds() throws Exception {
        TripCoverStorage storage = initialized(temporary.toString());
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);
        byte[] png = output.toByteArray();

        assertCode("INVALID_COVER", () -> storage.stage(new ByteArrayInputStream(png), png.length, "image/jpeg"));
        byte[] corrupt = png.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertCode("INVALID_COVER", () -> storage.stage(new ByteArrayInputStream(corrupt), corrupt.length, "image/png"));
        byte[] trailing = Arrays.copyOf(png, png.length + 4);
        trailing[trailing.length - 1] = 'X';
        assertCode("INVALID_COVER", () -> storage.stage(new ByteArrayInputStream(trailing), trailing.length, "image/png"));
        byte[] animated = injectChunkBeforeIend(png, "acTL", new byte[] {0, 0, 0, 1, 0, 0, 0, 0});
        assertCode("INVALID_COVER", () -> storage.stage(new ByteArrayInputStream(animated), animated.length, "image/png"));
        byte[] overDimension = pngHeaderOnly(8193, 1);
        assertCode("COVER_TOO_LARGE", () -> storage.stage(new ByteArrayInputStream(overDimension),
            overDimension.length, "image/png"));
        byte[] overPixels = pngHeaderOnly(6000, 5000);
        assertCode("COVER_TOO_LARGE", () -> storage.stage(new ByteArrayInputStream(overPixels),
            overPixels.length, "image/png"));
        assertCode("COVER_TOO_LARGE", () -> storage.stage(new ByteArrayInputStream(new byte[1]),
            TripCoverStorage.MAX_INPUT + 1, "image/png"));
        try (var remaining = Files.list(temporary.resolve("temp"))) {
            assertEquals(0, remaining.count());
        }
    }

    private TripCoverStorage initialized(String path) {
        TripCoverStorage storage = new TripCoverStorage(path);
        storage.initialize();
        return storage;
    }

    private static byte[] pngWithText(BufferedImage image, String key, String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] data = (key + "\0" + value).getBytes(StandardCharsets.ISO_8859_1);
        return injectChunkBeforeIend(output.toByteArray(), "tEXt", data);
    }

    private static byte[] injectChunkBeforeIend(byte[] png, String type, byte[] data) throws Exception {
        int iend = png.length - 12;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, iend);
        writeChunk(output, type, data);
        output.write(png, iend, 12);
        return output.toByteArray();
    }

    private static byte[] pngHeaderOnly(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(header)) {
            data.writeInt(width); data.writeInt(height); data.writeByte(8); data.writeByte(2);
            data.writeByte(0); data.writeByte(0); data.writeByte(0);
        }
        writeChunk(output, "IHDR", header.toByteArray());
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws Exception {
        DataOutputStream writer = new DataOutputStream(output);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writer.writeInt(data.length);
        writer.write(typeBytes);
        writer.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes); crc.update(data);
        writer.writeInt((int) crc.getValue());
    }

    private static void assertCode(String expected, Throwing action) {
        TripCoverException exception = assertThrows(TripCoverException.class, action::run);
        assertEquals(expected, exception.code());
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
