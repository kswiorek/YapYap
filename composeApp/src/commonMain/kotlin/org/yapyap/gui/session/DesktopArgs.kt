package org.yapyap.gui.session

import org.yapyap.wiring.LoopbackTransport

/**
 * Desktop CLI for two-instance local dev. All flags are optional; unset means
 * production behavior (real backends, legacy keyring, resolved data dir).
 *
 * - `--native-titlebar` forces the OS window frame (native caption buttons)
 *   instead of the custom in-app title bar. Opt-in escape hatch only; the
 *   custom bar is used on every OS by default.
 *
 * Unknown flags and missing values fail fast with [DESKTOP_ARGS_USAGE].
 */
data class DesktopArgs(
    val dataDir: String? = null,
    val loopback: LoopbackTransport = LoopbackTransport.NONE,
    val keyringNamespace: String? = null,
    val nativeTitlebar: Boolean = false,
)

const val DESKTOP_ARGS_USAGE: String =
    "Usage: YapYap [--data-dir <path>] [--loopback] [--keyring-ns <name>] [--native-titlebar]\n" +
        "  Two-instance local dev, e.g.:\n" +
        "    YapYap --data-dir .yapyap-dev-a --loopback --keyring-ns dev-a"

fun parseDesktopArgs(args: Array<String>): DesktopArgs {
    var dataDir: String? = null
    var loopback = LoopbackTransport.NONE
    var keyringNamespace: String? = null
    var nativeTitlebar = false
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
            "--native-titlebar" -> {
                if (inline != null) {
                    throw IllegalArgumentException("--native-titlebar takes no value\n\n$DESKTOP_ARGS_USAGE")
                }
                nativeTitlebar = true
            }
            else -> throw IllegalArgumentException("Unknown argument: $raw\n\n$DESKTOP_ARGS_USAGE")
        }
        i++
    }
    return DesktopArgs(
        dataDir = dataDir,
        loopback = loopback,
        keyringNamespace = keyringNamespace,
        nativeTitlebar = nativeTitlebar,
    )
}
