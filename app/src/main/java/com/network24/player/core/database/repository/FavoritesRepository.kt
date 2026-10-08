package com.network24.player.core.database.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.network24.player.core.database.dao.FavoritesDao
import com.network24.player.core.database.entity.FavoriteEntity
import kotlinx.coroutines.tasks.await

/**
 * Favorites: the local table is what the screens read; the account's Firestore document (user_favorites/<user>)
 * keeps the same list so a phone and a TV agree. The order of "items" in the cloud is the viewer's own order.
 */
class FavoritesRepository(
    private val favoritesDao: FavoritesDao,
    private val firestore: FirebaseFirestore
) {
    private fun doc(userId: String) =
        firestore.collection("user_favorites").document(userId)

    suspend fun getFavoriteItemIds(type: String): Set<String> {
        return favoritesDao.getByType(type).map { it.itemId }.toSet()
    }

    suspend fun syncFromCloud(userId: String) {
        try {
            val snapshot = doc(userId).get().await()
            if (!snapshot.exists()) return

            val keys = snapshot.get("items") as? List<*> ?: emptyList<Any>()
            favoritesDao.clearAll()
            keys.forEachIndexed { i, raw ->
                val key = raw?.toString() ?: return@forEachIndexed
                val parts = key.split(":", limit = 2)
                val type = parts.getOrNull(0) ?: return@forEachIndexed
                val itemId = parts.getOrNull(1) ?: return@forEachIndexed

                favoritesDao.upsert(
                    FavoriteEntity(
                        key = key,
                        itemType = type,
                        itemId = itemId,
                        createdAtMs = System.currentTimeMillis(),
                        // the cloud list is in the viewer's order
                        position = i + 1
                    )
                )
            }
        } catch (_: Exception) {
            // offline / error => keep local cache intact
        }
    }

    suspend fun addFavorite(userId: String, type: String, itemId: String) {
        val key = "$type:$itemId"

        // once the viewer has arranged the list, a new favorite goes to its end
        val max = favoritesDao.maxPosition(type) ?: 0
        favoritesDao.upsert(
            FavoriteEntity(
                key = key,
                itemType = type,
                itemId = itemId,
                createdAtMs = System.currentTimeMillis(),
                position = if (max > 0) max + 1 else 0
            )
        )

        val data = mapOf(
            "items" to FieldValue.arrayUnion(key),
            "updatedAtMs" to System.currentTimeMillis()
        )
        doc(userId).set(data, SetOptions.merge()).await()
    }

    suspend fun removeFavorite(userId: String, type: String, itemId: String) {
        val key = "$type:$itemId"

        favoritesDao.deleteByKey(key)

        try {
            doc(userId).update(
                mapOf(
                    "items" to FieldValue.arrayRemove(key),
                    "updatedAtMs" to System.currentTimeMillis()
                )
            ).await()
        } catch (_: Exception) {
            // doc missing => ignore
        }
    }

    /** The viewer's own order for one type: positions 1..n locally, and the whole cloud list rewritten in that order. */
    suspend fun saveOrder(userId: String, type: String, itemIdsInOrder: List<String>) {
        itemIdsInOrder.forEachIndexed { i, id -> favoritesDao.setPosition("$type:$id", i + 1) }
        val all = favoritesDao.getAll()
        val items = itemIdsInOrder.map { "$type:$it" } + all.filter { it.itemType != type }.map { it.key }
        runCatching {
            doc(userId).set(mapOf("items" to items, "updatedAtMs" to System.currentTimeMillis()), SetOptions.merge()).await()
        }
    }
}
