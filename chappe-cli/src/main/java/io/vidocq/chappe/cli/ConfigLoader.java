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
package io.vidocq.chappe.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.vidocq.chappe.cli.yaml.YamlReader;

/** Loads a {@link ChappeConfig} from a YAML file on disk. */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Reads the UTF-8 file and maps it to a {@code ChappeConfig}. */
    public static ChappeConfig load(Path file) throws IOException {
        String content = Files.readString(file);
        return ChappeConfig.from(YamlReader.parse(content));
    }
}
