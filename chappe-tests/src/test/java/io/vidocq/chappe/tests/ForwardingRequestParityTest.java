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
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import io.vidocq.chappe.api.ForwardingRequest;
import io.vidocq.chappe.api.Request;

import org.junit.jupiter.api.Test;

/**
 * Structural guard for {@link ForwardingRequest}: every method of
 * {@link Request} must be redeclared (forwarded) by {@code ForwardingRequest}.
 *
 * <p>Anonymous {@code Request} wrappers silently fell back to interface
 * defaults each time a method was added to {@code Request} after they were
 * written — {@code attribute()} (CASSINI-002), then
 * {@code trailers()}/{@code onDisconnect()} through {@code Router.mount}
 * (foy BUG-20260611-01). This test turns the next occurrence into a build
 * failure: add a method to {@code Request} and this fails until
 * {@code ForwardingRequest} forwards it.</p>
 */
class ForwardingRequestParityTest {

    @Test
    void everyRequestMethodIsForwarded() {
        var missing = new ArrayList<String>();
        for (Method m : Request.class.getMethods()) {
            if (m.isSynthetic() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            try {
                ForwardingRequest.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }
        assertTrue(
                missing.isEmpty(),
                "ForwardingRequest must forward every Request method; missing: " + missing
                        + " — a wrapped transport (mount, harness, adapter) would silently lose it");
    }
}
