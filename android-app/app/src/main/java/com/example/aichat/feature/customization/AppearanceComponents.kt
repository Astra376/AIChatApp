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

fun profileFont(key: String): FontFamily = when(key) {
    "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "rounded" -> FontFamily.Cursive
    "sans" -> FontFamily.SansSerif; else -> FontFamily.Default
}
@Composable
fun AppearanceBackdrop(preferences: AppearanceDto,modifier: Modifier = Modifier) {
    val base=MaterialTheme.colorScheme.background
    Box(modifier) {
        when(preferences.background) {
            "image" -> preferences.backgroundUrl?.let { AsyncImage(model=it,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize()); Box(Modifier.fillMaxSize().background(base.copy(alpha=.72f))) }
            "aurora", "midnight", "rose", "paper" -> {
                val tint=when(preferences.background) { "aurora" -> Color(0xFF28564B); "midnight" -> Color(0xFF363359); "rose" -> Color(0xFF643340); else -> Color(0xFF68604F) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base,tint.copy(alpha=.28f),base))))
                Canvas(Modifier.fillMaxSize()) {
                    val step=if(preferences.background=="paper") 24.dp.toPx() else 64.dp.toPx()
                    var y=0f
                    while(y<size.height) {
                        var x=0f
                        while(x<size.width) { drawCircle(tint.copy(alpha=.14f),if(preferences.background=="paper") .6.dp.toPx() else 1.4.dp.toPx(),Offset(x,y)); x+=step }
                        y+=step
                    }
                }
            }
        }
    }
}
@Composable
fun AppearanceProfileHeader(name: String,avatarUrl: String?,stats: List<ProfileCountStat>,appearance: AppearanceDto,modifier: Modifier=Modifier) {
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        appearance.bannerUrl?.let { AsyncImage(model=it,contentDescription="Profile banner",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(18.dp))) }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            appearance.profileBackgroundUrl?.let { AsyncImage(model=it,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.matchParentSize()); Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha=.76f))) }
            Row(Modifier.fillMaxWidth().padding(if(appearance.profileBackgroundUrl!=null) 12.dp else 0.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
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
@Composable private fun ProfileFrame(frame: String,modifier: Modifier) {
    if(frame=="none") return
    val colors=when(frame) { "halo" -> listOf(Color(0xFFFFD99C),Color(0xFFB3884B)); "orbit" -> listOf(Color(0xFFACC6FF),Color(0xFFD6AEFF)); "laurel" -> listOf(Color(0xFFBFE1BD),Color(0xFF6D9F79)); else -> listOf(Color(0xFFF7AAD7),Color(0xFFB5C9FF),Color(0xFFB9E9D2)) }
    Canvas(modifier) {
        val radius=size.minDimension/2-3.dp.toPx()
        drawCircle(Brush.sweepGradient(colors+colors.first()),radius,style=Stroke(2.dp.toPx()))
        if(frame=="halo") drawCircle(colors.first().copy(alpha=.5f),radius-4.dp.toPx(),style=Stroke(.8.dp.toPx()))
        if(frame=="orbit" || frame=="prism") repeat(if(frame=="orbit") 3 else 6) { i ->
            val angle=i*2*Math.PI/(if(frame=="orbit") 3 else 6)-Math.PI/2
            drawCircle(colors[i%colors.size],3.dp.toPx(),Offset(center.x+cos(angle).toFloat()*radius,center.y+sin(angle).toFloat()*radius))
        }
        if(frame=="laurel") repeat(14) { i ->
            val angle=(i*18+35)*Math.PI/180
            val start=Offset(center.x+cos(angle).toFloat()*radius,center.y+sin(angle).toFloat()*radius)
            drawLine(colors.first(),start,Offset(center.x+cos(angle+.05).toFloat()*(radius-5.dp.toPx()),center.y+sin(angle+.05).toFloat()*(radius-5.dp.toPx())),2.dp.toPx())
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
