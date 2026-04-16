package fr.vidocq.chappe.http;

import fr.vidocq.chappe.api.Headers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Implémentation de {@link Headers} sur tableaux parallèles — zero-copy.
 * <p>
 * Enveloppe directement les tableaux internes de {@link HttpRequestImpl}
 * sans copie ni allocation d'objets {@link Entry} (sauf lors de l'itération).
 */
final class ArrayHeaders implements Headers {

    private final String[] names;
    private final String[] values;
    private final int count;

    ArrayHeaders(String[] names, String[] values, int count) {
        this.names = names;
        this.values = values;
        this.count = count;
    }

    @Override
    public Optional<String> first(String name) {
        for (int i = 0; i < count; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return Optional.of(values[i]);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> all(String name) {
        var result = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                result.add(values[i]);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean contains(String name) {
        for (int i = 0; i < count; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public Iterator<Entry> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public Entry next() {
                if (index >= count) throw new NoSuchElementException();
                var entry = new Entry(names[index], values[index]);
                index++;
                return entry;
            }
        };
    }
}
