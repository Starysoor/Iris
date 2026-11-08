package com.comfyremote.app.data.repository

import com.comfyremote.app.HistoryItem
import com.comfyremote.app.HistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable facade over the JSON result library. File I/O remains intentionally
 * simple, but all mutations are serialized here so concurrent generation tasks
 * cannot race a load-modify-write cycle.
 */
class HistoryRepository(private val store: HistoryStore) {
    private val lock = Any()
    private val _items = MutableStateFlow(store.load())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()
    /** Persistent diagnostic state, independent of the visible result list. */
    val loadError: StateFlow<String?> = store.loadError

    fun load(): List<HistoryItem> = _items.value

    fun refresh(): List<HistoryItem> = synchronized(lock) {
        store.load().also { _items.value = it }
    }

    fun find(id: String): HistoryItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: HistoryItem) = synchronized(lock) {
        store.add(item)
        _items.value = store.load()
    }

    fun toggleFavorite(id: String) = synchronized(lock) {
        val item = _items.value.firstOrNull { it.id == id } ?: return@synchronized
        store.updateMetadata(id, favorite = !item.favorite)
        _items.value = store.load()
    }

    fun setTags(id: String, tags: List<String>) = synchronized(lock) {
        store.updateMetadata(id, tags = tags)
        _items.value = store.load()
    }

    fun delete(id: String) = synchronized(lock) {
        store.delete(id)
        _items.value = store.load()
    }

    fun deleteMany(ids: Collection<String>) = synchronized(lock) {
        store.deleteMany(ids)
        _items.value = store.load()
    }

    fun newImageFile(promptId: String, index: Int) = store.newImageFile(promptId, index)

    fun newMediaFile(promptId: String, index: Int, extension: String) =
        store.newMediaFile(promptId, index, extension)
}
