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
package io.vidocq.chappe.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Utility for sending raw HTTP requests via Socket
 * and parsing responses. Used for RFC conformance tests.
 */
final class RawHttp {

    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    private RawHttp() {}

    /**
     * Opens a socket, sends the raw request, reads the complete response, and closes it.
     */
    static String sendAndReceive(int port, String rawRequest) {
        return sendAndReceive(port, rawRequest, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Opens a socket, sends the raw request, reads the complete response, and closes it.
     *
     * @param port       server port
     * @param rawRequest raw HTTP request (with \r\n)
     * @param timeoutMs  SO_TIMEOUT in milliseconds
     * @return the raw HTTP response
     */
    static String sendAndReceive(int port, String rawRequest, int timeoutMs) {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(timeoutMs);
            write(socket.getOutputStream(), rawRequest);
            socket.shutdownOutput();
            return readAll(socket.getInputStream(), timeoutMs);
        } catch (IOException e) {
            throw new RuntimeException("Failed to send/receive on port " + port, e);
        }
    }

    /**
     * Opens a persistent socket (keep-alive). The caller is responsible for closing it.
     */
    static Socket openConnection(int port, int timeoutMs) {
        try {
            var socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(timeoutMs);
            return socket;
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to port " + port, e);
        }
    }

    /**
     * Sends a request on an already open socket and reads a response.
     */
    static String sendAndReceiveOnSocket(Socket socket, String rawRequest) {
        try {
            write(socket.getOutputStream(), rawRequest);
            return readResponse(socket.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("Failed to send/receive on existing socket", e);
        }
    }

    /**
     * Extracts the HTTP status code from the status line.
     * Example: "HTTP/1.1 200 OK" -> 200
     */
    static int extractStatusCode(String response) {
        if (response == null || response.isEmpty()) {
            throw new IllegalArgumentException("Empty response");
        }
        // Status line: HTTP/1.1 200 OK
        int firstSpace = response.indexOf(' ');
        if (firstSpace < 0) {
            throw new IllegalArgumentException(
                    "Malformed status line: " + response.lines().findFirst().orElse(""));
        }
        int secondSpace = response.indexOf(' ', firstSpace + 1);
        int endOfCode = secondSpace > 0 ? secondSpace : response.indexOf('\r', firstSpace + 1);
        if (endOfCode < 0) {
            endOfCode = response.indexOf('\n', firstSpace + 1);
        }
        String codeStr = response.substring(firstSpace + 1, endOfCode).trim();
        return Integer.parseInt(codeStr);
    }

    /**
     * Extracts the response body (everything after the first \r\n\r\n).
     */
    static String extractBody(String response) {
        int separator = response.indexOf("\r\n\r\n");
        if (separator < 0) {
            return "";
        }
        return response.substring(separator + 4);
    }

    /**
     * Case-insensitive lookup of a header in the response.
     *
     * @return the header value, or null if absent
     */
    static String extractHeader(String response, String name) {
        String lowerName = name.toLowerCase();
        // Parse headers section (between status line and body)
        int headerEnd = response.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            headerEnd = response.length();
        }
        String headerSection = response.substring(0, headerEnd);
        String[] lines = headerSection.split("\r\n");
        for (int i = 1; i < lines.length; i++) { // skip status line
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String headerName = lines[i].substring(0, colon).trim().toLowerCase();
                if (headerName.equals(lowerName)) {
                    return lines[i].substring(colon + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Writes a UTF-8 string to an OutputStream.
     */
    static void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Reads all available data from an InputStream until EOF or timeout.
     */
    static String readAll(InputStream in, int timeoutMs) {
        try {
            var baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (java.net.SocketTimeoutException e) {
            // Timeout is expected when server keeps connection open
            return "";
        } catch (IOException e) {
            throw new RuntimeException("Failed to read response", e);
        }
    }

    /**
     * Reads a single HTTP response from the stream (headers + Content-Length-based body).
     * Useful for keep-alive connections where reading until EOF is not possible.
     */
    static String readResponse(InputStream in) {
        try {
            var baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1];
            // Read until we find \r\n\r\n
            int crlfCount = 0;
            while (true) {
                int b = in.read();
                if (b == -1) break;
                baos.write(b);
                if (b == '\r' || b == '\n') {
                    crlfCount++;
                } else {
                    crlfCount = 0;
                }
                // Detect \r\n\r\n (4 bytes) end of headers
                if (crlfCount >= 4) {
                    break;
                }
            }
            String headers = baos.toString(StandardCharsets.UTF_8);

            // Parse Content-Length to read body
            String clHeader = extractHeader(headers, "Content-Length");
            if (clHeader != null) {
                int contentLength = Integer.parseInt(clHeader.trim());
                byte[] body = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = in.read(body, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
                baos.write(body, 0, totalRead);
            }

            return baos.toString(StandardCharsets.UTF_8);
        } catch (java.net.SocketTimeoutException e) {
            return "";
        } catch (IOException e) {
            throw new RuntimeException("Failed to read response", e);
        }
    }
}
