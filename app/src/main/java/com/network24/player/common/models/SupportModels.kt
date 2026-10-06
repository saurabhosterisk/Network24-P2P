package com.network24.player.common.models

import com.google.gson.annotations.SerializedName

/** support_api.php responses (in-app Live Support). */

data class SupportChannel(
    val id: String,
    val name: String,
    val writable: Boolean,
    // Newest message in the channel, for the unread highlight.
    @SerializedName("last_message_id") val lastMessageId: String?
)

data class SupportAuthor(
    val name: String?,
    val avatar: String?,
    val bot: Boolean,
    val staff: Boolean,
    val app: Boolean
)

data class SupportAttachment(
    val url: String?,
    val name: String?,
    val type: String?,
    val width: Int?,
    val height: Int?,
    val size: Long?
) {
    val isImage: Boolean
        get() = type?.startsWith("image/") == true ||
            name?.substringAfterLast('.', "")?.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp")
}

data class SupportEmbed(
    val title: String?,
    val description: String?,
    val url: String?,
    val image: String?
)

data class SupportReply(
    val id: String?,
    val author: String?,
    val text: String?
)

data class SupportMessage(
    val id: String,
    val time: String?,
    val edited: Boolean,
    val author: SupportAuthor,
    val text: String?,
    val attachments: List<SupportAttachment>?,
    val embeds: List<SupportEmbed>?,
    val reply: SupportReply?,
    val pinned: Boolean
)

data class SupportChannelsResponse(
    val result: Boolean,
    val message: String?,
    val channels: List<SupportChannel>?
)

data class SupportMessagesResponse(
    val result: Boolean,
    val message: String?,
    val channel: String?,
    val you: String?,
    val messages: List<SupportMessage>?
)

data class SupportSendResponse(
    val result: Boolean,
    val message: SupportMessage?
)

/** One line of the full-screen AI assistant chat (support_api.php ai_*). */
data class AiMessage(
    val id: Int,
    val from: String,
    val text: String?,
    val time: Long?,
    // Tap-able answers the bot offers ("1. Yes, it's fixed", channel list).
    val choices: List<String>?,
    // "Go to channel" buttons (search results, a channel that was just fixed).
    val channels: List<AiChannel>? = null
) {
    val fromBot: Boolean get() = from == "bot"
}

data class AiChannel(
    val id: Int,
    val name: String?,
    // "Now: <programme> (ends in 20 min)", or the channel's category.
    val info: String?
)

data class AiPollResponse(
    val result: Boolean,
    val online: Boolean,
    // idle / queued / reading / typing - shown as the bot's status line.
    val state: String?,
    val messages: List<AiMessage>?
)

data class AiAskResponse(
    val result: Boolean,
    val online: Boolean,
    val message: AiMessage?
)

/** Error body shape shared by every support_api.php failure. */
data class SupportError(
    val result: Boolean?,
    val error: String?,
    val message: String?
)

/** Web-player state kept on Main for the whole account (support_api.php web_state_get / lock_*). */
data class WebLock(
    val enabled: Boolean?,
    val cats: List<String>?,
    /** false = the customer never set a PIN, so it is the default 0000 */
    val custom_pin: Boolean? = null
)

data class WebStateResponse(
    val ok: Boolean?,
    val recent: List<Int>?,
    val lock: WebLock?,
    val recent_cleared: Long? = null
)

data class WebLockResponse(
    val ok: Boolean?,
    val lock: WebLock?
)

data class WebOkResponse(
    val ok: Boolean?
)
