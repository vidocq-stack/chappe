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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.chappe.api.StatusCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A symlink inside the served root must not expose files outside of it: the canonical
 * (real) path of the resolved file has to stay under the canonical root (security audit F-08).
 */
class StaticFileHandlerSymlinkTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    @Test
    void aRegularFileInsideTheRootIsServed() throws Exception {
        Files.writeString(root.resolve("real.txt"), "public");
        Handler h = StaticFileHandler.builder().addPath(root).build();

        assertEquals(StatusCode.OK, h.handle(req("/real.txt")).status());
    }

    @Test
    void aFileSymlinkPointingOutsideTheRootIsNotServed() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
        Handler h = StaticFileHandler.builder().addPath(root).build();

        assertEquals(StatusCode.NOT_FOUND, h.handle(req("/link.txt")).status());
    }

    @Test
    void aDirectorySymlinkPointingOutsideTheRootIsNotServed() throws Exception {
        Files.createDirectory(outside.resolve("sub"));
        Files.writeString(outside.resolve("sub").resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("dirlink"), outside.resolve("sub"));
        Handler h = StaticFileHandler.builder().addPath(root).build();

        assertEquals(StatusCode.NOT_FOUND, h.handle(req("/dirlink/secret.txt")).status());
    }

    @Test
    void aSymlinkChainEndingOutsideTheRootIsNotServed() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("hop.txt"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("hop.txt"));
        Handler h = StaticFileHandler.builder().addPath(root).build();

        assertEquals(StatusCode.NOT_FOUND, h.handle(req("/link.txt")).status());
    }

    private static Request req(String path) {
        return new Request() {
            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public URI uri() {
                return URI.create("http://test" + path);
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public HttpVersion version() {
                return HttpVersion.HTTP_1_1;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public Body body() {
                return Body.empty();
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParams() {
                return Map.of();
            }
        };
    }
}
