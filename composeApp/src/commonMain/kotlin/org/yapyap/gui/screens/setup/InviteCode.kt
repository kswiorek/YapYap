package org.yapyap.gui.screens.setup

/**
 * Shared invite-code codec (S3). Invite bytes travel as opaque base64 text —
 * the GUI never inspects the payload, it only encodes (display) and decodes
 * (sponsor paste). Whitespace-tolerant on decode so pasted codes with line
 * breaks still work.
 */
object Base64Encoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(256) { -1 }.also { rev ->
        ALPHABET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[(b0 shr 2) and 0x3F])
            out.append(ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F])
            out.append(if (i + 1 < bytes.size) ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F] else '=')
            out.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Null on any malformed input — the caller shows "ask for a fresh one". */
    fun decode(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 4 != 0) return null
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var i = 0
        while (i < clean.length) {
            val c0 = clean[i]
            val c1 = clean[i + 1]
            val c2 = clean[i + 2]
            val c3 = clean[i + 3]
            val v0 = if (c0.code < 256) REVERSE[c0.code] else -1
            val v1 = if (c1.code < 256) REVERSE[c1.code] else -1
            if (v0 < 0 || v1 < 0) return null
            val pad2 = c2 == '='
            val pad3 = c3 == '='
            // Padding only valid as the final quantum's tail.
            if ((pad2 || pad3) && i + 4 != clean.length) return null
            if (pad2 && !pad3) return null
            val v2 = if (pad2) 0 else if (c2.code < 256) REVERSE[c2.code] else -1
            val v3 = if (pad3) 0 else if (c3.code < 256) REVERSE[c3.code] else -1
            if (!pad2 && v2 < 0) return null
            if (!pad3 && v3 < 0) return null
            out.add(((v0 shl 2) or (v1 shr 4)).toByte())
            if (!pad2) out.add((((v1 and 0xF) shl 4) or (v2 shr 2)).toByte())
            if (!pad3) out.add((((v2 and 0x3) shl 6) or v3).toByte())
            i += 4
        }
        return out.toByteArray()
    }
}
