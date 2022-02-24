package com.flipperdevices.keyscreen.impl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flipperdevices.bridge.dao.api.delegates.FavoriteApi
import com.flipperdevices.bridge.dao.api.delegates.KeyApi
import com.flipperdevices.bridge.dao.api.delegates.KeyParser
import com.flipperdevices.bridge.dao.api.model.FlipperKeyPath
import com.flipperdevices.core.di.ComponentHolder
import com.flipperdevices.core.log.LogTagProvider
import com.flipperdevices.core.log.warn
import com.flipperdevices.filemanager.api.share.ShareApi
import com.flipperdevices.keyscreen.impl.R
import com.flipperdevices.keyscreen.impl.di.KeyScreenComponent
import com.flipperdevices.keyscreen.impl.model.DeleteState
import com.flipperdevices.keyscreen.impl.model.FavoriteState
import com.flipperdevices.keyscreen.impl.model.KeyScreenState
import com.flipperdevices.keyscreen.impl.model.ShareState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KeyScreenViewModel(
    private val keyPath: FlipperKeyPath?,
    application: Application
) : AndroidViewModel(application), LogTagProvider {
    override val TAG = "KeyScreenViewModel"

    @Inject
    lateinit var keyApi: KeyApi

    @Inject
    lateinit var favoriteApi: FavoriteApi

    @Inject
    lateinit var keyParser: KeyParser

    @Inject
    lateinit var shareApi: ShareApi

    init {
        ComponentHolder.component<KeyScreenComponent>().inject(this)
    }

    private val keyScreenState = MutableStateFlow<KeyScreenState>(KeyScreenState.InProgress)
    private val shareDelegate = ShareDelegate(application, shareApi, keyParser)

    init {
        viewModelScope.launch {
            val keyPathNotNull = if (keyPath == null) {
                keyScreenState.update { KeyScreenState.Error(R.string.keyscreen_error_keypath) }
                return@launch
            } else keyPath
            val flipperKey = keyApi.getKey(keyPathNotNull)
            if (flipperKey == null) {
                keyScreenState.update {
                    KeyScreenState.Error(R.string.keyscreen_error_notfound_key)
                }
                return@launch
            }

            val parsedKey = keyParser.parseKey(flipperKey)
            val isFavorite = favoriteApi.isFavorite(keyPathNotNull)
            keyScreenState.update {
                KeyScreenState.Ready(
                    parsedKey,
                    if (isFavorite) FavoriteState.FAVORITE else FavoriteState.NOT_FAVORITE,
                    ShareState.NOT_SHARING,
                    DeleteState.NOT_DELETED
                )
            }
            return@launch
        }
    }

    fun getKeyScreenState(): StateFlow<KeyScreenState> = keyScreenState

    fun setFavorite(isFavorite: Boolean) {
        val keyPathNotNull = keyPath ?: return
        val state = keyScreenState.value
        if (state !is KeyScreenState.Ready || state.favoriteState == FavoriteState.PROGRESS) {
            warn { "We skip setFavorite, because state is $state" }
            return
        }

        keyScreenState.update {
            if (it is KeyScreenState.Ready) it.copy(favoriteState = FavoriteState.PROGRESS) else it
        }

        viewModelScope.launch {
            favoriteApi.setFavorite(keyPathNotNull, isFavorite)
            keyScreenState.update {
                if (it is KeyScreenState.Ready) it.copy(
                    favoriteState = if (isFavorite) {
                        FavoriteState.FAVORITE
                    } else FavoriteState.NOT_FAVORITE
                ) else it
            }
        }
    }

    fun onShare() {
        val keyPathNotNull = keyPath ?: return
        val state = keyScreenState.value
        if (state !is KeyScreenState.Ready || state.shareState == ShareState.PROGRESS) {
            warn { "We skip onShare, because state is $state" }
            return
        }
        keyScreenState.update {
            if (it is KeyScreenState.Ready) it.copy(shareState = ShareState.PROGRESS) else it
        }

        viewModelScope.launch {
            val flipperKey = keyApi.getKey(keyPathNotNull)
            if (flipperKey == null) {
                keyScreenState.update {
                    KeyScreenState.Error(R.string.keyscreen_error_notfound_key)
                }
                return@launch
            }
            shareDelegate.share(flipperKey)
            keyScreenState.update {
                if (it is KeyScreenState.Ready) it.copy(shareState = ShareState.NOT_SHARING) else it
            }
        }
    }
}