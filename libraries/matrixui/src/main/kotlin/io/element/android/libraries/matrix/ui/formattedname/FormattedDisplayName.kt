/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.ui.formattedname

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/*
 * Haven: formatted names, like Haven Desktop's: an optional `{ format, formatted_body }` object next to a plain name,
 * holding the name as a strictly inline subset of message HTML (bold, italics, underline, strikethrough and custom
 * emoji). Display only, behind the Labs setting.
 * - MSC4589 formatted display names: "m.formatted_displayname" next to `displayname`, in profiles, m.room.member events
 *   and MSC4144 per-message profiles.
 * - MSC4591 formatted room names: "m.formatted_name" next to `name`, in m.room.name, with the same rules.
 *
 * A formatted name is only used when its text (with each emoji replaced by its alt text, which may be empty) is the
 * plain name, once whitespace is normalized in both, so it can never read as a different name than the one used for
 * mentions, disambiguation, search and the rest.
 */

const val FORMATTED_DISPLAYNAME_STABLE_KEY = "m.formatted_displayname"
const val FORMATTED_DISPLAYNAME_UNSTABLE_KEY = "org.matrix.msc4589.formatted_displayname"
const val FORMATTED_ROOM_NAME_STABLE_KEY = "m.formatted_name"
const val FORMATTED_ROOM_NAME_UNSTABLE_KEY = "org.matrix.msc4591.formatted_name"

private const val HTML_FORMAT = "org.matrix.custom.html"
private const val MAX_BYTES = 1024
private const val MAX_IMAGES = 10
private const val MAX_DEPTH = 10

/** An mxc:// URI: a server name (host, IPv4/IPv6 literal, optional port) and a media ID, nothing else. */
private val MXC_URI = Regex("^mxc://[A-Za-z0-9.\\-:\\[\\]]{1,255}/[A-Za-z0-9_-]{1,255}$")

/** A display name with its formatting: runs of styled text and custom emoji. */
@Immutable
data class FormattedName(val runs: ImmutableList<FormattedNameRun>) {
    /** The plain name: the text, with each emoji's alt text. */
    val text: String get() = runs.joinToString("") { it.text }

    /** Whether this is a formatted version of the plain [name] (the consistency rule). */
    fun matches(name: String?): Boolean = name != null && normalizeName(text) == normalizeName(name)
}

/** Runs of whitespace as a single space, without any at the ends: how names are compared. */
fun normalizeName(name: String): String = name.replace(WHITESPACE, " ").trim()

private val WHITESPACE = Regex("\\s+")

@Immutable
sealed interface FormattedNameRun {
    val text: String

    data class Text(
        override val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
    ) : FormattedNameRun

    /**
     * A custom emoji: its image, [text] (its alt text, which may be empty: then it isn't part of the plain name) and
     * [title] (its shortcode, as a rule).
     */
    data class Emoji(val url: String, override val text: String, val title: String = "") : FormattedNameRun {
        /** What it's called, for accessibility and wherever it can't be drawn. */
        val description: String get() = text.ifEmpty { title }
    }
}

/** The raw formatted name object in an event's content or a profile, under either key. */
fun rawFormattedDisplayName(content: JSONObject?): JSONObject? =
    content?.optJSONObject(FORMATTED_DISPLAYNAME_UNSTABLE_KEY) ?: content?.optJSONObject(FORMATTED_DISPLAYNAME_STABLE_KEY)

/** The raw formatted room name object in an m.room.name event's content, under either key. */
fun rawFormattedRoomName(content: JSONObject?): JSONObject? =
    content?.optJSONObject(FORMATTED_ROOM_NAME_UNSTABLE_KEY) ?: content?.optJSONObject(FORMATTED_ROOM_NAME_STABLE_KEY)

/** [rawFormattedDisplayName] of the content in JSON [contentJson]. */
fun rawFormattedDisplayName(contentJson: String?): JSONObject? =
    contentJson?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::rawFormattedDisplayName)

/**
 * The formatted name [raw] to show in place of [displayName] (a display name or a room name), or null when it isn't
 * valid or its text isn't [displayName] (whitespace normalized, see [FormattedName.matches]).
 *
 * Only the permitted tags are kept, with none of their attributes; any other element is dropped, keeping its text (so
 * the text check then usually fails). An image becomes a custom emoji only with `data-mx-emoticon` and a strictly
 * checked mxc:// URI, and its alt text otherwise. Nothing is ever rendered as HTML.
 */
fun parseFormattedDisplayName(raw: JSONObject?, displayName: String?): FormattedName? {
    if (raw == null || displayName.isNullOrEmpty()) return null
    if (raw.opt("format") != HTML_FORMAT) return null
    val body = raw.opt("formatted_body") as? String ?: return null
    if (body.isEmpty() || body.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null

    val runs = mutableListOf<FormattedNameRun>()
    var images = 0
    var tooDeep = false

    fun copy(from: Node, style: FormattedNameRun.Text, depth: Int) {
        if (depth > MAX_DEPTH) {
            tooDeep = true
            return
        }
        for (node in from.childNodes()) {
            when (node) {
                is TextNode -> node.wholeText.takeIf { it.isNotEmpty() }?.let { runs += style.copy(text = it) }
                is Element -> when (val tag = node.normalName()) {
                    "img" -> {
                        images++
                        val alt = node.attr("alt")
                        val src = node.attr("src")
                        runs += if (node.hasAttr("data-mx-emoticon") && MXC_URI.matches(src)) {
                            FormattedNameRun.Emoji(src, alt, node.attr("title"))
                        } else {
                            // Not a custom emoji: shown as its alt text
                            style.copy(text = alt)
                        }
                    }
                    "b", "strong" -> copy(node, style.copy(bold = true), depth + 1)
                    "i", "em" -> copy(node, style.copy(italic = true), depth + 1)
                    "u" -> copy(node, style.copy(underline = true), depth + 1)
                    "s", "del" -> copy(node, style.copy(strikethrough = true), depth + 1)
                    // Not permitted: dropped, keeping its text
                    else -> if (tag != "script" && tag != "style") copy(node, style, depth + 1)
                }
                // Comments and the like
                else -> Unit
            }
        }
    }
    copy(Jsoup.parseBodyFragment(body).body(), FormattedNameRun.Text(""), 0)

    val name = FormattedName(runs.filter { it.text.isNotEmpty() || it is FormattedNameRun.Emoji }.toImmutableList())
    if (tooDeep || images > MAX_IMAGES || !name.matches(displayName)) return null
    return name
}

/** [parseFormattedDisplayName] of the formatted name in [content] (an m.room.member event's, or a profile). */
fun formattedDisplayNameIn(content: JSONObject?, displayName: String?): FormattedName? =
    parseFormattedDisplayName(rawFormattedDisplayName(content), displayName)
