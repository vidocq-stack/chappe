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

import java.util.Map;

import io.vidocq.archrules.Layer;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Chappe's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Chappe's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 */
final class ArchitectureRules {

    /** CLAUDE.md, "Zero dependency": nothing outside the JDK in what Chappe ships. */
    static final ArchRule JDK_ONLY = VidocqArchRules.dependOnlyOn(
            new String[0], new String[] {"java..", "javax..", "io.vidocq.chappe.."}, "CLAUDE.md, Zero dependency");

    /**
     * Workspace CLAUDE.md, "Static code generation": what would be reflection at run time is generated at build
     * time. Chappe has no documented reflective fallback.
     */
    static final ArchRule NO_REFLECTION =
            VidocqArchRules.noReflection(Map.of(), "workspace CLAUDE.md, static code generation");

    /**
     * CLAUDE.md, Standards: "All code uses virtual threads, never use a classic thread pool". A platform thread
     * created one by one breaks the same rule.
     */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "CLAUDE.md, Standards");

    /**
     * CHAPPE-037 (BUG.md): each virtual thread has thread-locals of its own, so a cache kept in a ThreadLocal is
     * never shared; the buffer pool kept that way served no second connection.
     */
    static final ArchRule NO_THREAD_LOCAL =
            VidocqArchRules.noThreadLocal("a ThreadLocal caches nothing across virtual threads", "BUG.md, CHAPPE-037");

    /**
     * CLAUDE.md, Modules: chappe-api is the public API, chappe-http implements the protocols on it, chappe-core
     * runs the server on both. Neither the API nor the protocols reach into the engine, and the API knows no
     * protocol class.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Modules",
            Layer.named("API").in("io.vidocq.chappe.api.."),
            Layer.named("HTTP").in("io.vidocq.chappe.http..").accessibleOnlyBy("Core"),
            Layer.named("Core").in("io.vidocq.chappe.core..").accessibleByNone());

    private ArchitectureRules() {}
}
