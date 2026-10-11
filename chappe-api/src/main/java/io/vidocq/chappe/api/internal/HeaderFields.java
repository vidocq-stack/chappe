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
package io.vidocq.chappe.api.internal;

/**
 * The one syntax rule for the header and trailer fields chappe writes (CHAPPE-008).
 * <ul>
 *   <li>a name is an RFC 9110 §5.6.2 {@code token}: one or more tchar;</li>
 *   <li>a value holds only field-vchar ({@code 0x21-0x7E}), obs-text ({@code 0x80-0xFF}), SP and
 *       HTAB (RFC 9110 §5.5). CR, LF, NUL and other controls are refused, and so is any char
 *       above {@code U+00FF}: it has no single-byte form, and truncating it would forge a
 *       control (U+010A becomes LF).</li>
 * </ul>
 * The same rule serves the response builder, the upgrade head, the HTTP/1.1 writer and the HPACK
 * encoder. Not part of the public API: the package is exported to {@code io.vidocq.chappe.http}
 * only.
 */
public final class HeaderFields {

    private HeaderFields() {}

    /** RFC 9110 §5.6.2 {@code token}: one or more tchar; {@code null} is not a token. */
    public static boolean isToken(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            boolean tchar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) return false;
        }
        return true;
    }

    /**
     * RFC 9110 §5.5 field value: field-vchar, obs-text, SP and HTAB only, so one byte per char;
     * {@code null} is not a field value. Also the rule for a reason phrase (RFC 9112 §4).
     */
    public static boolean isFieldValue(String s) {
        if (s == null) return false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 0x21 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF) || c == ' ' || c == '\t';
            if (!ok) return false;
        }
        return true;
    }

    /** Whether {@code name: value} can be written as a header or trailer line. */
    public static boolean isValid(String name, String value) {
        return isToken(name) && isFieldValue(value);
    }

    /**
     * Throws unless {@code name: value} is a valid field. The message names the field, never
     * echoes the value (it may carry a secret or an injection attempt), and escapes the name.
     *
     * @param kind what the field is, for the message ({@code "header"}, {@code "trailer"}...)
     * @throws IllegalArgumentException if the name is not a token or the value not a field value
     */
    public static void requireValid(String kind, String name, String value) {
        if (!isToken(name)) {
            throw new IllegalArgumentException(
                    "Invalid " + kind + " name: " + printable(name) + " (must be an RFC 9110 token)");
        }
        if (!isFieldValue(value)) {
            throw new IllegalArgumentException("Invalid value for " + kind + " " + name
                    + " (null, a control char such as CR, LF or NUL, or a char above U+00FF)");
        }
    }

    /** {@code s} with every char outside printable ASCII escaped as {@code \}{@code uXXXX}. */
    public static String printable(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
