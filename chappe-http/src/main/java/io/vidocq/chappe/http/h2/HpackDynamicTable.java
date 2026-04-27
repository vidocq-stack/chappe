package io.vidocq.chappe.http.h2;

/**
 * HPACK Dynamic Table — RFC 7541 §2.3.2
 *
 * <p>Maintains a FIFO ring buffer of (name, value) header entries. Entries are evicted from the
 * oldest end when the cumulative size would exceed {@code maxSize}. The size of each entry is
 * defined as {@code name.length() + value.length() + 32} per RFC 7541 §4.1.
 *
 * <p>Indexing is 1-based: index 1 is the most recently added entry (newest).
 */
public final class HpackDynamicTable {

    private static final int ENTRY_OVERHEAD = 32;
    private static final int INITIAL_CAPACITY = 16;

    private String[] names;
    private String[] values;

    /** Points to the next insertion slot (wraps around). */
    private int head;

    /** Number of valid entries currently stored. */
    private int count;

    /** Current sum of all entry sizes (name.len + value.len + 32). */
    private int currentSize;

    /** Maximum allowed cumulative size (bytes). */
    private int maxSize;

    /**
     * Creates a new dynamic table with the given maximum size.
     *
     * @param maxSize maximum cumulative entry size in bytes (RFC 7541 §4.2)
     */
    public HpackDynamicTable(int maxSize) {
        this.maxSize = maxSize;
        this.names = new String[INITIAL_CAPACITY];
        this.values = new String[INITIAL_CAPACITY];
        this.head = 0;
        this.count = 0;
        this.currentSize = 0;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the header name at the given 1-based index (1 = newest entry).
     *
     * @param index 1-based index
     * @return the header name
     * @throws IndexOutOfBoundsException if {@code index < 1} or {@code index > count()}
     */
    public String name(int index) {
        checkIndex(index);
        return names[slot(index)];
    }

    /**
     * Returns the header value at the given 1-based index (1 = newest entry).
     *
     * @param index 1-based index
     * @return the header value
     * @throws IndexOutOfBoundsException if {@code index < 1} or {@code index > count()}
     */
    public String value(int index) {
        checkIndex(index);
        return values[slot(index)];
    }

    /**
     * Returns the number of entries currently stored in the table.
     *
     * @return entry count
     */
    public int count() {
        return count;
    }

    /**
     * Adds a header entry to the dynamic table.
     *
     * <p>If the entry size alone exceeds {@code maxSize}, the table is emptied and the entry is
     * <em>not</em> added (RFC 7541 §4.4).
     *
     * <p>Otherwise, oldest entries are evicted until the new entry fits, then the entry is
     * prepended so that index 1 points to it.
     *
     * @param name  header name (must not be {@code null})
     * @param value header value (must not be {@code null})
     */
    public void add(String name, String value) {
        int entrySize = entrySize(name, value);

        if (entrySize > maxSize) {
            // RFC 7541 §4.4: empty the table, do not add the entry
            clear();
            return;
        }

        // Evict oldest entries until there is room
        while (count > 0 && currentSize + entrySize > maxSize) {
            evictOldest();
        }

        // Grow the backing arrays if the ring is full
        if (count == names.length) {
            grow();
        }

        // Insert at 'head'; advance head
        names[head] = name;
        values[head] = value;
        head = (head + 1) % names.length;
        count++;
        currentSize += entrySize;
    }

    /**
     * Updates the maximum table size and evicts entries as needed (RFC 7541 §4.3).
     *
     * @param newMax new maximum cumulative size in bytes
     */
    public void setMaxSize(int newMax) {
        this.maxSize = newMax;
        while (count > 0 && currentSize > maxSize) {
            evictOldest();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a 1-based logical index to a physical array slot.
     *
     * <p>Index 1 is the most recently inserted entry, which sits at {@code head - 1}.
     * Index {@code count} is the oldest entry.
     *
     * <p>Formula: {@code slot = Math.floorMod(head - index, capacity)}
     */
    private int slot(int index) {
        return Math.floorMod(head - index, names.length);
    }

    /** Evicts the oldest entry (highest logical index). */
    private void evictOldest() {
        if (count == 0) {
            return;
        }
        // The oldest entry occupies slot(count)
        int oldestSlot = slot(count);
        currentSize -= entrySize(names[oldestSlot], values[oldestSlot]);
        names[oldestSlot] = null;
        values[oldestSlot] = null;
        count--;
    }

    /**
     * Doubles the capacity of the ring buffer, re-laying entries in logical order
     * (oldest → newest) starting at index 0 so that {@code head == count} after growth.
     */
    private void grow() {
        int oldCap = names.length;
        int newCap = oldCap * 2;
        String[] newNames = new String[newCap];
        String[] newValues = new String[newCap];

        // Copy in logical order: oldest (index=count) first, newest (index=1) last
        for (int i = 1; i <= count; i++) {
            // destination: (count - i) so oldest lands at 0, newest at count-1
            int dest = count - i;
            int src = slot(i);
            newNames[dest] = names[src];
            newValues[dest] = values[src];
        }

        names = newNames;
        values = newValues;
        // After re-layout, slots 0..count-1 hold entries (oldest..newest).
        // Next insertion goes to slot 'count'.
        head = count;
    }

    /** Removes all entries from the table. */
    private void clear() {
        for (int i = 0; i < names.length; i++) {
            names[i] = null;
            values[i] = null;
        }
        head = 0;
        count = 0;
        currentSize = 0;
    }

    /** Returns the RFC 7541 size of a single entry. */
    private static int entrySize(String name, String value) {
        return name.length() + value.length() + ENTRY_OVERHEAD;
    }

    private void checkIndex(int index) {
        if (index < 1 || index > count) {
            throw new IndexOutOfBoundsException(
                    "Dynamic table index " + index + " out of bounds [1, " + count + "]");
        }
    }
}
