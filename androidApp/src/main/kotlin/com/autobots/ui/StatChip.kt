package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One cell of the stat strip: tiny grey label over a value, tap to reveal the tooltip.
 *
 * Shared by the live capture header and the import preview so a file's numbers read in
 * the same vocabulary as a running session's.
 */
@Composable
internal fun StatChip(
    label: String,
    value: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueColor: Color? = null,
    valueMaxLines: Int = 1,
    height: Dp? = null,
    active: String? = null,
    onTooltip: (String?) -> Unit = {},
) = StatChip(
    label = label,
    value = AnnotatedString(value),
    tooltip = tooltip,
    modifier = modifier,
    highlight = highlight,
    valueColor = valueColor,
    valueMaxLines = valueMaxLines,
    height = height,
    active = active,
    onTooltip = onTooltip,
)

/** Same chip, with a value that styles part of itself — a unit or a `×` set smaller. */
@Composable
internal fun StatChip(
    label: String,
    value: AnnotatedString,
    tooltip: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueColor: Color? = null,
    valueMaxLines: Int = 1,
    height: Dp? = null,
    active: String? = null,
    onTooltip: (String?) -> Unit = {},
) {
    val selected = active == tooltip
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) Color.White.copy(alpha = 0.12f)
                else Color.Black.copy(alpha = 0.32f),
            )
            .clickable { onTooltip(if (selected) null else tooltip) }
            .then(if (height != null) Modifier.height(height) else Modifier)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
        )
        // With a [height] the label pins to the top and the value centres in what is left,
        // so a one-line value sits level with the middle of a three-line one. Without one
        // the chip wraps its content and there is nothing to centre in — weighting an
        // unbounded column would stretch the chip to fill the screen.
        Box(
            modifier = if (height != null) Modifier.weight(1f) else Modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                color = valueColor ?: if (highlight) Color(0xFF69F0AE) else Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                maxLines = valueMaxLines,
                textAlign = TextAlign.Center,
            )
        }
    }
}
