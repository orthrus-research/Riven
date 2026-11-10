package cc.orthrus.riven.worldgen;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Flat little-endian primitives for derived regional files, with detached arrays on read. */
public final class RegionalData {
    private static final ValueLayout.OfInt INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private RegionalData() {}

    /** A malformed entry is a cache miss; a codec's programming errors are not. */
    public static final class Malformed extends Exception {
        private static final long serialVersionUID = 1;

        public Malformed(String reason) {
            super(reason);
        }
    }

    public static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        public void integer(int value) {
            for (int shift = 0; shift < 32; shift += 8) bytes.write(value >>> shift);
        }

        public void longValue(long value) {
            for (int shift = 0; shift < 64; shift += 8) bytes.write((int) (value >>> shift));
        }

        public void bool(boolean value) {
            bytes.write(value ? 1 : 0);
        }

        public void doubleValue(double value) {
            longValue(Double.doubleToRawLongBits(value));
        }

        public void floatValue(float value) {
            integer(Float.floatToRawIntBits(value));
        }

        public void raw(byte[] values) {
            bytes.writeBytes(values);
        }

        public void bytes(byte[] values) {
            integer(values == null ? -1 : values.length);
            if (values != null) raw(values);
        }

        public void integers(int[] values) {
            integer(values == null ? -1 : values.length);
            if (values != null) for (int value : values) integer(value);
        }

        public void longs(long[] values) {
            integer(values == null ? -1 : values.length);
            if (values != null) for (long value : values) longValue(value);
        }

        public void doubles(double[] values) {
            integer(values == null ? -1 : values.length);
            if (values != null) for (double value : values) doubleValue(value);
        }

        public void floats(float[] values) {
            integer(values == null ? -1 : values.length);
            if (values != null) for (float value : values) floatValue(value);
        }

        public byte[] finish() {
            return bytes.toByteArray();
        }
    }

    /** Never exposes the mapped segment: codecs can retain only copied primitives and arrays. */
    public static final class Reader {
        private final MemorySegment bytes;
        private long position;

        Reader(MemorySegment bytes) {
            this.bytes = bytes;
        }

        public long remaining() {
            return bytes.byteSize() - position;
        }

        public int integer() throws Malformed {
            require(Integer.BYTES);
            int value = bytes.get(INT, position);
            position += Integer.BYTES;
            return value;
        }

        public long longValue() throws Malformed {
            require(Long.BYTES);
            long value = bytes.get(LONG, position);
            position += Long.BYTES;
            return value;
        }

        public boolean bool() throws Malformed {
            require(1);
            byte value = bytes.get(ValueLayout.JAVA_BYTE, position++);
            if (value != 0 && value != 1) throw new Malformed("Invalid boolean");
            return value != 0;
        }

        public double doubleValue() throws Malformed {
            return Double.longBitsToDouble(longValue());
        }

        public float floatValue() throws Malformed {
            return Float.intBitsToFloat(integer());
        }

        public byte[] bytes() throws Malformed {
            int count = count(1);
            return count < 0 ? null : take(count).toArray(ValueLayout.JAVA_BYTE);
        }

        public int[] integers() throws Malformed {
            int count = count(Integer.BYTES);
            if (count < 0) return null;
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = integer();
            return values;
        }

        public long[] longs() throws Malformed {
            int count = count(Long.BYTES);
            if (count < 0) return null;
            long[] values = new long[count];
            for (int i = 0; i < count; i++) values[i] = longValue();
            return values;
        }

        public double[] doubles() throws Malformed {
            int count = count(Double.BYTES);
            if (count < 0) return null;
            double[] values = new double[count];
            for (int i = 0; i < count; i++) values[i] = doubleValue();
            return values;
        }

        public float[] floats() throws Malformed {
            int count = count(Float.BYTES);
            if (count < 0) return null;
            float[] values = new float[count];
            for (int i = 0; i < count; i++) values[i] = floatValue();
            return values;
        }

        public void requireEnd() throws Malformed {
            if (remaining() != 0) throw new Malformed("Trailing entry bytes");
        }

        void expect(byte[] expected) throws Malformed {
            int count = integer();
            if (count != expected.length) throw new Malformed("Entry identity length");
            require(count);
            for (byte value : expected)
                if (bytes.get(ValueLayout.JAVA_BYTE, position++) != value)
                    throw new Malformed("Entry identity mismatch");
        }

        byte[] raw(int count) throws Malformed {
            return take(count).toArray(ValueLayout.JAVA_BYTE);
        }

        long position() {
            return position;
        }

        private MemorySegment take(long count) throws Malformed {
            require(count);
            MemorySegment result = bytes.asSlice(position, count);
            position += count;
            return result;
        }

        private int count(int width) throws Malformed {
            int count = integer();
            if (count < -1 || count > remaining() / width)
                throw new Malformed("Array length outside entry");
            return count;
        }

        private void require(long count) throws Malformed {
            if (count < 0 || count > remaining()) throw new Malformed("Truncated entry");
        }
    }
}
