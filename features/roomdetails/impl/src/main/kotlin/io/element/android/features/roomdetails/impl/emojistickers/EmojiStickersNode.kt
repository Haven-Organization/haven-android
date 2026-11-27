/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.roomdetails.impl.emojistickers

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.roomdetails.impl.R
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.components.dialogs.ListDialog
import io.element.android.libraries.designsystem.components.dialogs.ListOption
import io.element.android.libraries.designsystem.components.dialogs.SaveChangesDialog
import io.element.android.libraries.designsystem.components.dialogs.SingleSelectionDialog
import io.element.android.libraries.designsystem.components.dialogs.TextFieldDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.emoji.api.imagepacks.ImageUsage
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.StateEventType
import io.element.android.libraries.matrix.api.room.powerlevels.permissionsFlow
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Haven: a room's custom emoji and sticker packs (MSC2545), like Haven Desktop's room settings "Emoji & Stickers" tab.
 * People allowed to send `m.room.image_pack` state can create, edit and delete packs; everyone else can browse them.
 */
@ContributesNode(RoomScope::class)
@AssistedInject
class EmojiStickersNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val room: JoinedRoom,
    matrixClient: MatrixClient,
    @ApplicationContext context: Context,
    private val mediaLoader: MatrixMediaLoader,
    private val pickerProvider: PickerProvider,
    private val imagePackService: ImagePackService,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun onDone()
    }

    private val callback: Callback = callback()
    private val packs = RoomImagePacks(room, matrixClient, context)

    /** So the emoji picker and the composer see the change. */
    private fun refreshPicker() {
        sessionScope.launch { imagePackService.allJoinedRoomPacks(forceRefresh = true) }
    }

    @Composable
    override fun View(modifier: Modifier) {
        val canManage by remember {
            room.permissionsFlow(false) { it.canOwnUserSendState(StateEventType.RoomImagePack) }
        }.collectAsState(false)
        var list by remember { mutableStateOf<List<EditablePack>?>(null) }
        var loadFailed by remember { mutableStateOf(false) }
        var openStateKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            runCatching { packs.load() }
                .onSuccess { list = it }
                .onFailure { loadFailed = true }
        }
        val openPack = list?.firstOrNull { it.stateKey == openStateKey }
        if (openPack != null) {
            PackEditor(
                saved = openPack,
                canManage = canManage,
                onBack = { openStateKey = null },
                onSaved = { updated ->
                    list = list?.map { if (it.stateKey == updated.stateKey) updated else it }
                    refreshPicker()
                },
                modifier = modifier,
            )
        } else {
            PackList(
                packs = list,
                loadFailed = loadFailed,
                canManage = canManage,
                onOpen = { openStateKey = it.stateKey },
                onCreated = { created ->
                    list = list.orEmpty() + created
                    openStateKey = created.stateKey
                },
                onDeleted = { deleted ->
                    list = list?.filter { it.stateKey != deleted.stateKey }
                    refreshPicker()
                },
                modifier = modifier,
            )
        }
    }

    @Composable
    private fun PackList(
        packs: List<EditablePack>?,
        loadFailed: Boolean,
        canManage: Boolean,
        onOpen: (EditablePack) -> Unit,
        onCreated: (EditablePack) -> Unit,
        onDeleted: (EditablePack) -> Unit,
        modifier: Modifier,
    ) {
        val scope = rememberCoroutineScope()
        var newName by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var toDelete by remember { mutableStateOf<EditablePack?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    titleStr = stringResource(R.string.haven_emoji_stickers_title),
                    navigationIcon = { BackButton(onClick = callback::onDone) },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    loadFailed -> CenteredText(stringResource(CommonStrings.error_unknown))
                    packs == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (canManage) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextField(
                                        value = newName,
                                        onValueChange = { newName = it },
                                        label = stringResource(R.string.haven_emoji_stickers_new_pack),
                                        singleLine = true,
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Button(
                                        text = stringResource(R.string.haven_emoji_stickers_create),
                                        enabled = newName.isNotBlank() && !busy,
                                        showProgress = busy,
                                        size = ButtonSize.Medium,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                this@EmojiStickersNode.packs.create(newName, packs)
                                                    .onSuccess {
                                                        newName = ""
                                                        onCreated(it)
                                                    }
                                                    .onFailure { error = it.message ?: it.toString() }
                                                busy = false
                                            }
                                        },
                                    )
                                }
                                HorizontalDivider()
                            }
                        } else {
                            item {
                                Text(
                                    text = stringResource(R.string.haven_emoji_stickers_read_only),
                                    style = ElementTheme.typography.fontBodySmRegular,
                                    color = ElementTheme.colors.textSecondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        if (packs.isEmpty()) {
                            item { CenteredText(stringResource(R.string.haven_emoji_stickers_empty), Modifier.padding(top = 32.dp)) }
                        }
                        items(packs, key = { it.stateKey }) { pack ->
                            PackListItem(
                                headlineContent = pack.displayName,
                                supportingText = pluralStringResource(R.plurals.haven_emoji_stickers_image_count, pack.images.size, pack.images.size),
                                leading = { PackIcon(pack.iconUrl, 40.dp) },
                                trailing = if (canManage) {
                                    {
                                        IconButton(onClick = { toDelete = pack }) {
                                            Icon(CompoundIcons.Delete(), contentDescription = stringResource(CommonStrings.action_delete))
                                        }
                                    }
                                } else {
                                    null
                                },
                                onClick = { onOpen(pack) },
                            )
                        }
                    }
                }
            }
        }
        toDelete?.let { pack ->
            ConfirmationDialog(
                title = stringResource(R.string.haven_emoji_stickers_delete_pack_title),
                content = stringResource(R.string.haven_emoji_stickers_delete_pack_message, pack.displayName),
                submitText = stringResource(CommonStrings.action_delete),
                destructiveSubmit = true,
                onSubmitClick = {
                    toDelete = null
                    scope.launch {
                        this@EmojiStickersNode.packs.delete(pack)
                            .onSuccess { onDeleted(pack) }
                            .onFailure { error = it.message ?: it.toString() }
                    }
                },
                onDismiss = { toDelete = null },
            )
        }
        error?.let { ErrorDialog(content = it, onSubmit = { error = null }) }
    }

    @Composable
    private fun PackEditor(
        saved: EditablePack,
        canManage: Boolean,
        onBack: () -> Unit,
        onSaved: (EditablePack) -> Unit,
        modifier: Modifier,
    ) {
        val scope = rememberCoroutineScope()
        var draft by remember(saved.stateKey) { mutableStateOf(saved) }
        val dirty = canManage && draft != saved
        var busy by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var editingIndex by remember { mutableStateOf<Int?>(null) }
        var choosingUsage by remember { mutableStateOf(false) }
        var addingMxc by remember { mutableStateOf(false) }
        var confirmLeave by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun addImage(url: String, infoJson: String?, shortcode: String) {
            val taken = draft.images.map { it.shortcode }.toSet()
            draft = draft.copy(
                images = draft.images + EditableImage(
                    key = null,
                    shortcode = uniqueShortcode(shortcode, taken),
                    url = url,
                    body = shortcode,
                    usages = draft.usages,
                    infoJson = infoJson,
                    rawJson = null,
                ),
            )
        }

        fun save() {
            busy = true
            scope.launch {
                packs.save(draft)
                    .onSuccess { savedPack ->
                        draft = savedPack
                        onSaved(savedPack)
                    }
                    .onFailure { error = it.message ?: it.toString() }
                busy = false
            }
        }

        val imagePicker = pickerProvider.registerGalleryMultiPicker { uris ->
            if (uris.isEmpty()) return@registerGalleryMultiPicker
            busy = true
            scope.launch {
                uris.forEach { uri ->
                    packs.upload(uri)
                        .onSuccess { addImage(it.url, it.infoJson, it.shortcode) }
                        .onFailure { error = it.message ?: it.toString() }
                }
                busy = false
            }
        }
        val avatarPicker = pickerProvider.registerGalleryImagePicker { uri ->
            uri ?: return@registerGalleryImagePicker
            busy = true
            scope.launch {
                packs.upload(uri)
                    .onSuccess { draft = draft.copy(avatarUrl = it.url) }
                    .onFailure { error = it.message ?: it.toString() }
                busy = false
            }
        }

        val leave = { if (dirty) confirmLeave = true else onBack() }
        BackHandler(onBack = leave)

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    titleStr = draft.displayName.ifBlank { draft.stateKey },
                    navigationIcon = { BackButton(onClick = leave) },
                )
            },
            bottomBar = {
                if (dirty) {
                    Column {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.haven_emoji_stickers_unsaved),
                                style = ElementTheme.typography.fontBodySmRegular,
                                color = ElementTheme.colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                text = stringResource(CommonStrings.action_discard),
                                size = ButtonSize.Medium,
                                enabled = !busy,
                                onClick = { draft = saved },
                            )
                            Button(
                                text = stringResource(CommonStrings.action_save),
                                size = ButtonSize.Medium,
                                enabled = !busy,
                                showProgress = busy,
                                onClick = ::save,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val lcQuery = query.trim().lowercase()
            val shown = draft.images.withIndex().filter { (_, image) ->
                lcQuery.isEmpty() || image.shortcode.lowercase().contains(lcQuery) || image.body.lowercase().contains(lcQuery)
            }
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .then(if (canManage && !busy) Modifier.clickable { avatarPicker.launch() } else Modifier),
                        ) {
                            PackIcon(draft.avatarUrl ?: draft.iconUrl, 64.dp)
                            if (canManage) {
                                Icon(
                                    imageVector = CompoundIcons.Edit(),
                                    contentDescription = stringResource(R.string.haven_emoji_stickers_change_avatar),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(ElementTheme.colors.bgCanvasDefault, RoundedCornerShape(8.dp))
                                        .padding(2.dp)
                                        .size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        TextField(
                            value = draft.displayName,
                            onValueChange = { draft = draft.copy(displayName = it) },
                            label = stringResource(R.string.haven_emoji_stickers_pack_name),
                            singleLine = true,
                            enabled = canManage,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PackListItem(
                        headlineContent = stringResource(R.string.haven_emoji_stickers_usage),
                        supportingText = usageLabel(draft.usages),
                        enabled = canManage,
                        onClick = { choosingUsage = true },
                    )
                    HorizontalDivider()
                    if (draft.images.isNotEmpty()) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            label = stringResource(R.string.haven_emoji_stickers_search),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                when {
                    draft.images.isEmpty() -> item {
                        CenteredText(stringResource(R.string.haven_emoji_stickers_no_images), Modifier.padding(24.dp))
                    }
                    shown.isEmpty() -> item {
                        CenteredText(stringResource(R.string.haven_emoji_stickers_no_match), Modifier.padding(24.dp))
                    }
                }
                items(shown, key = { (index, image) -> image.key ?: "new-$index" }) { (index, image) ->
                    PackListItem(
                        headlineContent = ":${image.shortcode}:",
                        supportingText = image.body.takeIf { it.isNotBlank() && it != image.shortcode },
                        leading = { PackIcon(image.url, 48.dp) },
                        trailing = if (canManage) {
                            {
                                IconButton(onClick = { draft = draft.copy(images = draft.images.filterIndexed { i, _ -> i != index }) }) {
                                    Icon(CompoundIcons.Close(), contentDescription = stringResource(R.string.haven_emoji_stickers_remove_image))
                                }
                            }
                        } else {
                            null
                        },
                        onClick = { editingIndex = index },
                    )
                }
                if (canManage) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                text = stringResource(R.string.haven_emoji_stickers_upload),
                                enabled = !busy,
                                showProgress = busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            )
                            OutlinedButton(
                                text = stringResource(R.string.haven_emoji_stickers_add_mxc),
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { addingMxc = true },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        editingIndex?.let { index ->
            val image = draft.images.getOrNull(index)
            if (image == null) {
                editingIndex = null
            } else {
                ImageDialog(
                    image = image,
                    canManage = canManage,
                    onDone = { updated ->
                        if (updated != null) draft = draft.copy(images = draft.images.mapIndexed { i, it -> if (i == index) updated else it })
                        editingIndex = null
                    },
                )
            }
        }
        if (choosingUsage) {
            UsageDialog(
                selected = draft.usages,
                onSelect = { usages ->
                    // Images that used the pack's usage follow it
                    draft = draft.copy(
                        usages = usages,
                        images = draft.images.map { if (it.usages == draft.usages) it.copy(usages = usages) else it },
                    )
                    choosingUsage = false
                },
                onDismiss = { choosingUsage = false },
            )
        }
        if (addingMxc) {
            val invalid = stringResource(R.string.haven_emoji_stickers_mxc_invalid)
            TextFieldDialog(
                title = stringResource(R.string.haven_emoji_stickers_add_mxc),
                value = "",
                placeholder = "mxc://",
                validation = { it != null && MXC_URL.matches(it.trim()) },
                onValidationErrorMessage = invalid,
                onSubmit = { value ->
                    addingMxc = false
                    val url = value.trim()
                    busy = true
                    scope.launch {
                        addImage(url, imageInfo(url), sanitizeShortcode(url.substringAfterLast('/')))
                        busy = false
                    }
                },
                onDismissRequest = { addingMxc = false },
            )
        }
        if (confirmLeave) {
            SaveChangesDialog(
                onSaveClick = {
                    confirmLeave = false
                    save()
                },
                onDiscardClick = {
                    confirmLeave = false
                    onBack()
                },
                onDismiss = { confirmLeave = false },
            )
        }
        error?.let { ErrorDialog(content = it, onSubmit = { error = null }) }
    }

    /** `info` for an image already on the server, so stickers made from it have a size. */
    private suspend fun imageInfo(url: String): String? {
        val bytes = mediaLoader.loadMediaContent(MediaSource(url)).getOrNull() ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val info = JSONObject().put("size", bytes.size)
        bounds.outMimeType?.let { info.put("mimetype", it) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) info.put("w", bounds.outWidth).put("h", bounds.outHeight)
        return info.toString()
    }

    @Composable
    private fun ImageDialog(image: EditableImage, canManage: Boolean, onDone: (EditableImage?) -> Unit) {
        var shortcode by remember { mutableStateOf(image.shortcode) }
        var body by remember { mutableStateOf(image.body) }
        var usages by remember { mutableStateOf(image.usages) }
        var choosingUsage by remember { mutableStateOf(false) }
        ListDialog(
            title = ":${image.shortcode}:",
            submitText = stringResource(if (canManage) CommonStrings.action_done else CommonStrings.action_close),
            enabled = !canManage || shortcode.isNotBlank(),
            onSubmit = {
                onDone(if (canManage) image.copy(shortcode = sanitizeShortcode(shortcode.trim()), body = body, usages = usages) else null)
            },
            onDismissRequest = { onDone(null) },
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = MediaRequestData(MediaSource(image.url), MediaRequestData.Kind.Content),
                        contentDescription = image.body.ifBlank { image.shortcode },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(160.dp),
                    )
                }
            }
            if (canManage) {
                item {
                    TextField(
                        value = shortcode,
                        onValueChange = { shortcode = it },
                        label = stringResource(R.string.haven_emoji_stickers_shortcode),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    TextField(
                        value = body,
                        onValueChange = { body = it },
                        label = stringResource(R.string.haven_emoji_stickers_description),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (image.body.isNotBlank() && image.body != image.shortcode) {
                item { Text(image.body, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textPrimary) }
            }
            item {
                PackListItem(
                    headlineContent = stringResource(R.string.haven_emoji_stickers_usage),
                    supportingText = usageLabel(usages),
                    enabled = canManage,
                    onClick = { choosingUsage = true },
                )
            }
        }
        if (choosingUsage) {
            UsageDialog(
                selected = usages,
                onSelect = {
                    usages = it
                    choosingUsage = false
                },
                onDismiss = { choosingUsage = false },
            )
        }
    }

    @Composable
    private fun UsageDialog(selected: Set<ImageUsage>, onSelect: (Set<ImageUsage>) -> Unit, onDismiss: () -> Unit) {
        val choices = listOf(ImageUsage.entries.toSet(), setOf(ImageUsage.Emoticon), setOf(ImageUsage.Sticker))
        val labels = choices.map { usageLabel(it) }
        SingleSelectionDialog(
            title = stringResource(R.string.haven_emoji_stickers_usage),
            options = persistentListOf(*labels.map { ListOption(it) }.toTypedArray()),
            initialSelection = choices.indexOf(selected).takeIf { it >= 0 },
            onSelectOption = { onSelect(choices[it]) },
            onDismissRequest = onDismiss,
        )
    }

    private companion object {
        val MXC_URL = Regex("^mxc://[^/]+/[^/]+$")
    }
}

@Composable
private fun usageLabel(usages: Set<ImageUsage>): String = stringResource(
    when {
        ImageUsage.Emoticon in usages && ImageUsage.Sticker in usages -> R.string.haven_emoji_stickers_usage_both
        ImageUsage.Sticker in usages -> R.string.haven_emoji_stickers_usage_stickers
        else -> R.string.haven_emoji_stickers_usage_emoji
    }
)

@Composable
private fun PackIcon(url: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(ElementTheme.colors.bgSubtleSecondary),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = MediaRequestData(MediaSource(url), MediaRequestData.Kind.Thumbnail(if (size <= 48.dp) 96L else 320L)),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        color = ElementTheme.colors.textSecondary,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(24.dp),
    )
}

/** A [ListItem] with a title, optional subtitle and optional leading/trailing content. */
@Composable
private fun PackListItem(
    headlineContent: String,
    supportingText: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        content = { Text(headlineContent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supportingText?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leading?.let { ListItemContent.Custom { _ -> it() } },
        trailingContent = trailing?.let { ListItemContent.Custom { _ -> it() } },
        enabled = enabled,
        onClick = onClick?.takeIf { enabled },
    )
}
