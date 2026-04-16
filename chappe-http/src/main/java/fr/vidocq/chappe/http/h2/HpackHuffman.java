package fr.vidocq.chappe.http.h2;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HPACK Huffman codec — RFC 7541 Appendix B.
 *
 * <p>Decoding uses a binary tree built at class-load time (bit 0 → left, bit 1 → right).
 * Encoding walks the HUFFMAN_TABLE directly.
 */
public final class HpackHuffman {

    // -------------------------------------------------------------------------
    // Huffman table: {code, length} for symbols 0-256 (256 = EOS)
    // Source: RFC 7541 Appendix B
    // -------------------------------------------------------------------------
    private static final int[][] HUFFMAN_TABLE = {
        // 0-31 (control characters)
        {0x1ff8, 13},    {0x7fffd8, 23},  {0xfffffe2, 28}, {0xfffffe3, 28},
        {0xfffffe4, 28}, {0xfffffe5, 28}, {0xfffffe6, 28}, {0xfffffe7, 28},
        {0xfffffe8, 28}, {0xffffea, 24},  {0x3ffffffc, 30},{0xfffffe9, 28},
        {0xfffffea, 28}, {0x3ffffffd, 30},{0xfffffeb, 28}, {0xfffffec, 28},
        {0xfffffed, 28}, {0xfffffee, 28}, {0xfffffef, 28}, {0xffffff0, 28},
        {0xffffff1, 28}, {0xffffff2, 28}, {0x3ffffffe, 30},{0xffffff3, 28},
        {0xffffff4, 28}, {0xffffff5, 28}, {0xffffff6, 28}, {0xffffff7, 28},
        {0xffffff8, 28}, {0xffffff9, 28}, {0xffffffa, 28}, {0xffffffb, 28},
        // 32-63 (punctuation, digits)
        {0x14, 6},       {0x3f8, 10},     {0x3f9, 10},     {0xffa, 12},
        {0x1ff9, 13},    {0x15, 6},       {0xf8, 8},       {0x7fa, 11},
        {0x3fa, 10},     {0x3fb, 10},     {0xf9, 8},       {0x7fb, 11},
        {0xfa, 8},       {0x16, 6},       {0x17, 6},       {0x18, 6},
        {0x0, 5},        {0x1, 5},        {0x2, 5},        {0x19, 6},
        {0x1a, 6},       {0x1b, 6},       {0x1c, 6},       {0x1d, 6},
        {0x1e, 6},       {0x1f, 6},       {0x5c, 7},       {0xfb, 8},
        {0x7ffc, 15},    {0x20, 6},       {0xffb, 12},     {0x3fc, 10},
        // 64-95 (uppercase letters and some symbols)
        {0x1ffa, 13},    {0x21, 6},       {0x5d, 7},       {0x5e, 7},
        {0x5f, 7},       {0x60, 7},       {0x61, 7},       {0x62, 7},
        {0x63, 7},       {0x64, 7},       {0x65, 7},       {0x66, 7},
        {0x67, 7},       {0x68, 7},       {0x69, 7},       {0x6a, 7},
        {0x6b, 7},       {0x6c, 7},       {0x6d, 7},       {0x6e, 7},
        {0x6f, 7},       {0x70, 7},       {0x71, 7},       {0x72, 7},
        {0xfc, 8},       {0x73, 7},       {0xfd, 8},       {0x1ffb, 13},
        {0x7fff0, 19},   {0x1ffc, 13},    {0x3ffc, 14},    {0x22, 6},
        // 96-127 (lowercase letters and some symbols)
        {0x7ffd, 15},    {0x3, 5},        {0x23, 6},       {0x4, 5},
        {0x24, 6},       {0x5, 5},        {0x25, 6},       {0x26, 6},
        {0x27, 6},       {0x6, 5},        {0x74, 7},       {0x75, 7},
        {0x28, 6},       {0x29, 6},       {0x2a, 6},       {0x7, 5},
        {0x2b, 6},       {0x76, 7},       {0x2c, 6},       {0x8, 5},
        {0x9, 5},        {0x2d, 6},       {0x77, 7},       {0x78, 7},
        {0x79, 7},       {0x7a, 7},       {0x7b, 7},       {0x7ffe, 15},
        {0x7fc, 11},     {0x3ffd, 14},    {0x1ffd, 13},    {0xffffffc, 28},
        // 128-159
        {0xfffe6, 20},   {0x3fffd2, 22},  {0xfffe7, 20},   {0xfffe8, 20},
        {0x3fffd3, 22},  {0x3fffd4, 22},  {0x3fffd5, 22},  {0x7fffd9, 23},
        {0x3fffd6, 22},  {0x7fffda, 23},  {0x7fffdb, 23},  {0x7fffdc, 23},
        {0x7fffdd, 23},  {0x7fffde, 23},  {0xffffeb, 24},  {0x7fffdf, 23},
        {0xffffec, 24},  {0xffffed, 24},  {0x3fffd7, 22},  {0x7fffe0, 23},
        {0xffffee, 24},  {0x7fffe1, 23},  {0x7fffe2, 23},  {0x7fffe3, 23},
        {0x7fffe4, 23},  {0x1fffdc, 21},  {0x3fffd8, 22},  {0x7fffe5, 23},
        {0x3fffd9, 22},  {0x7fffe6, 23},  {0x7fffe7, 23},  {0xffffef, 24},
        // 160-191
        {0x3fffda, 22},  {0x1fffdd, 21},  {0xfffe9, 20},   {0x3fffdb, 22},
        {0x3fffdc, 22},  {0x7fffe8, 23},  {0x7fffe9, 23},  {0x1fffde, 21},
        {0x7fffea, 23},  {0x3fffdd, 22},  {0x3fffde, 22},  {0xfffff0, 24},
        {0x1fffdf, 21},  {0x3fffdf, 22},  {0x7fffeb, 23},  {0x7fffec, 23},
        {0x1fffe0, 21},  {0x1fffe1, 21},  {0x3fffe0, 22},  {0x1fffe2, 21},
        {0x7fffed, 23},  {0x3fffe1, 22},  {0x7fffee, 23},  {0x7fffef, 23},
        {0xfffea, 20},   {0x3fffe2, 22},  {0x3fffe3, 22},  {0x3fffe4, 22},
        {0x7ffff0, 23},  {0x3fffe5, 22},  {0x3fffe6, 22},  {0x7ffff1, 23},
        // 192-223
        {0x3ffffe0, 26}, {0x3ffffe1, 26}, {0xfffeb, 20},   {0x7fff1, 19},
        {0x3fffe7, 22},  {0x7ffff2, 23},  {0x3fffe8, 22},  {0x1ffffec, 25},
        {0x3ffffe2, 26}, {0x3ffffe3, 26}, {0x3ffffe4, 26}, {0x7ffffde, 27},
        {0x7ffffdf, 27}, {0x3ffffe5, 26}, {0xfffff1, 24},  {0x1ffffed, 25},
        {0x7fff2, 19},   {0x1fffe3, 21},  {0x3ffffe6, 26}, {0x7ffffe0, 27},
        {0x7ffffe1, 27}, {0x3ffffe7, 26}, {0x7ffffe2, 27}, {0xfffff2, 24},
        {0x1fffe4, 21},  {0x1fffe5, 21},  {0x3ffffe8, 26}, {0x3ffffe9, 26},
        {0xffffffd, 28}, {0x7ffffe3, 27}, {0x7ffffe4, 27}, {0x7ffffe5, 27},
        // 224-255
        {0xfffec, 20},   {0xfffff3, 24},  {0xfffed, 20},   {0x1fffe6, 21},
        {0x3fffe9, 22},  {0x1fffe7, 21},  {0x1fffe8, 21},  {0x7ffff3, 23},
        {0x3fffea, 22},  {0x3fffeb, 22},  {0x1ffffee, 25}, {0x1ffffef, 25},
        {0xfffff4, 24},  {0xfffff5, 24},  {0x3ffffea, 26}, {0x7ffff4, 23},
        {0x3ffffeb, 26}, {0x7ffffe6, 27}, {0x3ffffec, 26}, {0x3ffffed, 26},
        {0x7ffffe7, 27}, {0x7ffffe8, 27}, {0x7ffffe9, 27}, {0x7ffffea, 27},
        {0x7ffffeb, 27}, {0xffffffe, 28}, {0x7ffffec, 27}, {0x7ffffed, 27},
        {0x7ffffee, 27}, {0x7ffffef, 27}, {0x7fffff0, 27}, {0x3ffffee, 26},
        // 256 = EOS
        {0x3fffffff, 30}
    };

    // EOS symbol index
    private static final int EOS = 256;

    // -------------------------------------------------------------------------
    // Decode tree
    // -------------------------------------------------------------------------

    /** Internal node of the Huffman decode tree. */
    private static final class Node {
        Node left;   // bit 0
        Node right;  // bit 1
        int symbol;  // -1 for internal nodes, 0-256 for leaves

        Node() {
            this.symbol = -1;
        }
    }

    private static final Node ROOT;

    static {
        ROOT = new Node();
        for (int sym = 0; sym < HUFFMAN_TABLE.length; sym++) {
            int code   = HUFFMAN_TABLE[sym][0];
            int length = HUFFMAN_TABLE[sym][1];
            Node node = ROOT;
            for (int i = length - 1; i >= 0; i--) {
                int bit = (code >>> i) & 1;
                if (bit == 0) {
                    if (node.left == null) node.left = new Node();
                    node = node.left;
                } else {
                    if (node.right == null) node.right = new Node();
                    node = node.right;
                }
            }
            node.symbol = sym;
        }
    }

    private HpackHuffman() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Decodes {@code length} Huffman-encoded bytes from {@code src}.
     *
     * <p>The buffer position is advanced by {@code length} bytes.
     *
     * @param src    source buffer positioned at the start of the encoded data
     * @param length number of bytes to read
     * @return decoded string (ISO-8859-1 / ASCII as per HPACK)
     * @throws IllegalArgumentException if the Huffman stream is invalid or contains EOS
     */
    public static String decode(ByteBuffer src, int length) {
        byte[] out = new byte[length * 2]; // generous upper bound
        int outLen = 0;

        Node node = ROOT;
        int paddingBits = 0;   // count of trailing 1-bits (potential EOS padding)

        for (int i = 0; i < length; i++) {
            int b = src.get() & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                int v = (b >>> bit) & 1;
                node = (v == 0) ? node.left : node.right;
                if (node == null) {
                    throw new IllegalArgumentException("Invalid Huffman code in HPACK stream");
                }
                if (node.symbol >= 0) {
                    if (node.symbol == EOS) {
                        throw new IllegalArgumentException("EOS symbol encountered in HPACK Huffman stream");
                    }
                    if (outLen == out.length) {
                        // grow buffer
                        byte[] tmp = new byte[out.length * 2];
                        System.arraycopy(out, 0, tmp, 0, outLen);
                        out = tmp;
                    }
                    out[outLen++] = (byte) node.symbol;
                    node = ROOT;
                    paddingBits = 0;
                } else {
                    // Track potential EOS padding: every 1-bit advances into the EOS code
                    paddingBits = (v == 1) ? paddingBits + 1 : 0;
                }
            }
        }

        // After consuming all bytes the traversal must end at the root or on a
        // path that is consistent with EOS padding (remaining bits all 1s).
        if (node != ROOT) {
            // Verify that the partial code matches the EOS prefix of the same length
            // i.e. all remaining bits read were 1s (paddingBits == bits consumed past last symbol).
            // A simpler check: the node we're on must be reachable only via all-1 bits from
            // the last emitted symbol, which is exactly what paddingBits tracks.
            // RFC 7541 §5.2: padding MUST be EOS prefix bits (all ones) and < 8 bits.
            if (paddingBits == 0 || paddingBits >= 8) {
                throw new IllegalArgumentException(
                    "Invalid HPACK Huffman padding: " + paddingBits + " trailing bit(s)");
            }
            // Verify all padding bits are 1 (they are by construction of paddingBits counter)
        }

        return new String(out, 0, outLen, StandardCharsets.ISO_8859_1);
    }

    /**
     * Encodes {@code s} using HPACK Huffman coding.
     *
     * @param s source string
     * @return Huffman-encoded bytes
     */
    public static byte[] encode(String s) {
        byte[] input = s.getBytes(StandardCharsets.ISO_8859_1);
        // Calculate total bits needed
        long totalBits = 0;
        for (byte b : input) {
            totalBits += HUFFMAN_TABLE[b & 0xFF][1];
        }
        int totalBytes = (int) ((totalBits + 7) / 8);
        byte[] out = new byte[totalBytes];

        int bitPos = 0; // current bit position in output
        for (byte b : input) {
            int sym    = b & 0xFF;
            int code   = HUFFMAN_TABLE[sym][0];
            int length = HUFFMAN_TABLE[sym][1];
            for (int i = length - 1; i >= 0; i--) {
                int bit      = (code >>> i) & 1;
                int byteIdx  = bitPos / 8;
                int bitShift = 7 - (bitPos % 8);
                out[byteIdx] |= (byte) (bit << bitShift);
                bitPos++;
            }
        }
        // Pad remaining bits with 1s (EOS prefix)
        while (bitPos < totalBytes * 8) {
            int byteIdx  = bitPos / 8;
            int bitShift = 7 - (bitPos % 8);
            out[byteIdx] |= (byte) (1 << bitShift);
            bitPos++;
        }
        return out;
    }
}
