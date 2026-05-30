package io.vidocq.chappe.api;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * HTTP headers — ordered list of name/value pairs.
 * <p>
 * Name lookup is case-insensitive (RFC 9110, Section 5.1).
 * Duplicate names are allowed.
 */
public interface Headers extends Iterable<Headers.Entry> {

    /** Name/value pair of an HTTP header. */
    record Entry(String name, String value) {}

    /** Returns the first value for the given name, or empty. */
    Optional<String> first(String name);

    /** Returns all values for the given name. */
    List<String> all(String name);

    /** First value or {@code null} — avoids Optional allocation. */
    default String firstOrNull(String name) {
        return first(name).orElse(null);
    }

    /** Checks whether a header with this name is present. */
    boolean contains(String name);

    /** Total number of entries (including duplicates). */
    int size();

    /** {@code true} if there are no entries. */
    default boolean isEmpty() {
        return size() == 0;
    }

    @Override
    Iterator<Entry> iterator();

    /** Creates a builder for constructing {@code Headers}. */
    static Builder builder() {
        return new DefaultHeadersBuilder();
    }

    /** Returns empty headers (singleton). */
    static Headers empty() {
        return DefaultHeaders.EMPTY;
    }

    /** Shortcut for a single header. */
    static Headers of(String name, String value) {
        return builder().add(name, value).build();
    }

    /** Builder for constructing {@code Headers} fluently. */
    interface Builder {

        /** Adds an entry (duplicates are preserved). */
        Builder add(String name, String value);

        /** Replaces all entries with this name with a single value. */
        Builder set(String name, String value);

        /** Builds the immutable {@code Headers}. */
        Headers build();
    }
}
