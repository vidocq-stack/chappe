/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.vidocq.chappe.api.Headers;

/**
 * {@link Headers} implementation backed by parallel arrays — zero-copy.
 * <p>
 * Directly wraps the internal arrays of {@link HttpRequestImpl}
 * without copying or allocating {@link Entry} objects (except during iteration).
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
        var v = firstOrNull(name);
        return v != null ? Optional.of(v) : Optional.empty();
    }

    @Override
    public String firstOrNull(String name) {
        for (int i = 0; i < count; i++) {
            if (names[i].equalsIgnoreCase(name)) {
                return values[i];
            }
        }
        return null;
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
