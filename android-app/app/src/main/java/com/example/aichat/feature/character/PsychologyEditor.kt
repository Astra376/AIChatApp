package com.example.aichat.feature.character

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aichat.core.design.AppTextField
import com.example.aichat.core.network.*
import kotlin.math.*

fun CharacterEmotionDto.scores(): List<Pair<String, Int>> = listOf(
    "Trust" to trust, "Affection" to affection, "Stress" to stress, "Energy" to energy,
    "Openness" to openness, "Joy" to joy, "Sadness" to sadness, "Anger" to anger,
    "Fear" to fear, "Curiosity" to curiosity, "Jealousy" to jealousy, "Hope" to hope,
    "Loneliness" to loneliness, "Shame" to shame, "Pride" to pride, "Guilt" to guilt
)
fun CharacterEmotionDto.withScore(key: String, value: Int): CharacterEmotionDto {
    val v = value.coerceIn(0, 100)
    return when (key) {
        "Trust" -> copy(trust=v); "Affection" -> copy(affection=v); "Stress" -> copy(stress=v); "Energy" -> copy(energy=v)
        "Openness" -> copy(openness=v); "Joy" -> copy(joy=v); "Sadness" -> copy(sadness=v); "Anger" -> copy(anger=v)
        "Fear" -> copy(fear=v); "Curiosity" -> copy(curiosity=v); "Jealousy" -> copy(jealousy=v); "Hope" -> copy(hope=v)
        "Loneliness" -> copy(loneliness=v); "Shame" -> copy(shame=v); "Pride" -> copy(pride=v); "Guilt" -> copy(guilt=v)
        else -> this
    }
}
fun CharacterPersonalityDto.scores() = listOf("Warmth" to warmth, "Confidence" to confidence, "Playfulness" to playfulness,
    "Formality" to formality, "Assertiveness" to assertiveness, "Volatility" to volatility, "Resilience" to resilience, "Adaptability" to adaptability)
fun CharacterPersonalityDto.withScore(key: String, value: Int): CharacterPersonalityDto {
    val v = value.coerceIn(0, 100)
    return when (key) { "Warmth" -> copy(warmth=v); "Confidence" -> copy(confidence=v); "Playfulness" -> copy(playfulness=v)
        "Formality" -> copy(formality=v); "Assertiveness" -> copy(assertiveness=v); "Volatility" -> copy(volatility=v)
        "Resilience" -> copy(resilience=v); "Adaptability" -> copy(adaptability=v); else -> this }
}

@Composable
fun PsychologySection(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
        }
    }
}

/** Native sliders provide complete accessible controls; the canvas adds direct radar editing. */
@Composable
fun PsychologyRadar(scores: List<Pair<String, Int>>, onChange: (String, Int) -> Unit, enabled: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val values = scores.take(16)
    val update by rememberUpdatedState(onChange)
    val latestValues by rememberUpdatedState(values)
    fun changePoint(point: Offset, width: Float, height: Float) {
        val center = Offset(width / 2f, height / 2f)
        val radius = min(width, height) * .42f
        val delta = point - center
        var angle = atan2(delta.y, delta.x) + PI.toFloat() / 2f
        if (angle < 0) angle += 2f * PI.toFloat()
        val index = (angle / (2f * PI.toFloat()) * latestValues.size).roundToInt() % latestValues.size
        update(latestValues[index].first, (delta.getDistance() / radius * 100).roundToInt().coerceIn(0, 100))
    }
    Canvas(Modifier.fillMaxWidth().height(210.dp).semantics { contentDescription = "Psychology radar. Use the sliders below to adjust each emotion or trait." }
        .pointerInput(enabled, values.size) { if (enabled) detectTapGestures { changePoint(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(enabled, values.size) { if (enabled) detectDragGestures(onDragStart = { changePoint(it, size.width.toFloat(), size.height.toFloat()) }) { change, _ ->
            change.consume(); changePoint(change.position, size.width.toFloat(), size.height.toFloat())
        } }) {
        if (values.isEmpty()) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) * .42f
        fun point(index: Int, scale: Float): Offset {
            val angle = 2 * PI.toFloat() * index / values.size - PI.toFloat() / 2
            return center + Offset(cos(angle), sin(angle)) * radius * scale
        }
        for (ring in 1..4) {
            val path = Path()
            values.indices.forEach { index -> val p = point(index, ring / 4f); if (index == 0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) }
            path.close(); drawPath(path, grid, style = Stroke(1.dp.toPx()))
        }
        values.indices.forEach { drawLine(grid, center, point(it, 1f), strokeWidth = 1.dp.toPx()) }
        val path = Path()
        values.forEachIndexed { index, pair -> val p = point(index,pair.second.coerceIn(0,100)/100f); if(index==0) path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) }
        path.close(); drawPath(path,accent.copy(alpha=.16f)); drawPath(path,accent,style=Stroke(2.dp.toPx()))
        values.forEachIndexed { index, pair -> drawCircle(accent,4.dp.toPx(),point(index,pair.second.coerceIn(0,100)/100f)) }
    }
    Text("Clockwise from top: " + values.joinToString(" · ") { it.first }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EmotionEditor(value: CharacterEmotionDto, enabled: Boolean = true, onChange: (CharacterEmotionDto) -> Unit) {
    PsychologyRadar(value.scores(), { key, score -> onChange(value.withScore(key, score)) }, enabled)
    if (value.mood.isNotBlank()) Text(value.mood, style = MaterialTheme.typography.titleSmall)
    if (value.reason.isNotBlank()) Text(value.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    value.scores().forEach { (label, score) ->
        val momentum = value.momentum[label.lowercase()] ?: 0f
        ScoreSlider(label, score, enabled, if (abs(momentum) >= 1f) (if (momentum > 0) "Rising" else "Settling") else null) { onChange(value.withScore(label,it)) }
    }
}
@Composable
fun PersonalityEditor(value: CharacterPersonalityDto, enabled: Boolean = true, onChange: (CharacterPersonalityDto) -> Unit) {
    PsychologyRadar(value.scores(), { key, score -> onChange(value.withScore(key, score)) }, enabled)
    PsychologyText("Personality & voice", value.description, enabled, 1500) { onChange(value.copy(description=it)) }
    value.scores().forEach { (label, score) -> ScoreSlider(label,score,enabled) { onChange(value.withScore(label,it)) } }
    Text("Volatility sets emotional intensity. Resilience shapes recovery; adaptability allows gradual change through the story.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
private fun ScoreSlider(label: String, value: Int, enabled: Boolean, trend: String? = null, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label, style=MaterialTheme.typography.labelLarge)
            Text(listOfNotNull(trend, value.toString()).joinToString(" · "), style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value=value.coerceIn(0,100).toFloat(),onValueChange={onChange(it.roundToInt())},valueRange=0f..100f,enabled=enabled,
            modifier=Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}
@Composable
fun MindEditor(value: CharacterPsychologyDto, enabled: Boolean = true, onChange: (CharacterPsychologyDto) -> Unit) {
    PsychologyText("Cornerstone", value.cornerstone, enabled, 1200) { onChange(value.copy(cornerstone=it)) }
    Text("A grounding experience, relationship or belief that gives their life meaning. It can evolve as the story changes them.", style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    PsychologyText("Beliefs & ideas · one per line", value.beliefs.joinToString("\n"),enabled) { onChange(value.copy(beliefs=it.lines())) }
    PsychologyText("Wants & desires · one per line",value.desires.joinToString("\n"),enabled) { onChange(value.copy(desires=it.lines())) }
    PsychologyText("Secret desires · one per line",value.secretDesires.joinToString("\n"),enabled) { onChange(value.copy(secretDesires=it.lines())) }
    PsychologyText("Life, backstory & experiences",value.lifeStory,enabled,8000) { onChange(value.copy(lifeStory=it)) }
    PsychologyText("Day-to-day life, work & school",value.dailyLife,enabled) { onChange(value.copy(dailyLife=it)) }
    PsychologyText("Relationships",value.relationships,enabled) { onChange(value.copy(relationships=it)) }
    PsychologyText("Significant events · one per line",value.significantEvents.joinToString("\n"),enabled) { onChange(value.copy(significantEvents=it.lines())) }
}
@Composable
fun PsychologyText(label: String, value: String, enabled: Boolean = true, limit: Int = 3000, onChange: (String) -> Unit) {
    Text(label,style=MaterialTheme.typography.labelLarge)
    AppTextField(value=value,onValueChange={onChange(it.take(limit))},placeholder=label,enabled=enabled,
        modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=6,shape=RoundedCornerShape(16.dp))
}
@Composable
fun CharacterPsychologyEditor(value: CharacterPsychologyDefaultsDto, enabled: Boolean = true,
    isUltra: Boolean = false, onUpgradeUltra: () -> Unit = {}, onChange: (CharacterPsychologyDefaultsDto) -> Unit) {
    PsychologySection("Mind & life",true) { MindEditor(value.psychology,enabled) { onChange(value.copy(psychology=it)) } }
    PsychologySection("Personality") { PersonalityEditor(value.personality,enabled) { onChange(value.copy(personality=it)) } }
    PsychologySection("Default emotions") { EmotionEditor(value.emotions,enabled) { onChange(value.copy(emotions=it)) } }
    PsychologySection("Ultra · Advanced character detail") {
        Text("Shape their inner conflicts, attachment patterns, growth arcs, nuanced boundaries and dialogue examples with 8,000 extra characters.",style=MaterialTheme.typography.bodySmall)
        if (isUltra || value.advancedDefinition.isNotBlank()) PsychologyText("Advanced direction",value.advancedDefinition,enabled && isUltra,8000) {
            onChange(value.copy(advancedDefinition=it))
        }
        if (!isUltra) {
            if (value.advancedDefinition.isNotBlank()) Text("Your character's existing detail is preserved.",style=MaterialTheme.typography.bodySmall)
            Button(onClick=onUpgradeUltra) { Text("Unlock with Ultra") }
        }
    }
    PsychologySection("The user's role in this story") {
        Text("Leave this blank to use their own name or selected persona.",style=MaterialTheme.typography.bodySmall)
        PsychologyText("Default name",value.defaultPersona.name,enabled,80) { onChange(value.copy(defaultPersona=value.defaultPersona.copy(name=it))) }
        PsychologyText("Backstory",value.defaultPersona.backstory,enabled,4000) { onChange(value.copy(defaultPersona=value.defaultPersona.copy(backstory=it))) }
        PsychologyText("Appearance",value.defaultPersona.appearance,enabled,2000) { onChange(value.copy(defaultPersona=value.defaultPersona.copy(appearance=it))) }
        PsychologyText("Pronouns",value.defaultPersona.pronouns,enabled,80) { onChange(value.copy(defaultPersona=value.defaultPersona.copy(pronouns=it))) }
    }
}
