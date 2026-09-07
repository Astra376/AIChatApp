package com.example.aichat.feature.customization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aichat.core.design.CircleAvatar
import com.example.aichat.core.ui.ProfileCountStat
import kotlin.math.cos
import kotlin.math.sin

val LocalAppearance = androidx.compose.runtime.staticCompositionLocalOf { AppearanceDto() }

fun profileFont(key: String): FontFamily = when(key) {
    "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "rounded" -> FontFamily.Cursive
    "sans" -> FontFamily.SansSerif; else -> FontFamily.Default
}
val appearancePresets = listOf("default", "aurora", "midnight", "rose", "paper")
fun appearancePreset(id: String): String? = id.removePrefix("preset:").takeIf { id.startsWith("preset:") && it in appearancePresets }

/** Bundled vector treatments render immediately, without image requests. */
@Composable
fun PresetArtwork(key: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.background
    val tint = when (key) {
        "aurora" -> Color(0xFF4D9C89); "midnight" -> Color(0xFF777BBC)
        "rose" -> Color(0xFFAD7486); "paper" -> Color(0xFFADA087); else -> Color(0xFF606874)
    }
    Canvas(modifier.background(base)) {
        drawRect(Brush.linearGradient(listOf(base, tint.copy(alpha = .28f), base), end = Offset(size.width, size.height)))
        if (key == "paper") {
            val step = 22.dp.toPx()
            var y = 0f
            while (y < size.height) { drawLine(tint.copy(alpha = .10f), Offset(0f, y), Offset(size.width, y), .5.dp.toPx()); y += step }
        } else {
            repeat(5) { i ->
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(-size.width * .2f, size.height * (.55f + i * .08f))
                    cubicTo(size.width * .2f, -size.height * .2f, size.width * .7f, size.height * 1.2f, size.width * 1.2f, size.height * (.12f + i * .08f))
                }
                drawPath(path, tint.copy(alpha = .12f - i * .015f), style = Stroke((if (i == 0) 24f else 1f).dp.toPx()))
            }
        }
    }
}

@Composable
private fun AppearanceImage(id: String, url: String?, modifier: Modifier, description: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = androidx.compose.runtime.remember(id, url) {
        coil.request.ImageRequest.Builder(context).data(url).memoryCacheKey("appearance:$id").diskCacheKey("appearance:$id").crossfade(false).build()
    }
    if (url != null) AsyncImage(model = request, contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
fun AppearanceBackdrop(preferences: AppearanceDto, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.background
    Box(modifier) {
        if (preferences.background == "image") {
            AppearanceImage(preferences.backgroundId, preferences.backgroundUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(base.copy(alpha = .72f)))
        } else if (preferences.background != "default") PresetArtwork(preferences.background, Modifier.fillMaxSize())
    }
}
@Composable
fun AppearanceProfileHeader(name: String,avatarUrl: String?,stats: List<ProfileCountStat>,appearance: AppearanceDto,modifier: Modifier=Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if (appearance.bannerId.isNotEmpty()) Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(16.dp))) {
            val preset = appearancePreset(appearance.bannerId)
            if (preset != null) PresetArtwork(preset, Modifier.fillMaxSize())
            else AppearanceImage(appearance.bannerId, appearance.bannerUrl, Modifier.fillMaxSize(), "Profile banner")
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            if (appearance.profileBackgroundId.isNotEmpty()) {
                val preset = appearancePreset(appearance.profileBackgroundId)
                if (preset != null) PresetArtwork(preset, Modifier.matchParentSize())
                else AppearanceImage(appearance.profileBackgroundId, appearance.profileBackgroundUrl, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .42f)))
            }
            Row(Modifier.fillMaxWidth().padding(if(appearance.profileBackgroundId.isNotEmpty()) 12.dp else 0.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(104.dp),contentAlignment=Alignment.Center) {
                    CircleAvatar(name=name.ifBlank{"User"},avatarUrl=avatarUrl,modifier=Modifier.size(if(appearance.frame=="none") 104.dp else 88.dp))
                    ProfileFrame(appearance.frame,Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(name.ifBlank{"User"},style=MaterialTheme.typography.headlineSmall.copy(fontFamily=profileFont(appearance.profileFont),fontWeight=FontWeight.Bold))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        stats.forEach { stat -> Column { Text(com.example.aichat.core.util.formatCount(stat.count),style=MaterialTheme.typography.titleMedium.copy(fontFamily=profileFont(appearance.profileFont))); Text(stat.label,style=MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
    }
}
@Composable fun ProfileFrame(frame: String,modifier: Modifier) {
    if(frame=="none") return
    val colors=when(frame) { "halo" -> listOf(Color(0xFFFFD99C),Color(0xFFB3884B)); "orbit" -> listOf(Color(0xFFACC6FF),Color(0xFFD6AEFF)); "laurel" -> listOf(Color(0xFFBFE1BD),Color(0xFF6D9F79)); else -> listOf(Color(0xFFF7AAD7),Color(0xFFB5C9FF),Color(0xFFB9E9D2)) }
    Canvas(modifier) {
        val radius=size.minDimension/2-3.dp.toPx()
        drawCircle(Brush.sweepGradient(colors+colors.first()),radius,style=Stroke(2.dp.toPx()))
        if(frame=="halo") drawCircle(colors.first().copy(alpha=.5f),radius-4.dp.toPx(),style=Stroke(.8.dp.toPx()))
        if (frame == "orbit") {
            drawArc(colors.last(), 210f, 100f, false, topLeft = Offset(5.dp.toPx(), 5.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        }
        if (frame == "prism") drawCircle(Brush.sweepGradient(colors.reversed() + colors.last()), radius - 3.dp.toPx(), style = Stroke(.7.dp.toPx()))
        if (frame == "laurel") repeat(12) { i ->
            val angle = (i * 20 + 35) * Math.PI / 180
            val root = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            val tip = Offset(center.x + cos(angle + .08).toFloat() * (radius - 6.dp.toPx()), center.y + sin(angle + .08).toFloat() * (radius - 6.dp.toPx()))
            val leaf = androidx.compose.ui.graphics.Path().apply { moveTo(root.x, root.y); quadraticBezierTo(root.x - 4.dp.toPx(), tip.y, tip.x, tip.y); quadraticBezierTo(root.x + 2.dp.toPx(), root.y, root.x, root.y); close() }
            drawPath(leaf, colors.first())
        }
    }
}
@Composable
fun ShowcaseWidgets(showcase: ShowcaseDto,onCharacter: (String)->Unit,modifier: Modifier=Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        showcase.featured?.let { character -> CharacterWidget("Featured character",listOf(character),onCharacter) }
        if(showcase.stats.charactersChatted!=null || showcase.stats.longestChat!=null) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                showcase.stats.charactersChatted?.let { Metric("Characters chatted",it,Modifier.weight(1f)) }
                showcase.stats.longestChat?.let { Metric("Longest chat · messages",it,Modifier.weight(1f)) }
            }
        }
        if(showcase.favorites.isNotEmpty()) CharacterWidget("Favorite characters",showcase.favorites,onCharacter)
        if(showcase.created.isNotEmpty()) CharacterWidget("Most popular creations",showcase.created,onCharacter)
        if(showcase.recommended.isNotEmpty()) CharacterWidget("Most chatted",showcase.recommended,onCharacter)
    }
}
@Composable private fun Metric(label: String,value: Int,modifier: Modifier) { Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.padding(14.dp)) { Text(com.example.aichat.core.util.formatCount(value),style=MaterialTheme.typography.titleLarge); Text(label,style=MaterialTheme.typography.bodySmall) } } }
@Composable private fun CharacterWidget(title: String,characters: List<ShowcaseCharacter>,onCharacter: (String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleMedium); LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(characters,key={it.id}) { c ->
        Surface(onClick={onCharacter(c.id)},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.padding(10.dp).widthIn(max=250.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { CircleAvatar(name=c.name,avatarUrl=c.avatarUrl,modifier=Modifier.size(44.dp)); Text(c.name,style=MaterialTheme.typography.labelLarge,maxLines=2) } }
    } } }
}
