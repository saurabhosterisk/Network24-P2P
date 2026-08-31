package com.network24.player.features.live

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Posts a "channel down" alert to the shared chat room. Was previously duplicated
 * (setupReportButton) in ChannelListActivity and FavoriteChannelsActivity.
 */
object ChannelDownReporter {
    fun report(
        username: String,
        channelName: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val message = "🚨 System Alert: $username reported that the channel '$channelName' is currently down."
        val data = hashMapOf(
            "senderId" to "system_bot",
            "senderName" to "System",
            "text" to message,
            "ts" to FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection("rooms")
            .document("channel_down")
            .collection("messages")
            .add(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { exception -> onFailure(exception) }
    }
}
