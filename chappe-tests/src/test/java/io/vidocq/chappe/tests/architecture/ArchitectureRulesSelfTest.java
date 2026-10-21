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
package io.vidocq.chappe.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import io.vidocq.chappe.api.archfixture.ApiReachingIntoCore;
import io.vidocq.chappe.core.ChappeServerProvider;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Each architecture rule catches what it forbids: a rule that passes on the shipped classes proves nothing
 * unless it fails on a class that breaks it. The fixtures below each break one rule.
 */
class ArchitectureRulesSelfTest {

    @Test
    void theJdkOnlyRuleCatchesALibraryDependency() {
        assertViolated(ArchitectureRules.JDK_ONLY, UsesALibrary.class);
        assertKept(ArchitectureRules.JDK_ONLY, UsesTheJdkOnly.class);
    }

    @Test
    void theReflectionRuleCatchesAClassLookupByName() {
        assertViolated(ArchitectureRules.NO_REFLECTION, LooksUpAClassByName.class);
        assertViolated(ArchitectureRules.NO_REFLECTION, ReadsAMethodReflectively.class);
        assertKept(ArchitectureRules.NO_REFLECTION, UsesTheJdkOnly.class);
    }

    @Test
    void theVirtualThreadRuleCatchesPlatformThreadPoolsAndThreads() {
        assertViolated(ArchitectureRules.NO_PLATFORM_THREADS, UsesAFixedThreadPool.class);
        assertViolated(ArchitectureRules.NO_PLATFORM_THREADS, BuildsAThreadPoolExecutor.class);
        assertViolated(ArchitectureRules.NO_PLATFORM_THREADS, StartsAPlatformThread.class);
        assertKept(ArchitectureRules.NO_PLATFORM_THREADS, UsesVirtualThreads.class);
    }

    @Test
    void theThreadLocalRuleCatchesAThreadLocal() {
        assertViolated(ArchitectureRules.NO_THREAD_LOCAL, KeepsAThreadLocal.class);
        assertKept(ArchitectureRules.NO_THREAD_LOCAL, UsesVirtualThreads.class);
    }

    @Test
    void theLayerRuleCatchesTheApiReachingIntoTheEngine() {
        assertViolated(ArchitectureRules.LAYERS, ApiReachingIntoCore.class, ChappeServerProvider.class);
    }

    private static void assertViolated(ArchRule rule, Class<?>... classes) {
        assertTrue(
                rule.evaluate(new ClassFileImporter().importClasses(classes)).hasViolation(),
                () -> "expected a violation of: " + rule.getDescription());
    }

    private static void assertKept(ArchRule rule, Class<?>... classes) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(classes));
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    static final class UsesALibrary {
        void check() {
            org.junit.jupiter.api.Assertions.fail("a library outside the JDK");
        }
    }

    static final class UsesTheJdkOnly {
        String join(java.util.List<String> parts) {
            return String.join(",", parts);
        }
    }

    static final class LooksUpAClassByName {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    }

    static final class ReadsAMethodReflectively {
        Object method() throws NoSuchMethodException {
            return String.class.getDeclaredMethod("length");
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(
                    1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
        }
    }

    static final class UsesVirtualThreads {
        ExecutorService executor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    static final class KeepsAThreadLocal {
        private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

        StringBuilder buffer() {
            return BUFFER.get();
        }
    }
}
