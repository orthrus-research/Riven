package cc.orthrus.riven.worldgen;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * Bounded memo of one immutable value per site cell. Each cell may sit in either of two slots
 * picked by independent bits of its hash; a miss fills an empty one of the two, else replaces the
 * older entry, so entries no longer read age out and two cells that share one slot settle into
 * their other slots instead of evicting each other on every alternate read. Values must be pure
 * functions of the cell, so eviction or a concurrent miss only recomputes the same value; that
 * value may be a rebuilt copy, so callers compare values by their cell, never by reference.
 */
public final class CellMemo<T> {
    private static final class Entry<T> {
        final int x, z;
        final T value;
        final long stored = System.nanoTime();

        Entry(int x, int z, T value) {
            this.x = x;
            this.z = z;
            this.value = value;
        }
    }

    private final AtomicReferenceArray<Entry<T>> slots;
    private final SharedBuild<T> builds = new SharedBuild<>();
    private final int mask;

    public CellMemo(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0)
            throw new IllegalArgumentException("Cell memo capacity must be a power of two");
        slots = new AtomicReferenceArray<>(capacity);
        mask = capacity - 1;
    }

    public T get(int x, int z, Supplier<T> compute) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL), h = SeededNoise.mix(key);
        int a = (int) h & mask, b = (int) (h >>> 32) & mask;
        Entry<T> held = find(x, z, a, b);
        if (held != null) return held.value;
        // A miss that raced a build ending finds its value on the second look.
        return builds.get(
                key,
                () -> {
                    Entry<T> e = find(x, z, a, b);
                    return e != null ? e.value : compute.get();
                },
                value -> store(x, z, a, b, value));
    }

    private Entry<T> find(int x, int z, int a, int b) {
        Entry<T> e = slots.get(a);
        if (e != null && e.x == x && e.z == z) return e;
        Entry<T> f = slots.get(b);
        return f != null && f.x == x && f.z == z ? f : null;
    }

    /**
     * Holds a built value before its build lets go of the cell. The slots as they stand after the
     * build (other threads may have filled them meanwhile, this cell among them): an empty one of
     * the two, else the older entry's.
     */
    private void store(int x, int z, int a, int b, T value) {
        Entry<T> e = slots.get(a), f = slots.get(b);
        if ((e != null && e.x == x && e.z == z) || (f != null && f.x == x && f.z == z)) return;
        Entry<T> entry = new Entry<>(x, z, value);
        if (e == null) slots.set(a, entry);
        else if (f == null) slots.set(b, entry);
        else slots.set(e.stored - f.stored <= 0 ? a : b, entry);
    }

    /** Whether a cell's value is held now (timing only: it may be evicted at any moment). */
    public boolean holds(int x, int z) {
        long h = SeededNoise.mix(((long) x << 32) ^ (z & 0xffffffffL));
        Entry<T> e = slots.get((int) h & mask), f = slots.get((int) (h >>> 32) & mask);
        return (e != null && e.x == x && e.z == z) || (f != null && f.x == x && f.z == z);
    }
}
