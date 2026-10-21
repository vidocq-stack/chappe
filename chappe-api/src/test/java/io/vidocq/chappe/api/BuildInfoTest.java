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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The build identity reads {@code chappe-build.properties}, which Maven filters at build time. A value the
 * build could not fill keeps its placeholder: without git-commit-id, the commit read
 * {@code ${git.commit.id.abbrev}} and the {@code Server} header showed it (CHAPPE-038).
 */
class BuildInfoTest {

    @Test
    void aValueTheBuildLeftUnfilteredIsUnknown() {
        var properties = new Properties();
        properties.setProperty("chappe.git.commit", "${git.commit.id.abbrev}");
        assertEquals("unknown", BuildInfo.value(properties, "chappe.git.commit"));
    }

    @Test
    void aMissingValueIsUnknown() {
        assertEquals("unknown", BuildInfo.value(new Properties(), "chappe.git.commit"));
    }

    @Test
    void aFilteredValueIsKept() {
        var properties = new Properties();
        properties.setProperty("chappe.git.commit", "8d670fb0");
        assertEquals("8d670fb0", BuildInfo.value(properties, "chappe.git.commit"));
    }
}
