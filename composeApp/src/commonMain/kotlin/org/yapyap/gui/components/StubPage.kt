package org.yapyap.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.yapyap.gui.theme.yapColors
import org.yapyap.gui.theme.yapType

/** Stub page: real chrome + placeholder content + Soon chip (03-ux-patterns.md §8.2). */
@Composable
fun StubPage(
    title: String,
    headline: String,
    explanation: String,
    modifier: Modifier = Modifier,
) {
    val colors = yapColors
    Box(
        modifier.fillMaxSize().background(colors.bgChat).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    style = yapType.headingXl.copy(
                        color = colors.textStrong,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                SoonChip()
            }
            Spacer(Modifier.height(16.dp))
            BasicText(
                headline,
                style = yapType.headingSm.copy(
                    color = colors.textDefault,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                explanation,
                style = yapType.bodySm.copy(
                    color = colors.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
            )
        }
    }
}
