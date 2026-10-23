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
package io.vidocq.chappe.http.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The padding of a Huffman string must be the most significant bits of the EOS code, all ones, and
 * shorter than 8 bits (RFC 7541 §5.2, CHAPPE-046). Each octet below holds the 5-bit code of
 * {@code '0'} ({@code 00000}) followed by 3 padding bits.
 */
class HpackHuffmanPaddingTest {

    private static String decode(int octet) {
        return HpackHuffman.decode(ByteBuffer.wrap(new byte[] {(byte) octet}), 1);
    }

    @Test
    void paddingOfOnesIsAccepted() {
        assertEquals("0", decode(0b0000_0111));
    }

    @ParameterizedTest
    @ValueSource(ints = {0b0000_0101, 0b0000_0011, 0b0000_0110})
    void paddingWithAZeroBitIsRejected(int octet) {
        assertThrows(IllegalArgumentException.class, () -> decode(octet));
    }
}
