package com.example.aichat.feature.customization

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.aichat.core.auth.AuthRepository
import com.example.aichat.core.model.CharacterSummary
import com.example.aichat.core.model.CharacterVisibility
import com.example.aichat.core.network.userFacingMessage
import com.example.aichat.core.ui.AppBackButton
import com.example.aichat.core.ui.ScreenBackgroundBox
import com.example.aichat.feature.character.CharacterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    val repository: AppearanceRepository, private val client: OkHttpClient,
    auth: AuthRepository, characters: CharacterRepository
): ViewModel() {
    private val mutableDraft=MutableStateFlow(repository.preferences.value)
    val draft=mutableDraft.asStateFlow()
    private val mutableBusy=MutableStateFlow(false); val busy=mutableBusy.asStateFlow()
    private val mutableStatus=MutableStateFlow<String?>(null); val status=mutableStatus.asStateFlow()
    private val mutableCharacters=MutableStateFlow<List<CharacterSummary>>(emptyList()); val charactersList=mutableCharacters.asStateFlow()
    init {
        perform { repository.refresh(); mutableDraft.value=repository.preferences.value }
        viewModelScope.launch { characters.observeOwnedCharacters(auth.sessionState.value.profile?.userId.orEmpty()).collect { list -> mutableCharacters.value=list.filter { it.visibility==CharacterVisibility.PUBLIC } } }
    }
    fun refreshAccess() { viewModelScope.launch {
        try { repository.refresh(); mutableDraft.value = mutableDraft.value.copy(ultra = repository.preferences.value.ultra) }
        catch (error: CancellationException) { throw error }
        catch (_: Throwable) { }
    } }
    fun edit(value: AppearanceDto) { if(!mutableBusy.value) { mutableDraft.value=value; mutableStatus.value=null } }
    private fun perform(block: suspend ()->Unit) {
        if(mutableBusy.value) return
        mutableBusy.value=true; mutableStatus.value=null
        viewModelScope.launch {
            try { block() } catch(e: Exception) { if(e is CancellationException) throw e; mutableStatus.value=e.userFacingMessage("This change couldn't be completed. Try again.") }
            finally { mutableBusy.value=false }
        }
    }
    fun save(context: Context) = perform {
        repository.save(mutableDraft.value)
        LauncherAppearance.select(context,repository.preferences.value.icon)
        mutableDraft.value=repository.preferences.value; mutableStatus.value="Appearance saved."
    }
    private suspend fun applyAsset(context: Context, target: String, asset: AppearanceAsset) {
        mutableDraft.value=when(target) {
            "banner" -> mutableDraft.value.copy(bannerId=asset.id,bannerUrl=asset.url)
            "profile" -> mutableDraft.value.copy(profileBackgroundId=asset.id,profileBackgroundUrl=asset.url)
            "background" -> mutableDraft.value.copy(background="image",backgroundId=asset.id,backgroundUrl=asset.url)
            else -> mutableDraft.value
        }
        if(target=="icon") {
            val accepted=LauncherAppearance.pinCustom(context,asset.url,client)
            mutableStatus.value=if(accepted) "Confirm the custom Meek shortcut on your home screen." else "This launcher doesn't support custom shortcuts. Choose a preset app icon instead."
        }
    }
    fun upload(context: Context,target: String,uri: Uri)=perform { applyAsset(context,target,repository.upload(if(target=="profile") "background" else target,uri)) }
    fun generate(context: Context,target: String,prompt: String)=perform { applyAsset(context,target,repository.generate(if(target=="profile") "background" else target,prompt)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceRoute(onBack: ()->Unit, onUpgradeUltra: ()->Unit = {}, viewModel: AppearanceViewModel=hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val characters by viewModel.charactersList.collectAsStateWithLifecycle()
    val context=LocalContext.current
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) { viewModel.refreshAccess(); onPauseOrDispose { } }
    var uploadTarget by remember { mutableStateOf("background") }
    var generationTarget by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.upload(context,uploadTarget,it) } }
    ScreenBackgroundBox {
        LazyColumn(modifier=Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) { AppBackButton(onClick=onBack); Text("Your Meek",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f)); if(draft.ultra) TextButton(onClick={viewModel.save(context)},enabled=!busy) { Text("Save") } } }
            if(busy) item { LinearProgressIndicator(modifier=Modifier.fillMaxWidth()) }
            status?.let { message -> item { Text(message,style=MaterialTheme.typography.bodyMedium) } }
            if(!draft.ultra && !busy) {
                item { Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("Make Meek yours",style=MaterialTheme.typography.headlineSmall); Text("Ultra unlocks profile frames, fonts, featured characters, backgrounds and custom app icons."); Button(onClick=onUpgradeUltra) { Text("Explore Meek Ultra") } } } }
            } else if(draft.ultra) {
                item { Text("Profile",style=MaterialTheme.typography.titleLarge) }
                item { AppearanceProfileHeader(name="Your profile",avatarUrl=null,stats=emptyList(),appearance=draft) }
                item { ChoiceRow("Profile font",listOf("default","sans","serif","mono","rounded"),draft.profileFont,!busy) { viewModel.edit(draft.copy(profileFont=it)) } }
                item { ChoiceRow("Profile frame",listOf("none","halo","orbit","laurel","prism"),draft.frame,!busy) { viewModel.edit(draft.copy(frame=it)) } }
                item { ImageActions("Profile banner",busy,onUpload={uploadTarget="banner";picker.launch("image/*")},onGenerate={generationTarget="banner"},onClear={viewModel.edit(draft.copy(bannerId="",bannerUrl=null))}) }
                item { ImageActions("Profile background",busy,onUpload={uploadTarget="profile";picker.launch("image/*")},onGenerate={generationTarget="profile"},onClear={viewModel.edit(draft.copy(profileBackgroundId="",profileBackgroundUrl=null))}) }
                item { Text("Featured character",style=MaterialTheme.typography.titleMedium); Text("Choose a public character you created.",style=MaterialTheme.typography.bodySmall) }
                item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { item { FilterChip(selected=draft.featuredCharacterId.isEmpty(),onClick={viewModel.edit(draft.copy(featuredCharacterId=""))},label={Text("None")},enabled=!busy) }; items(characters,key={it.id}) { c -> FilterChip(selected=draft.featuredCharacterId==c.id,onClick={viewModel.edit(draft.copy(featuredCharacterId=c.id))},label={Text(c.name)},enabled=!busy) } } }
                item { Text("Public profile widgets",style=MaterialTheme.typography.titleMedium); Text("Only the widgets you select are visible to other people.",style=MaterialTheme.typography.bodySmall) }
                items(listOf("charactersChatted" to "Characters chatted", "longestChat" to "Longest chat · message count", "favorites" to "Favorite characters", "created" to "Most popular creations", "recommended" to "Your most chatted characters")) { (key,label) ->
                    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=key in draft.widgets,onCheckedChange={checked -> viewModel.edit(draft.copy(widgets=if(checked) draft.widgets+key else draft.widgets-key))},enabled=!busy); Text(label) }
                }
                item { HorizontalDivider(); Text("App background",style=MaterialTheme.typography.titleLarge) }
                item { Box(Modifier.fillMaxWidth().height(150.dp)) { AppearanceBackdrop(draft,Modifier.fillMaxSize()); Surface(modifier=Modifier.align(Alignment.Center),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.9f)) { Text("A little more you",Modifier.padding(16.dp)) } } }
                item { ChoiceRow("Choose a mood",listOf("default","aurora","midnight","rose","paper","image"),draft.background,!busy) { viewModel.edit(draft.copy(background=it)) } }
                item { ImageActions("Custom background",busy,onUpload={uploadTarget="background";picker.launch("image/*")},onGenerate={generationTarget="background"},onClear={viewModel.edit(draft.copy(background="default",backgroundId="",backgroundUrl=null))}) }
                item { HorizontalDivider(); Text("App icon",style=MaterialTheme.typography.titleLarge) }
                item { ChoiceRow("Launcher icon",listOf("default","midnight","rose","mint","sunset"),draft.icon,!busy) { viewModel.edit(draft.copy(icon=it)) } }
                item { Text("Preset icons replace your app icon. An uploaded or generated image creates a custom home screen shortcut, which Android lets you confirm.",style=MaterialTheme.typography.bodySmall) }
                item { ImageActions("Custom shortcut icon",busy,onUpload={uploadTarget="icon";picker.launch("image/*")},onGenerate={generationTarget="icon"}) }
                item { Button(onClick={viewModel.save(context)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Save appearance") } }
            }
        }
    }
    generationTarget?.let { target ->
        AlertDialog(onDismissRequest={if(!busy) generationTarget=null},title={Text("Imagine your ${if(target=="profile") "profile background" else target}")},text={Column { OutlinedTextField(value=prompt,onValueChange={prompt=it.take(1200)},label={Text("Describe the image")},minLines=3); Text("Up to 10 generated images each day.",style=MaterialTheme.typography.bodySmall) }},confirmButton={TextButton(onClick={viewModel.generate(context,target,prompt);generationTarget=null;prompt=""},enabled=prompt.trim().length>=8&&!busy) { Text("Generate") }},dismissButton={TextButton(onClick={generationTarget=null}) { Text("Cancel") }})
    }
}
@Composable
private fun ChoiceRow(
    title: String,
    choices: List<String>,
    selected: String,
    enabled: Boolean,
    onChoose: (String) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(choices) { item ->
                FilterChip(
                    selected = item == selected,
                    onClick = { onChoose(item) },
                    enabled = enabled,
                    label = { Text(item.replaceFirstChar(Char::uppercase)) }
                )
            }
        }
    }
}

@Composable
private fun ImageActions(
    title: String,
    busy: Boolean,
    onUpload: () -> Unit,
    onGenerate: () -> Unit,
    onClear: (() -> Unit)? = null
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUpload, enabled = !busy) {
                Text("Upload")
            }
            OutlinedButton(onClick = onGenerate, enabled = !busy) {
                Text("Generate")
            }
            onClear?.let { clear ->
                TextButton(onClick = clear, enabled = !busy) {
                    Text("Remove")
                }
            }
        }
    }
}
