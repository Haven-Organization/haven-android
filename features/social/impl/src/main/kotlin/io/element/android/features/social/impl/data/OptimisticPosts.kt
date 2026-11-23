/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import androidx.compose.runtime.mutableStateMapOf

/**
 * Likes and reposts shown right away, before the server confirms them: a tap updates the button at once, and is only
 * undone if sending fails. Each entry lasts until the post's live state catches up (or the action fails).
 */
class OptimisticPosts {
    private data class Pending(val liked: Boolean? = null, val reposted: Boolean? = null)

    private val pending = mutableStateMapOf<String, Pending>()

    /** [post] as it will be once its pending like / repost goes through. */
    fun applyTo(post: FeedPost): FeedPost {
        val p = pending[post.listKey] ?: return post
        var shown = post
        if (p.liked != null && p.liked != post.likedByMe) {
            shown = shown.copy(likedByMe = p.liked, likeCount = (post.likeCount + if (p.liked) 1 else -1).coerceAtLeast(0))
        }
        if (p.reposted != null && p.reposted != post.repostedByMe) {
            shown = shown.copy(repostedByMe = p.reposted, repostCount = post.repostCount + if (p.reposted) 1 else 0)
        }
        return shown
    }

    fun isLikePending(post: FeedPost) = pending[post.listKey]?.liked != null

    fun isRepostPending(post: FeedPost) = pending[post.listKey]?.reposted != null

    suspend fun like(post: FeedPost, liked: Boolean, send: suspend () -> Result<Unit>): Result<Unit> {
        pending[post.listKey] = (pending[post.listKey] ?: Pending()).copy(liked = liked)
        return send().also { result ->
            // On success, keep showing it until the live post has the reaction too (the timeline lags a little behind).
            if (result.isFailure) clear(post.listKey) { it.copy(liked = null) }
        }
    }

    suspend fun repost(post: FeedPost, send: suspend () -> Result<Unit>): Result<Unit> {
        pending[post.listKey] = (pending[post.listKey] ?: Pending()).copy(reposted = true)
        return send().also { result ->
            if (result.isFailure) clear(post.listKey) { it.copy(reposted = null) }
        }
    }

    /** Drops entries the live post has caught up with. */
    fun settle(post: FeedPost) {
        if (post.listKey !in pending) return
        clear(post.listKey) {
            it.copy(
                liked = it.liked?.takeIf { liked -> liked != post.likedByMe },
                reposted = it.reposted?.takeIf { reposted -> reposted != post.repostedByMe },
            )
        }
    }

    private fun clear(key: String, update: (Pending) -> Pending) {
        val next = pending[key]?.let(update) ?: return
        if (next.liked == null && next.reposted == null) pending.remove(key) else pending[key] = next
    }
}
