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

/** Error body shape shared by every support_api.php failure. */
data class SupportError(
    val result: Boolean?,
    val error: String?,
    val message: String?
)
