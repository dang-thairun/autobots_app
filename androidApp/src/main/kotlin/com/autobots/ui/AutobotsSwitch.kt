package com.autobots.ui

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The app's only toggle.
 *
 * Material's default switch reads as on-ish in both states on a dark screen: the unchecked
 * track picks up the surface tint and the thumb stays bright, so at a glance the operator
 * cannot tell a running Face Detection from a stopped one. Every state here is stated
 * explicitly instead — **green when on, dark grey when off** — and the two are far enough
 * apart in both hue and lightness to read across a phone on a tripod in daylight.
 *
 * Disabled keeps the same two colours at lower alpha, so "off" and "cannot be changed" never
 * look like the same thing.
 */
@Composable
fun AutobotsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = OnThumb,
            checkedTrackColor = OnTrack,
            checkedBorderColor = OnTrack,
            uncheckedThumbColor = OffThumb,
            uncheckedTrackColor = OffTrack,
            uncheckedBorderColor = OffBorder,
            disabledCheckedThumbColor = OnThumb.copy(alpha = 0.5f),
            disabledCheckedTrackColor = OnTrack.copy(alpha = 0.4f),
            disabledCheckedBorderColor = OnTrack.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = OffThumb.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = OffTrack.copy(alpha = 0.5f),
            disabledUncheckedBorderColor = OffBorder.copy(alpha = 0.5f),
        ),
    )
}

/** Bright thumb on a saturated green track — unmistakably running. */
private val OnTrack = Color(0xFF26A69A)
private val OnThumb = Color(0xFFE0F2F1)

/** Near-black track with a muted grey thumb — unmistakably stopped. */
private val OffTrack = Color(0xFF1C1F21)
private val OffThumb = Color(0xFF78909C)
private val OffBorder = Color(0xFF37474F)

/** Kept next to the switch so a row's text can echo its state without inventing a colour. */
val SwitchOnTextColor: Color = Color(0xFFE0F2F1)
val SwitchOffTextColor: Color = Color(0xFF78909C)
