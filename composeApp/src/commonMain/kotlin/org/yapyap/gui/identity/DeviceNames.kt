package org.yapyap.gui.identity

import org.yapyap.protocol.PeerId

/**
 * Deterministic human-readable device names (§6 of the plan): curated adjective
 * × noun lists, indices derived from SHA-256 of [PeerId.id]. Display-only sugar;
 * identity stays [org.yapyap.orchestrator.runtime.identity.DeviceView].
 *
 * The lists are versioned constants with golden tests — never silently change them.
 */
object DeviceNames {
    /** Word-list version. Bump + migrate when the lists change; golden tests pin names. */
    @Suppress("unused")
    const val LIST_VERSION = 1

    // 64 common adjectives — curated for familiarity, no ambiguous spellings.
    val adjectives: List<String> = listOf(
        "Amber", "Ashen", "Azure", "Bold", "Brass", "Brave", "Bright",
        "Bronze", "Calm", "Cinder", "Clear", "Cobalt", "Copper", "Coral",
        "Crimson", "Dapper", "Dawn", "Dusky", "Eager", "Ember", "Fable",
        "Fern", "Flint", "Frost", "Gentle", "Glacier", "Golden", "Granite",
        "Grove", "Harbor", "Hazel", "Hearty", "Honey", "Iron", "Ivory",
        "Jade", "Jolly", "Juniper", "Keen", "Kind", "Lively", "Lunar",
        "Marble", "Meadow", "Misty", "Nimble", "Noble", "Onyx", "Opal",
        "Peppy", "Pine", "Placid", "Quartz", "Quiet", "Redwood", "River",
        "Sable", "Sage", "Silent", "Solar", "Spruce", "Steady", "Swift", "Tawny",
    )

    // 64 familiar nouns.
    val nouns: List<String> = listOf(
        "Albatross", "Anchor", "Badger", "Beacon", "Beaver", "Birch", "Bison", "Bluff",
        "Brook", "Cabin", "Canyon", "Cliff", "Comet", "Cove", "Crane", "Dale",
        "Dune", "Eagle", "Falcon", "Fern", "Fjord", "Fox", "Gable", "Glade",
        "Grove", "Harbor", "Hawk", "Heath", "Heron", "Hollow", "Hound", "Inlet",
        "Island", "Jackal", "Kestrel", "Lagoon", "Lark", "Linden", "Lynx", "Maple",
        "Marsh", "Meadow", "Mesa", "Moose", "North", "Otter", "Owl", "Pine",
        "Plover", "Quail", "Raven", "Reef", "Ridge", "Robin", "Sparrow", "Summit",
        "Thrush", "Timber", "Trout", "Valley", "Vole", "Willow", "Wren", "Yew",
    )

    init {
        require(adjectives.size == 64 && nouns.size == 64) {
            "DeviceNames lists must stay at 64 entries each (golden-tested)"
        }
    }

    /** Stable display name for a device, e.g. "Amber Falcon". */
    fun from(peerId: PeerId): String {
        val digest = sha256Hex(peerId.id.lowercase())
        // First two bytes → indices. Deterministic across runs and platforms.
        val a = digestByte(digest, 0) % adjectives.size
        val b = digestByte(digest, 1) % nouns.size
        return "${adjectives[a]} ${nouns[b]}"
    }

    private fun digestByte(hex: String, index: Int): Int =
        hex.substring(index * 2, index * 2 + 2).toInt(16)

    // Minimal SHA-256 over UTF-8, dependency-free (commonMain has no crypto provider;
    // this is display sugar, not security — a compact implementation is fine).
    // Hex literals > Int.MAX wrap via toInt() — intended two's-complement constants.
    @Suppress("RedundantCallOfConversionMethod")
    private fun sha256Hex(input: String): String {
        val bytes = input.encodeToByteArray()
        val h = intArrayOf(
            0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
            0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt(),
        )
        val k = intArrayOf(
            0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
            0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
            0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
            0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
            0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
            0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
            0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
            0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
        val bitLen = bytes.size.toLong() * 8
        val padded = mutableListOf<Byte>()
        padded.addAll(bytes.toList())
        padded.add(0x80.toByte())
        while ((padded.size % 64) != 56) padded.add(0)
        for (i in 7 downTo 0) padded.add(((bitLen ushr (i * 8)) and 0xFF).toByte())
        val w = IntArray(64)
        var p = 0
        while (p < padded.size) {
            for (i in 0 until 16) {
                w[i] = ((padded[p + i * 4].toInt() and 0xFF) shl 24) or
                        ((padded[p + i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((padded[p + i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (padded[p + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0];
            var b = h[1];
            var c = h[2];
            var d = h[3]
            var e = h[4];
            var f = h[5];
            var g = h[6];
            var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            p += 64
        }
        return h.joinToString("") { (it.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0') }
    }

    private fun Int.rotateRight(n: Int): Int = (this ushr n) or (this shl (32 - n))
}
