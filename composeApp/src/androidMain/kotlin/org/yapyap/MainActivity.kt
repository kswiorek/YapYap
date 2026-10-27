package org.yapyap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.io.files.Path
import org.yapyap.gui.session.YapSession
import org.yapyap.gui.session.platformNotificationSink
import org.yapyap.gui.session.resolveDataDirectory
import java.io.File

class MainActivity : ComponentActivity() {
    private var session: YapSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dataDir: Path = resolveDataDirectory()
        val prefsFile = File(dataDir.toString(), "ui-prefs.json")
        session = YapSession(
            dataDirectory = dataDir,
            notificationSink = platformNotificationSink(),
            readPrefs = { runCatching { if (prefsFile.exists()) prefsFile.readText() else null }.getOrNull() },
            writePrefs = { raw ->
                runCatching {
                    prefsFile.parentFile?.mkdirs()
                    prefsFile.writeText(raw)
                }
            },
        ).also { it.start() }
        setContent {
            App(session)
        }
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App(null)
}
