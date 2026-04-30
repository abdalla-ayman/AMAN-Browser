package com.aman.browser.browser

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserViewModel : ViewModel() {

    private val _currentUrl   = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle    = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading    = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** One-shot user-facing toasts (block reasons, download started, etc.). */
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    fun onUrlChanged(url: String)    { _currentUrl.value = url   }
    fun onTitleChanged(title: String){ _pageTitle.value  = title }
    fun onLoadingChanged(v: Boolean) { _isLoading.value  = v     }

    fun notifyBlocked(reason: String) {
        _toasts.tryEmit("Blocked by Aman: $reason")
    }
    fun notifyDownloadStarted() {
        _toasts.tryEmit("Download started — see Downloads")
    }
}

