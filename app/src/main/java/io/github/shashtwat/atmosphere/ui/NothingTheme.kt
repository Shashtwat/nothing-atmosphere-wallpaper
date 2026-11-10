package io.github.shashtwat.atmosphere.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shashtwat.atmosphere.R

/** Nothing OS 5 component tokens (Nothing Settings / com.nothing.widget.ui). */
object Nt {
    val red = Color(0xFFD71921)
    val NType = FontFamily(Font(R.font.nw_ntype82))
    val NDot = FontFamily(Font(R.font.nw_ndot55))
    val NDot57 = FontFamily(Font(R.font.nw_ndot57))
}

data class NtColors(
    val surfacePrimary: Color, val surfaceOnPrimary: Color, val surfaceSecondary: Color, val surfaceTertiary: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color,
    val iconPrimary: Color, val iconInverted: Color, val iconTertiary: Color, val iconDisable: Color,
    val border: Color, val borderDarker: Color, val accent: Color,
)

private val Light = NtColors(
    Color(0xFFF2F2F2), Color(0xFFFCFCFC), Color(0xFFF2F2F2), Color(0xFFE3E3E3),
    Color(0xFF1C1C1C), Color(0xFF484848), Color(0xFF777777),
    Color(0xFF1C1C1C), Color(0xFFF2F2F2), Color(0xFF929292), Color(0xFFC8C8C8),
    Color(0xFFC8C8C8), Color(0xFFADADAD), Color(0xFFD71921),
)
private val Dark = NtColors(
    Color(0xFF000000), Color(0xFF101010), Color(0xFF1C1C1C), Color(0xFF323232),
    Color(0xFFF2F2F2), Color(0xFFC8C8C8), Color(0xFF929292),
    Color(0xFFF2F2F2), Color(0xFF1C1C1C), Color(0xFF929292), Color(0xFF484848),
    Color(0xFF484848), Color(0xFF606060), Color(0xFFEC232B),
)

private val LocalNt = androidx.compose.runtime.staticCompositionLocalOf { Light }
val nt: NtColors @Composable get() = LocalNt.current

@Composable
fun NothingTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = (if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()).copy(
        primary = c.iconPrimary, onPrimary = c.iconInverted, background = c.surfacePrimary, onBackground = c.textPrimary,
        surface = c.surfacePrimary, onSurface = c.textPrimary, surfaceContainer = c.surfaceOnPrimary,
        surfaceContainerHigh = c.surfaceTertiary, onSurfaceVariant = c.textSecondary, outline = c.border,
        outlineVariant = c.border, secondaryContainer = c.surfaceTertiary,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalNt provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Medium top app bar: back arrow row (48 dp) + NType 82 headline 28 sp. */
@Composable
fun NtTopBar(title: String, onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.height(48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.nt_back_arrow), "Back", Modifier.size(24.dp), colorFilter = ColorFilter.tint(nt.iconPrimary)) }
        }
        Text(
            title, fontFamily = Nt.NType, fontSize = 28.sp, color = nt.textPrimary, maxLines = 2,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).heightIn(min = 48.dp),
        )
    }
}

/** Section header: 14 sp / 500, not uppercase. */
@Composable
fun NtHeader(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nt.textPrimary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 12.dp))
}

@Composable
fun NtFooter(text: String) {
    Text(text, fontSize = 12.sp, color = nt.textTertiary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp))
}

/** Group of items: 16 dp outer corners, 4 dp inner, 1 dp gaps (Nothing Settings). */
@Composable
fun NtGroup(vararg items: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        items.forEachIndexed { i, item ->
            val top = if (i == 0) 16.dp else 4.dp
            val bottom = if (i == items.lastIndex) 16.dp else 4.dp
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(nt.surfaceOnPrimary)) { item() }
        }
    }
}

/** List item: 18 sp title, 14 sp summary, min 52 dp, 16 dp padding, trailing slot or dot chevron. */
@Composable
fun NtItem(
    title: String, summary: String? = null, onClick: (() -> Unit)? = null, chevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, lineHeight = 24.sp, color = nt.textPrimary)
            if (summary != null) Text(summary, fontSize = 14.sp, lineHeight = 20.sp, color = nt.textSecondary)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        else if (chevron) {
            Spacer(Modifier.width(12.dp))
            Image(painterResource(R.drawable.nt_chevron), null, Modifier.width(8.dp).height(14.dp), colorFilter = ColorFilter.tint(nt.iconTertiary))
        }
    }
}

private val SwitchEase = CubicBezierEasing(0.4f, 0f, 0f, 1f)

/** Nothing switch: 40x28 track, 24 dp thumb, 150 ms (0.4,0,0,1). */
@Composable
fun NtSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) nt.iconPrimary else nt.iconTertiary, tween(150, easing = SwitchEase), label = "t")
    val x by animateDpAsState(if (checked) 14.dp else 2.dp, tween(150, easing = SwitchEase), label = "x")
    Box(Modifier.width(40.dp).height(28.dp).clip(CircleShape).background(track).clickable { onChange(!checked) }) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(nt.surfaceOnPrimary))
    }
}

/** Nothing pill button. Filled = #1C1C1C / #F2F2F2 with inverted text; outlined otherwise. */
@Composable
fun NButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    Button(
        onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, nt.border),
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = nt.iconPrimary, contentColor = nt.iconInverted)
        else ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = nt.textPrimary),
    ) { Text(text, fontSize = 16.sp) }
}

// Compatibility names used by widget settings screens; they render with the Nothing components above.
@Composable
fun NHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, fontFamily = Nt.NType, fontSize = 28.sp, color = nt.textPrimary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp))
}

@Composable
fun NSection(text: String) = NtHeader(text)

@Composable
fun NCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(nt.surfaceOnPrimary), content = content)
}

@Composable
fun NRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Box(modifier) { NtItem(title, subtitle, chevron = false, trailing = trailing) }
}

@Composable
fun NSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = NtSwitch(checked, onChange)
