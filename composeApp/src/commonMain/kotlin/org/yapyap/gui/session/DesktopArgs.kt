package org.yapyap.gui.session

import org.yapyap.orchestrator.LoopbackTransport

/**
 * Desktop CLI for two-instance local dev. All flags are optional; unset means
 * production behavior (real backends, legacy keyring, resolved data dir).
 *
 * - `--data-dir <path>` (or `--data-dir=<path>`) overrides data-directory resolution.
 * - `--loopback` selects the loopback Tor backend (localhost TCP, no Tor daemon).
 * - `--keyring-ns <name>` isolates the OS-keyring service (`org.yapyap.<name>`).
 *
 * Unknown flags and missing values fail fast with [DESKTOP_ARGS_USAGE].
 */
data class DesktopArgs(
    val dataDir: String? = null,
    val loopback: LoopbackTransport = LoopbackTransport.NONE,
    val keyringNamespace: String? = null,
)

const val DESKTOP_ARGS_USAGE: String =
    "Usage: YapYap [--data-dir <path>] [--loopback] [--keyring-ns <name>]\n" +
        "  Two-instance local dev, e.g.:\n" +
        "    YapYap --data-dir .yapyap-dev-a --loopback --keyring-ns dev-a"

fun parseDesktopArgs(args: Array<String>): DesktopArgs {
    var dataDir: String? = null
    var loopback = LoopbackTransport.NONE
    var keyringNamespace: String? = null
    var i = 0

    fun nextValue(flag: String): String {
        i++
        val value = args.getOrNull(i)?.takeIf { !it.startsWith("--") }
            ?: throw IllegalArgumentException("Missing value for $flag\n\n$DESKTOP_ARGS_USAGE")
        require(value.isNotBlank()) { "Blank value for $flag\n\n$DESKTOP_ARGS_USAGE" }
        return value
    }

    while (i < args.size) {
        val raw = args[i]
        val flag = raw.substringBefore('=')
        val inline = raw.substringAfter('=', missingDelimiterValue = "").ifEmpty { null }
        when (flag) {
            "--data-dir" -> {
                val value = inline ?: nextValue(flag)
                require(value.isNotBlank()) { "Blank value for $flag\n\n$DESKTOP_ARGS_USAGE" }
                dataDir = value
            }
            "--keyring-ns" -> {
                val value = inline ?: nextValue(flag)
                require(value.isNotBlank()) { "Blank value for $flag\n\n$DESKTOP_ARGS_USAGE" }
                keyringNamespace = value
            }
            "--loopback" -> {
                if (inline != null) {
                    throw IllegalArgumentException("--loopback takes no value\n\n$DESKTOP_ARGS_USAGE")
                }
                loopback = LoopbackTransport.TOR
            }
            else -> throw IllegalArgumentException("Unknown argument: $raw\n\n$DESKTOP_ARGS_USAGE")
        }
        i++
    }
    return DesktopArgs(dataDir = dataDir, loopback = loopback, keyringNamespace = keyringNamespace)
}
