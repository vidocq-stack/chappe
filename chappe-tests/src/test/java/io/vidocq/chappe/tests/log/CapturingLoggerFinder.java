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
package io.vidocq.chappe.tests.log;

import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test {@link System.LoggerFinder} (registered via {@code META-INF/services}) that records every
 * {@link System.Logger} call into a static, thread-safe buffer. This is the deterministic way to
 * assert on {@code System.Logger} output regardless of the JDK's default JUL routing — used to prove
 * the WebSocket layer logs handler errors instead of swallowing them (see {@code WebSocketEchoTest}).
 *
 * <p>Installing a {@code LoggerFinder} is JVM-global, so it captures all {@code System.Logger} usage
 * across the chappe-tests run; assertions filter by the specific record they expect.</p>
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    public record Entry(String logger, System.Logger.Level level, String message, Throwable thrown) {}

    public static final CopyOnWriteArrayList<Entry> RECORDS = new CopyOnWriteArrayList<>();

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    private record RecordingLogger(String name) implements System.Logger {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Object... params) {
            RECORDS.add(new Entry(name, level, msg, null));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new Entry(name, level, msg, thrown));
        }
    }
}
