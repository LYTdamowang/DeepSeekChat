package com.deepseekchat.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseekchat.data.local.entity.MessageEntity
import com.deepseekchat.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val results: List<MessageEntity> = emptyList(),
    val isSearching: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            sessionRepository.searchMessages(query).collect { results ->
                _state.update { it.copy(results = results, isSearching = false) }
            }
        }
    }
}
