package io.vidocq.chappe.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Implémentation par défaut de {@link Headers.Builder}.
 */
final class DefaultHeadersBuilder implements Headers.Builder {

    private final List<Headers.Entry> entries = new ArrayList<>();

    @Override
    public Headers.Builder add(String name, String value) {
        entries.add(new Headers.Entry(name, value));
        return this;
    }

    @Override
    public Headers.Builder set(String name, String value) {
        entries.removeIf(e -> e.name().equalsIgnoreCase(name));
        entries.add(new Headers.Entry(name, value));
        return this;
    }

    @Override
    public Headers build() {
        if (entries.isEmpty()) {
            return DefaultHeaders.EMPTY;
        }
        return new DefaultHeaders(List.copyOf(entries));
    }
}
