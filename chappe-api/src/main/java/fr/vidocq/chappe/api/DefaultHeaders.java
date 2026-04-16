package fr.vidocq.chappe.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Implémentation par défaut de {@link Headers}.
 * <p>
 * Stockage en tableau plat pour un parcours cache-friendly.
 */
final class DefaultHeaders implements Headers {

    static final Headers EMPTY = new DefaultHeaders(List.of());

    private final List<Entry> entries;

    DefaultHeaders(List<Entry> entries) {
        this.entries = entries;
    }

    @Override
    public Optional<String> first(String name) {
        for (var entry : entries) {
            if (entry.name().equalsIgnoreCase(name)) {
                return Optional.of(entry.value());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> all(String name) {
        var result = new ArrayList<String>();
        for (var entry : entries) {
            if (entry.name().equalsIgnoreCase(name)) {
                result.add(entry.value());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean contains(String name) {
        for (var entry : entries) {
            if (entry.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public Iterator<Entry> iterator() {
        return entries.iterator();
    }
}
