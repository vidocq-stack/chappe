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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The architecture rules on the classes Chappe ships: chappe-api, chappe-http and chappe-core, test classes
 * left out (Vidocq/vidocq#289). A failure names each class and the dependency or call that breaks the rule.
 */
class ArchitectureRulesTest {

    private static final JavaClasses SHIPPED = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.vidocq.chappe.api", "io.vidocq.chappe.http", "io.vidocq.chappe.core");

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.chappe.core.ChappeServer"), "chappe-core imported");
        assertTrue(SHIPPED.contain("io.vidocq.chappe.http.HttpConnection"), "chappe-http imported");
        assertTrue(SHIPPED.contain("io.vidocq.chappe.api.Server"), "chappe-api imported");
    }

    @Test
    void theShippedClassesDependOnTheJdkOnly() {
        ArchitectureRules.JDK_ONLY.check(SHIPPED);
    }

    @Test
    void theShippedClassesUseNoReflection() {
        ArchitectureRules.NO_REFLECTION.check(SHIPPED);
    }

    @Test
    void theShippedClassesCreateNoPlatformThread() {
        ArchitectureRules.NO_PLATFORM_THREADS.check(SHIPPED);
    }

    @Test
    void theShippedClassesKeepNoThreadLocal() {
        ArchitectureRules.NO_THREAD_LOCAL.check(SHIPPED);
    }

    @Test
    void theModulesKeepTheirLayers() {
        ArchitectureRules.LAYERS.check(SHIPPED);
    }
}
