package com.nuvio.app.features.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dışarıdan (nuvio://search?query=...) gelen arama metnini, Arama ekranı açılana kadar saklar.
 * Ekran hazır olunca metni arama kutusuna yazar ve [consume] ile temizler.
 */
internal object SearchDeepLinkRequests {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun request(query: String) {
        _pending.value = query
    }

    fun consume(query: String) {
        if (_pending.value == query) _pending.value = null
    }
}
