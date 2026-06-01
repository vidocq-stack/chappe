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
