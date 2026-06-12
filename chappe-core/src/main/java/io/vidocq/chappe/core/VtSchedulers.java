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
package io.vidocq.chappe.core;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;

import io.vidocq.chappe.api.ChappeException;

/**
 * Factory for the virtual-thread executor that runs server connections.
 *
 * <p>Diagnostic hook for the 100k-ceiling study (see BENCHMARKS.md,
 * BENCH-20260612-01): the {@code chappe.bench.vtScheduler} system property
 * swaps the default ForkJoinPool virtual-thread scheduler for an experimental
 * one, through the JDK-internal {@code ThreadBuilders$VirtualThreadBuilder}
 * constructor (boot-time reflection only — requires
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}).
 *
 * <p>Supported values:
 * <ul>
 *   <li>(unset) — default JDK scheduler (ForkJoinPool, work-stealing)</li>
 *   <li>{@code fifo:N} — N platform carriers, one shared FIFO queue, blocking take</li>
 *   <li>{@code spin:N:M} — same, but carriers busy-spin up to M microseconds
 *       before parking — tests whether the awaitWork park/unpark oscillation
 *       is the tail-latency driver</li>
 * </ul>
 */
final class VtSchedulers {

    private VtSchedulers() {}

    static ExecutorService newServerExecutor() {
        String spec = System.getProperty("chappe.bench.vtScheduler");
        if (spec == null || spec.isBlank()) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        String[] parts = spec.split(":");
        int carriers = Integer.parseInt(parts[1]);
        long spinNanos = parts[0].equals("spin") ? Long.parseLong(parts[2]) * 1_000L : 0L;
        Executor scheduler = new SpinningCarrierPool(carriers, spinNanos);
        try {
            var ctor = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
                    .getDeclaredConstructor(Executor.class);
            ctor.setAccessible(true);
            var builder = (Thread.Builder.OfVirtual) ctor.newInstance(scheduler);
            ThreadFactory factory = builder.name("chappe-vt-", 0).factory();
            return Executors.newThreadPerTaskExecutor(factory);
        } catch (ReflectiveOperationException e) {
            throw new ChappeException.ServerException(
                    "chappe.bench.vtScheduler=" + spec
                            + " needs --add-opens java.base/java.lang=ALL-UNNAMED (JDK 25 internal API)",
                    e);
        }
    }

    /**
     * Minimal carrier pool: N daemon platform threads draining one
     * {@link LinkedTransferQueue} (lock-free poll, efficient blocking take).
     * Optional busy-spin window before parking, mimicking what event loops
     * effectively do between bursts.
     */
    private static final class SpinningCarrierPool implements Executor {

        private final LinkedTransferQueue<Runnable> queue = new LinkedTransferQueue<>();

        SpinningCarrierPool(int carriers, long spinNanos) {
            for (int i = 0; i < carriers; i++) {
                Thread t = new Thread(() -> runCarrier(spinNanos), "chappe-carrier-" + i);
                t.setDaemon(true);
                t.start();
            }
        }

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        private void runCarrier(long spinNanos) {
            while (true) {
                Runnable task = queue.poll();
                if (task == null && spinNanos > 0) {
                    long deadline = System.nanoTime() + spinNanos;
                    while (task == null && System.nanoTime() - deadline < 0) {
                        Thread.onSpinWait();
                        task = queue.poll();
                    }
                }
                if (task == null) {
                    try {
                        task = queue.take();
                    } catch (InterruptedException _) {
                        return;
                    }
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    // a continuation must never kill its carrier
                    Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
                }
            }
        }
    }
}
