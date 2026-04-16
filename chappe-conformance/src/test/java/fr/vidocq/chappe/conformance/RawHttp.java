package fr.vidocq.chappe.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Utilitaire pour envoyer des requetes HTTP brutes via Socket
 * et parser les reponses. Utilise pour les tests de conformite RFC.
 */
final class RawHttp {

    private static final int DEFAULT_TIMEOUT_MS = 5_000;

    private RawHttp() {}

    /**
     * Ouvre un socket, envoie la requete brute, lit la reponse complete et ferme.
     */
    static String sendAndReceive(int port, String rawRequest) {
        return sendAndReceive(port, rawRequest, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Ouvre un socket, envoie la requete brute, lit la reponse complete et ferme.
     *
     * @param port       port du serveur
     * @param rawRequest requete HTTP brute (avec \r\n)
     * @param timeoutMs  SO_TIMEOUT en millisecondes
     * @return la reponse HTTP brute
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
     * Ouvre un socket persistant (keep-alive). Le caller est responsable de le fermer.
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
     * Envoie une requete sur un socket deja ouvert et lit une reponse.
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
     * Extrait le code de statut HTTP depuis la ligne de statut.
     * Ex: "HTTP/1.1 200 OK" -> 200
     */
    static int extractStatusCode(String response) {
        if (response == null || response.isEmpty()) {
            throw new IllegalArgumentException("Empty response");
        }
        // Status line: HTTP/1.1 200 OK
        int firstSpace = response.indexOf(' ');
        if (firstSpace < 0) {
            throw new IllegalArgumentException("Malformed status line: " + response.lines().findFirst().orElse(""));
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
     * Extrait le corps de la reponse (tout apres le premier \r\n\r\n).
     */
    static String extractBody(String response) {
        int separator = response.indexOf("\r\n\r\n");
        if (separator < 0) {
            return "";
        }
        return response.substring(separator + 4);
    }

    /**
     * Recherche case-insensitive d'un header dans la reponse.
     *
     * @return la valeur du header, ou null si absent
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
     * Ecrit une chaine sur un OutputStream en UTF-8.
     */
    static void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Lit tout le contenu disponible d'un InputStream jusqu'a EOF ou timeout.
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
     * Lit une seule reponse HTTP depuis le stream (headers + body basee sur Content-Length).
     * Utile pour les connexions keep-alive ou l'on ne peut pas lire jusqu'a EOF.
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
