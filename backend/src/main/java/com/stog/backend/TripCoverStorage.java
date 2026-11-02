package com.stog.backend;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TripCoverStorage {
    static final long MAX_INPUT = 8L * 1024 * 1024;
    static final long MAX_DISPLAY = 4L * 1024 * 1024;
    static final long MAX_THUMBNAIL = 512L * 1024;
    static final long MAX_PIXELS = 24_000_000L;
    private static final String DISPLAY = "display.jpg";
    private static final String THUMBNAIL = "thumbnail.jpg";
    private static final String STAGING = ".staging";
    private static final String COMMITTED = ".committed";
    private static final String GC_PENDING = ".gc-pending";

    private final String configuredRoot;
    private Path root;
    private Path tempRoot;
    private Path coversRoot;
    private final DirectoryCursor tempCursor = new DirectoryCursor();
    private final DirectoryCursor coversCursor = new DirectoryCursor();

    public TripCoverStorage(@Value("${stog.media.root:}") String configuredRoot) {
        this.configuredRoot = configuredRoot;
    }

    @PostConstruct
    void initialize() {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException("STOG_MEDIA_ROOT must name an existing private writable directory");
        }
        try {
            Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(configured)) {
                throw invalidRoot();
            }
            for (Path part = configured; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)) throw invalidRoot();
            }
            Path real = configured.toRealPath();
            if (!real.equals(configured) || isResourceTree(real)) throw invalidRoot();
            if (!Files.isWritable(real)) throw invalidRoot();
            Path probe = Files.createTempFile(real, ".stog-media-probe-", ".tmp");
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.delete(probe);
            root = real;
            tempRoot = safeChild(root, "temp");
            coversRoot = safeChild(root, "covers");
            Files.createDirectories(tempRoot);
            Files.createDirectories(coversRoot);
            assertPrivateDirectory(tempRoot);
            assertPrivateDirectory(coversRoot);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw invalidRoot();
        }
    }

    Stage stage(InputStream source, long declaredSize, String declaredMediaType) {
        if (declaredSize > MAX_INPUT) throw tooLarge();
        UUID stagingId = UUID.randomUUID();
        Path directory = safeChild(tempRoot, stagingId.toString());
        Path original = safeChild(directory, "source.bin");
        try {
            Files.createDirectory(directory);
            Files.writeString(safeChild(directory, STAGING), "STAGING", StandardOpenOption.CREATE_NEW);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(source, original, digest);
            if (size == 0) throw invalidCover();
            validateContainer(original, declaredMediaType);
            Decoded decoded = decode(original, declaredMediaType);
            BufferedImage display = scale(decoded.image(), 2048);
            BufferedImage thumbnail = scale(decoded.image(), 512);
            Path displayFile = safeChild(directory, DISPLAY);
            Path thumbnailFile = safeChild(directory, THUMBNAIL);
            writeJpeg(display, displayFile);
            writeJpeg(thumbnail, thumbnailFile);
            long displaySize = Files.size(displayFile);
            long thumbnailSize = Files.size(thumbnailFile);
            if (displaySize < 1 || displaySize > MAX_DISPLAY || thumbnailSize < 1 || thumbnailSize > MAX_THUMBNAIL) {
                throw tooLarge();
            }
            force(displayFile);
            force(thumbnailFile);
            Files.delete(original);
            return new Stage(directory, digest.digest(), decoded.format(), decoded.width(), decoded.height(),
                displaySize, display.getWidth(), display.getHeight(), thumbnailSize,
                thumbnail.getWidth(), thumbnail.getHeight(), null);
        } catch (TripCoverException exception) {
            deleteOwned(directory);
            throw exception;
        } catch (Exception exception) {
            deleteOwned(directory);
            throw storageFailed();
        }
    }

    Stage commitStage(Stage stage, UUID storageKey) {
        Path destination = coverDirectory(storageKey);
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw storageFailed();
            Files.move(stage.directory(), destination, StandardCopyOption.ATOMIC_MOVE);
            return stage.withFinalDirectory(destination);
        } catch (IOException exception) {
            throw storageFailed();
        }
    }

    void discard(Stage stage) {
        if (stage.finalDirectory() == null) deleteOwned(stage.directory());
        else deleteFinalDirectory(stage.finalDirectory());
    }

    void markCommitted(UUID storageKey) {
        replaceMarker(coverDirectory(storageKey), STAGING, COMMITTED, "COMMITTED");
    }

    void markGcPending(UUID storageKey) {
        Path directory = coverDirectory(storageKey);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.deleteIfExists(safeChild(directory, STAGING));
            Files.deleteIfExists(safeChild(directory, COMMITTED));
            Files.writeString(safeChild(directory, GC_PENDING), "GC_PENDING",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Reconciliation retries marker transition; the committed database result is not reversed.
        }
    }

    byte[] read(UUID storageKey, boolean thumbnail, long expectedSize) {
        Path directory = coverDirectory(storageKey);
        Path file = safeChild(directory, thumbnail ? THUMBNAIL : DISPLAY);
        long cap = thumbnail ? MAX_THUMBNAIL : MAX_DISPLAY;
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw storageFailed();
            long actual = Files.size(file);
            if (actual != expectedSize || actual < 1 || actual > cap) throw storageFailed();
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] bytes = input.readNBytes(Math.toIntExact(cap + 1));
                if (bytes.length != actual || input.read() != -1) throw storageFailed();
                return bytes;
            }
        } catch (IOException exception) {
            throw storageFailed();
        }
    }

    synchronized ReconcileBatch reconciliationBatch(Instant cutoff, int maximumVisited) {
        if (maximumVisited < 1) throw new IllegalArgumentException("maximumVisited must be positive");
        int tempLimit = Math.max(1, maximumVisited / 2);
        ScanBatch temp = scan(tempRoot, tempCursor, tempLimit);
        cleanupTempEntries(temp.entries(), cutoff);
        ScanBatch finals = scan(coversRoot, coversCursor, maximumVisited - temp.visited());
        return new ReconcileBatch(storedDirectories(finals.entries()), temp.visited() + finals.visited());
    }

    synchronized List<StoredDirectory> finalDirectories() {
        return storedDirectories(scan(coversRoot, coversCursor, 100).entries());
    }

    void deleteFinal(UUID storageKey) {
        deleteFinalDirectory(coverDirectory(storageKey));
    }

    private static void deleteFinalDirectory(Path directory) {
        if (ownedMarker(directory)) deleteOwned(directory);
    }

    synchronized void cleanupOldTemp(Instant cutoff) {
        cleanupTempEntries(scan(tempRoot, tempCursor, 100).entries(), cutoff);
    }

    @PreDestroy
    synchronized void closeDirectoryScans() {
        tempCursor.close();
        coversCursor.close();
    }

    private ScanBatch scan(Path directory, DirectoryCursor cursor, int limit) {
        if (limit <= 0) return new ScanBatch(List.of(), 0);
        try {
            cursor.open(directory);
            List<Path> entries = new ArrayList<>();
            while (entries.size() < limit && cursor.hasNext()) entries.add(cursor.next());
            if (!cursor.hasNext()) cursor.close();
            return new ScanBatch(entries, entries.size());
        } catch (IOException exception) {
            cursor.close();
            throw storageFailed();
        }
    }

    private static void cleanupTempEntries(List<Path> entries, Instant cutoff) {
        for (Path child : entries) {
            try {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) continue;
                try { UUID.fromString(child.getFileName().toString()); }
                catch (IllegalArgumentException ignored) { continue; }
                FileTime modified = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS);
                if (modified.toInstant().isBefore(cutoff)
                        && Files.exists(safeChild(child, STAGING), LinkOption.NOFOLLOW_LINKS)) deleteOwned(child);
            } catch (IOException exception) {
                throw storageFailed();
            }
        }
    }

    private static List<StoredDirectory> storedDirectories(List<Path> entries) {
        List<StoredDirectory> result = new ArrayList<>();
        for (Path child : entries) {
            try {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) continue;
                UUID key;
                try { key = UUID.fromString(child.getFileName().toString()); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!ownedMarker(child)) continue;
                result.add(new StoredDirectory(key, child,
                    Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS)));
            } catch (IOException exception) {
                throw storageFailed();
            }
        }
        return result;
    }

    private Decoded decode(Path source, String declaredMediaType) {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(source.toFile())) {
            if (imageInput == null) throw invalidCover();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw invalidCover();
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, false, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String expected = "image/png".equals(declaredMediaType) ? "png" : "jpeg";
                if (!(format.equals(expected) || ("jpeg".equals(expected) && format.equals("jpg")))) throw invalidCover();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192
                        || Math.multiplyExact((long) width, (long) height) > MAX_PIXELS) throw tooLarge();
                int frames;
                try { frames = reader.getNumImages(true); }
                catch (IOException exception) { throw invalidCover(); }
                if (frames != 1) throw invalidCover();
                BufferedImage sourceImage = reader.read(0);
                if (sourceImage == null || sourceImage.getWidth() != width || sourceImage.getHeight() != height) {
                    throw invalidCover();
                }
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                    graphics.drawImage(sourceImage, 0, 0, null);
                } finally { graphics.dispose(); }
                return new Decoded(rgb, expected, width, height);
            } finally { reader.dispose(); }
        } catch (TripCoverException exception) {
            throw exception;
        } catch (ArithmeticException | IOException exception) {
            throw invalidCover();
        }
    }

    private static BufferedImage scale(BufferedImage source, int longestEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) longestEdge / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        if (targetWidth == width && targetHeight == height) return source;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally { graphics.dispose(); }
        return target;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam parameter = writer.getDefaultWriteParam();
            parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameter.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), parameter);
            output.flush();
        } finally { writer.dispose(); }
    }

    private static long copyBounded(InputStream input, Path target, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                total += read;
                if (total > MAX_INPUT) throw tooLarge();
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return total;
    }

    private static void validateContainer(Path source, String mediaType) {
        try {
            if ("image/png".equals(mediaType)) validatePng(source);
            else validateJpeg(source);
        } catch (TripCoverException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidCover();
        }
    }

    private static void validateJpeg(Path source) throws IOException {
        long size = Files.size(source);
        if (size < 4) throw invalidCover();
        try (InputStream input = Files.newInputStream(source)) {
            if (input.read() != 0xff || input.read() != 0xd8) throw invalidCover();
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            java.nio.ByteBuffer tail = java.nio.ByteBuffer.allocate(2);
            channel.position(size - 2);
            if (channel.read(tail) != 2) throw invalidCover();
            tail.flip();
            if ((tail.get() & 0xff) != 0xff || (tail.get() & 0xff) != 0xd9) throw invalidCover();
        }
    }

    private static void validatePng(Path source) throws IOException {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            for (byte expected : signature) if (input.readByte() != expected) throw invalidCover();
            boolean first = true;
            boolean ended = false;
            while (!ended) {
                int length;
                try { length = input.readInt(); } catch (EOFException exception) { throw invalidCover(); }
                if (length < 0 || length > MAX_INPUT) throw invalidCover();
                byte[] type = input.readNBytes(4);
                if (type.length != 4) throw invalidCover();
                String name = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
                if (first && !"IHDR".equals(name)) throw invalidCover();
                if ("acTL".equals(name) || "fcTL".equals(name) || "fdAT".equals(name)) throw invalidCover();
                byte[] data = input.readNBytes(length);
                if (data.length != length) throw invalidCover();
                int storedCrc = input.readInt();
                CRC32 crc = new CRC32();
                crc.update(type);
                crc.update(data);
                if ((int) crc.getValue() != storedCrc) throw invalidCover();
                if ("IEND".equals(name)) {
                    if (length != 0) throw invalidCover();
                    ended = true;
                }
                first = false;
            }
            if (input.read() != -1) throw invalidCover();
        }
    }

    private void replaceMarker(Path directory, String from, String to, String value) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                throw storageFailed();
            }
            Path marker = safeChild(directory, to);
            Files.writeString(marker, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(safeChild(directory, from));
        } catch (IOException exception) {
            throw storageFailed();
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private Path coverDirectory(UUID storageKey) { return safeChild(coversRoot, storageKey.toString()); }

    private static Path safeChild(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!child.getParent().equals(parent) || name.contains("/") || name.contains("\\")) throw storageFailed();
        return child;
    }

    private static boolean ownedMarker(Path directory) {
        return Files.exists(safeChild(directory, STAGING), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(safeChild(directory, COMMITTED), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(safeChild(directory, GC_PENDING), LinkOption.NOFOLLOW_LINKS);
    }

    private static void deleteOwned(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Owned temporary/orphan cleanup is retried by reconciliation.
        }
    }

    private static void assertPrivateDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)
                || !Files.isWritable(directory)) throw invalidRoot();
    }

    private static boolean isResourceTree(Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/src/main/resources/") || normalized.endsWith("/src/main/resources")
            || normalized.contains("/build/resources/") || normalized.endsWith("/build/resources");
    }

    private static IllegalStateException invalidRoot() {
        return new IllegalStateException("STOG_MEDIA_ROOT must name an existing private writable directory");
    }
    private static TripCoverException invalidCover() {
        return new TripCoverException(400, "INVALID_COVER", "The cover image is invalid");
    }
    private static TripCoverException tooLarge() {
        return new TripCoverException(413, "COVER_TOO_LARGE", "The cover image is too large");
    }
    private static TripCoverException storageFailed() {
        return new TripCoverException(503, "MEDIA_STORAGE_FAILED", "The cover image could not be stored");
    }

    record Stage(Path directory, byte[] inputSha256, String format, int inputWidth, int inputHeight,
        long displaySize, int displayWidth, int displayHeight, long thumbnailSize,
        int thumbnailWidth, int thumbnailHeight, Path finalDirectory) {
        Stage withFinalDirectory(Path value) { return new Stage(directory, inputSha256, format, inputWidth, inputHeight,
            displaySize, displayWidth, displayHeight, thumbnailSize, thumbnailWidth, thumbnailHeight, value); }
    }
    record StoredDirectory(UUID storageKey, Path path, FileTime modifiedAt) {}
    record ReconcileBatch(List<StoredDirectory> finalDirectories, int visitedEntries) {}
    private record ScanBatch(List<Path> entries, int visited) {}
    private record Decoded(BufferedImage image, String format, int width, int height) {}

    private static final class DirectoryCursor {
        private DirectoryStream<Path> stream;
        private Iterator<Path> iterator;

        void open(Path directory) throws IOException {
            if (stream == null) {
                stream = Files.newDirectoryStream(directory);
                iterator = stream.iterator();
            }
        }
        boolean hasNext() { return iterator != null && iterator.hasNext(); }
        Path next() { return iterator.next(); }
        void close() {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
            stream = null;
            iterator = null;
        }
    }
}
