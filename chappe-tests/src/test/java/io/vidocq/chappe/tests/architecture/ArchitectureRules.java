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

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Chappe's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). Each one names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the
 * shipped classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 */
final class ArchitectureRules {

    /** CLAUDE.md, "Zero dependency": nothing outside the JDK in what Chappe ships. */
    static final ArchRule JDK_ONLY = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "javax..", "io.vidocq.chappe..")
            .because("Chappe ships no dependency outside the JDK (CLAUDE.md, Zero dependency)");

    /**
     * Workspace CLAUDE.md, "Static code generation": what would be reflection at run time is generated at build
     * time. A class looked up by name, or a member read reflectively, is what this rule catches.
     */
    static final ArchRule NO_REFLECTION = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.reflect..")
            .orShould()
            .callMethodWhere(
                    target(owner(type(Class.class)))
                            .and(
                                    target(
                                            nameMatching(
                                                    "forName|newInstance|getDeclared.*|getMethods?|getFields?|getConstructors?|getRecordComponents"))))
            .because("Chappe uses no reflection at run time (workspace CLAUDE.md, static code generation)");

    /**
     * CLAUDE.md, Standards: "All code uses virtual threads, never use a classic thread pool". A platform thread
     * created one by one breaks the same rule.
     */
    static final ArchRule NO_PLATFORM_THREADS = noClasses()
            .should()
            .callMethodWhere(target(owner(type(Executors.class)))
                    .and(target(nameMatching("newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor"
                            + "|newScheduledThreadPool|newSingleThreadScheduledExecutor|newWorkStealingPool"))))
            .orShould()
            .callConstructorWhere(
                    target(owner(assignableTo(ThreadPoolExecutor.class).or(assignableTo(ForkJoinPool.class)))))
            .orShould()
            .callMethod(Thread.class, "ofPlatform")
            .orShould()
            .callConstructorWhere(target(owner(type(Thread.class))))
            .because("Chappe runs every task on a virtual thread (CLAUDE.md, Standards)");

    /**
     * CHAPPE-037 (BUG.md): each virtual thread has thread-locals of its own, so a cache kept in a ThreadLocal is
     * never shared; the buffer pool kept that way served no second connection.
     */
    static final ArchRule NO_THREAD_LOCAL = noClasses()
            .should()
            .dependOnClassesThat()
            .areAssignableTo(ThreadLocal.class)
            .because("a ThreadLocal caches nothing across virtual threads (BUG.md, CHAPPE-037)");

    /**
     * CLAUDE.md, Modules: chappe-api is the public API, chappe-http implements the protocols on it, chappe-core
     * runs the server on both. Neither the API nor the protocols reach into the engine, and the API knows no
     * protocol class.
     */
    static final ArchRule LAYERS = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("API")
            .definedBy("io.vidocq.chappe.api..")
            .layer("HTTP")
            .definedBy("io.vidocq.chappe.http..")
            .layer("Core")
            .definedBy("io.vidocq.chappe.core..")
            .whereLayer("Core")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("HTTP")
            .mayOnlyBeAccessedByLayers("Core")
            .because("chappe-api, chappe-http and chappe-core are layers, in that order (CLAUDE.md, Modules)");

    private ArchitectureRules() {}
}
