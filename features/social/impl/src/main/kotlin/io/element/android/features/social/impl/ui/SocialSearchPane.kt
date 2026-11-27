/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.features.social.impl.msc.SocialPostParser
import io.element.android.features.social.impl.msc.optStringOrNull
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class SearchTab { People, Groups, Posts }

private data class GroupResult(val roomId: String, val name: String?, val alias: String?, val topic: String?, val avatarUrl: String?, val members: Long)

private data class PostResult(val roomId: String, val eventId: String, val sender: String, val senderName: String?, val senderAvatar: String?, val body: String, val timestamp: Long)

private sealed interface SearchResults<out T> {
    data object Idle : SearchResults<Nothing>
    data object Loading : SearchResults<Nothing>
    data class Loaded<T>(val items: List<T>) : SearchResults<T>
    data class Failed(val message: String) : SearchResults<Nothing>
}

/** Haven Desktop's Search: People | Groups | Posts. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SocialSearchPane(deps: SocialDeps, navigator: SocialNavigator) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(SearchTab.People) }
    // Built once while Search is open, so typing filters it locally
    val localPeople by produceState<List<MatrixUser>?>(null, deps) { value = loadLocalPeople(deps) }
    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.social_search),
            singleLine = true,
            leadingIcon = { Icon(CompoundIcons.Search(), contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            SearchTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = {
                        Text(
                            stringResource(
                                when (item) {
                                    SearchTab.People -> R.string.social_search_people
                                    SearchTab.Groups -> R.string.social_groups
                                    SearchTab.Posts -> R.string.social_search_posts
                                }
                            )
                        )
                    },
                )
            }
        }
        when (tab) {
            SearchTab.People -> PeopleResults(deps, navigator, query, localPeople)
            SearchTab.Groups -> GroupResults(deps, navigator, query)
            SearchTab.Posts -> PostResults(deps, navigator, query)
        }
    }
}

/**
 * Everyone you share a room with, from the members already in the local store, plus the owners of the profile rooms
 * you follow. Synapse's user directory leaves out appservice (bridged) users, so without this, followed fediverse
 * people can't be found. Same idea as Haven Desktop's People search, which merges in your rooms' members.
 */
private suspend fun loadLocalPeople(deps: SocialDeps): List<MatrixUser> = withContext(Dispatchers.Default) {
    val me = deps.matrixClient.sessionId
    val people = LinkedHashMap<UserId, MatrixUser>()
    // Profile room owners first: the room's name and avatar stand in until a member event fills them in
    deps.repository.socialRooms.value.filter { it.kind == SocialRoomKind.Profile }.forEach { room ->
        val owner = room.info.creators.firstOrNull() ?: return@forEach
        people.putIfAbsent(owner, MatrixUser(owner, room.info.name, room.info.avatarUrl))
    }
    val roomIds = deps.matrixClient.getJoinedRoomIds().getOrDefault(emptySet())
    for (roomId in roomIds) {
        val members = deps.matrixClient.getRoom(roomId)?.use { it.cachedMembers() }.orEmpty()
        for (member in members) {
            if (member.membership != RoomMembershipState.JOIN || member.isServiceMember) continue
            val known = people[member.userId]
            if (known == null) {
                people[member.userId] = MatrixUser(member.userId, member.displayName, member.avatarUrl)
            } else if (known.displayName == null || known.avatarUrl == null) {
                people[member.userId] = known.copy(
                    displayName = known.displayName ?: member.displayName,
                    avatarUrl = known.avatarUrl ?: member.avatarUrl,
                )
            }
        }
    }
    people.remove(me)
    people.values.toList()
}

private fun MatrixUser.matches(query: String): Boolean =
    userId.value.contains(query, ignoreCase = true) || displayName?.contains(query, ignoreCase = true) == true

@Composable
private fun PeopleResults(deps: SocialDeps, navigator: SocialNavigator, query: String, localPeople: List<MatrixUser>?) {
    val results by produceState<SearchResults<MatrixUser>>(SearchResults.Idle, query, localPeople) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            value = SearchResults.Idle
            return@produceState
        }
        // People you share a room with show right away, then the directory's matches are added after them
        val local = localPeople.orEmpty().filter { it.matches(trimmed) }
            .sortedWith(compareByDescending<MatrixUser> { it.displayName?.startsWith(trimmed, ignoreCase = true) == true }.thenBy { it.displayName ?: it.userId.value })
        value = if (local.isNotEmpty()) SearchResults.Loaded(local) else SearchResults.Loading
        delay(400)
        val directory = deps.matrixClient.searchUsers(trimmed, 30)
        value = directory.fold(
            onSuccess = { response ->
                val localIds = local.mapTo(HashSet()) { it.userId }
                SearchResults.Loaded(local + response.results.filter { it.userId !in localIds && it.userId != deps.matrixClient.sessionId })
            },
            onFailure = { if (local.isNotEmpty()) SearchResults.Loaded(local) else SearchResults.Failed(it.message ?: it.toString()) },
        )
    }
    ResultList(results, empty = R.string.social_search_people_empty) { user ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { navigator.openUser(user.userId) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(AvatarData(user.userId.value, user.displayName, user.avatarUrl, AvatarSize.RoomListItem), AvatarType.User)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.displayName ?: user.userId.value, style = ElementTheme.typography.fontBodyLgMedium, color = ElementTheme.colors.textPrimary, maxLines = 1)
                Text(user.userId.value, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GroupResults(deps: SocialDeps, navigator: SocialNavigator, query: String) {
    val results by produceState<SearchResults<GroupResult>>(SearchResults.Loading, query) {
        delay(400)
        value = SearchResults.Loading
        // Public room directory filtered to MSC4501 group rooms (MSC3827 room_types), on the user's own server.
        value = deps.httpApi.publicRooms(server = null, query = query.trim(), roomTypes = listOf(Msc4501.ROOM_TYPE_GROUP), limit = 100, since = null).fold(
            onSuccess = { response ->
                val chunk = response.optJSONArray("chunk")
                val items = (0 until (chunk?.length() ?: 0)).mapNotNull { i ->
                    val room = chunk!!.optJSONObject(i) ?: return@mapNotNull null
                    // Also filter client-side, in case the server ignores room_types.
                    if (!Msc4501.isGroupRoomType(room.optStringOrNull("room_type"))) return@mapNotNull null
                    GroupResult(
                        roomId = room.optString("room_id"),
                        name = room.optStringOrNull("name"),
                        alias = room.optStringOrNull("canonical_alias"),
                        topic = room.optStringOrNull("topic"),
                        avatarUrl = room.optStringOrNull("avatar_url"),
                        members = room.optLong("num_joined_members"),
                    )
                }
                SearchResults.Loaded(items)
            },
            onFailure = { SearchResults.Failed(it.message ?: it.toString()) },
        )
    }
    ResultList(results, empty = R.string.social_search_groups_empty) { group ->
        SocialRoomRow(
            roomId = group.roomId,
            name = group.name ?: group.alias,
            avatarUrl = group.avatarUrl,
            topic = group.topic,
            memberCount = group.members,
            isProfile = false,
            onClick = { navigator.openRoomPage(RoomId(group.roomId)) },
        )
    }
}

@Composable
private fun PostResults(deps: SocialDeps, navigator: SocialNavigator, query: String) {
    val rooms by deps.repository.socialRooms.collectAsState()
    val results by produceState<SearchResults<PostResult>>(SearchResults.Idle, query, rooms) {
        if (query.isBlank()) {
            value = SearchResults.Idle
            return@produceState
        }
        delay(400)
        value = SearchResults.Loading
        value = deps.httpApi.searchRoomEvents(query.trim(), rooms.map { it.roomId.value }, nextBatch = null).fold(
            onSuccess = { response ->
                val roomEvents = response.optJSONObject("search_categories")?.optJSONObject("room_events")
                val list = roomEvents?.optJSONArray("results")
                val items = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val result = list!!.optJSONObject(i) ?: return@mapNotNull null
                    val event = result.optJSONObject("result") ?: return@mapNotNull null
                    val post = SocialPostParser.parse(event.toString()) ?: return@mapNotNull null
                    val profile = result.optJSONObject("context")?.optJSONObject("profile_info")
                        ?.optJSONObject(event.optString("sender")) ?: JSONObject()
                    PostResult(
                        roomId = event.optString("room_id"),
                        eventId = event.optString("event_id"),
                        sender = event.optString("sender"),
                        senderName = profile.optStringOrNull("displayname"),
                        senderAvatar = profile.optStringOrNull("avatar_url"),
                        body = post.content.body,
                        timestamp = event.optLong("origin_server_ts"),
                    )
                }
                SearchResults.Loaded(items)
            },
            onFailure = { SearchResults.Failed(it.message ?: it.toString()) },
        )
    }
    ResultList(results, empty = R.string.social_search_posts_empty) { post ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { navigator.openThread(RoomId(post.roomId), post.eventId) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.clickable { navigator.openUser(UserId(post.sender)) }) {
                Avatar(AvatarData(post.sender, post.senderName, post.senderAvatar, AvatarSize.RoomListItem), AvatarType.User, forcedAvatarSize = 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = (post.senderName ?: post.sender) + " · " + formatRelativeTime(post.timestamp),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = ElementTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(post.body, style = ElementTheme.typography.fontBodyLgRegular, color = ElementTheme.colors.textPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun <T> ResultList(results: SearchResults<T>, empty: Int, row: @Composable (T) -> Unit) {
    when (results) {
        SearchResults.Idle -> Unit
        SearchResults.Loading -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SocialSpinner()
        }
        is SearchResults.Failed -> EmptyStateInline(results.message)
        is SearchResults.Loaded -> if (results.items.isEmpty()) {
            EmptyStateInline(stringResource(empty))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(results.items) { item ->
                    row(item)
                    HorizontalDivider()
                }
            }
        }
    }
}
