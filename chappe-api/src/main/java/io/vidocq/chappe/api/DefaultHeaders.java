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
package io.vidocq.chappe.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link Headers}.
 * <p>
 * Flat-array storage for cache-friendly iteration.
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
