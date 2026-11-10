package cc.orthrus.riven.worldgen;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/** Caller-driven, deletable derived files below a directory supplied by the world host. */
public final class RegionalStore implements AutoCloseable {
    private static final long MAGIC = 0x3147524e45564952L;
    private static final int FORMAT = 1, CHECKSUM_BYTES = 32;
    // A flat body is one Java byte array; larger files are outside this format.
    private static final long MAX_FILE_BYTES = Integer.MAX_VALUE;
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_-]{0,47}");

    public interface Codec<T> {
        int version();

        void write(RegionalData.Writer out, T value);

        T read(RegionalData.Reader in) throws RegionalData.Malformed;
    }

    public record Statistics(long hits, long misses, long writes, long failedWrites) {}

    private final Path directory;
    private final RegionalIdentity identity;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();
    private final AtomicLong writes = new AtomicLong(), failedWrites = new AtomicLong();
    private volatile boolean closed;

    /** The host chooses the external root; construction does not create a directory. */
    public RegionalStore(Path hostDirectory, RegionalIdentity identity) {
        this.identity = Objects.requireNonNull(identity);
        directory =
                Objects.requireNonNull(hostDirectory)
                        .resolve("riven-regionals-v1")
                        .resolve(identity.namespace);
    }

    /** Returns detached immutable data, or null for an absent, stale, corrupt or unreadable entry. */
    public <T> T get(String kind, long x, long z, Codec<T> codec) {
        Path file = path(kind, x, z, codec);
        lifetime.readLock().lock();
        try {
            if (closed) return miss();
            try {
                T value = read(file, kind, x, z, codec);
                hits.incrementAndGet();
                return value;
            } catch (IOException | RegionalData.Malformed invalid) {
                return miss();
            }
        } finally {
            lifetime.readLock().unlock();
        }
    }

    /** Writes on the caller, never changes an existing file in place, and reports cache I/O failure. */
    public <T> boolean put(String kind, long x, long z, T value, Codec<T> codec) {
        Path file = path(kind, x, z, codec);
        if (closed) return failedWrite();
        RegionalData.Writer values = new RegionalData.Writer();
        codec.write(values, Objects.requireNonNull(value));
        byte[] body = values.finish();
        RegionalData.Writer header = new RegionalData.Writer();
        header.longValue(MAGIC);
        header.integer(FORMAT);
        header.integer(codec.version());
        header.longValue(x);
        header.longValue(z);
        header.bytes(kind.getBytes(StandardCharsets.UTF_8));
        identity.write(header);
        header.integer(body.length);
        header.raw(checksum(ByteBuffer.wrap(body)));
        byte[] prefix = header.finish();
        if ((long) prefix.length + body.length > MAX_FILE_BYTES) {
            failedWrites.incrementAndGet();
            return false;
        }
        lifetime.readLock().lock();
        Path temporary = null;
        try {
            if (closed) return failedWrite();
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "entry-", ".part");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                write(channel, prefix);
                write(channel, body);
                channel.force(true);
            }
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            writes.incrementAndGet();
            return true;
        } catch (IOException unavailable) {
            // Windows may refuse replacement while another reader maps the old entry.
            return failedWrite();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException unavailable) {
                    // Only this writer's unused temporary may remain; readers never open it.
                }
            }
            lifetime.readLock().unlock();
        }
    }

    public Statistics statistics() {
        return new Statistics(hits.get(), misses.get(), writes.get(), failedWrites.get());
    }

    /** Waits only for file operations already in progress; later operations are misses or skipped writes. */
    @Override
    public void close() {
        lifetime.writeLock().lock();
        try {
            closed = true;
        } finally {
            lifetime.writeLock().unlock();
        }
    }

    Path path(String kind, long x, long z, Codec<?> codec) {
        if (!KIND.matcher(kind).matches() || codec.version() < 1)
            throw new IllegalArgumentException(
                    "A regional kind and positive codec version are required");
        return directory
                .resolve(kind + "-" + codec.version())
                .resolve(
                        Long.toUnsignedString(x, 16) + "-" + Long.toUnsignedString(z, 16) + ".bin");
    }

    private <T> T miss() {
        misses.incrementAndGet();
        return null;
    }

    private boolean failedWrite() {
        failedWrites.incrementAndGet();
        return false;
    }

    private <T> T read(Path file, String kind, long x, long z, Codec<T> codec)
            throws IOException, RegionalData.Malformed {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
                Arena arena = Arena.ofConfined()) {
            long size = channel.size();
            if (size < 4L * Long.BYTES || size > MAX_FILE_BYTES)
                throw new RegionalData.Malformed("Entry size outside format");
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            RegionalData.Reader in = new RegionalData.Reader(mapped);
            if (in.longValue() != MAGIC
                    || in.integer() != FORMAT
                    || in.integer() != codec.version()
                    || in.longValue() != x
                    || in.longValue() != z)
                throw new RegionalData.Malformed("Entry format or key mismatch");
            in.expect(kind.getBytes(StandardCharsets.UTF_8));
            identity.verify(in);
            int bodyBytes = in.integer();
            byte[] checksum = in.raw(CHECKSUM_BYTES);
            if (bodyBytes < 0 || bodyBytes != in.remaining())
                throw new RegionalData.Malformed("Entry body length mismatch");
            MemorySegment body = mapped.asSlice(in.position(), bodyBytes);
            if (!MessageDigest.isEqual(checksum, checksum(body.asByteBuffer())))
                throw new RegionalData.Malformed("Entry checksum mismatch");
            RegionalData.Reader values = new RegionalData.Reader(body);
            T value = Objects.requireNonNull(codec.read(values), "A regional codec returned null");
            values.requireEnd();
            return value;
        }
    }

    private static byte[] checksum(ByteBuffer body) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(body);
            return hash.digest();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
