package com.example.utt_trafficjams.ui.screens.handbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.utt_trafficjams.data.model.HandbookFaqItem
import com.example.utt_trafficjams.data.repository.HandbookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandbookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HandbookRepository()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _faqItems = MutableStateFlow<List<HandbookFaqItem>>(repository.getFaqItems())
    val faqItems: StateFlow<List<HandbookFaqItem>> = _faqItems.asStateFlow()

    fun updateSearchQuery(value: String) {
        _searchQuery.value = value
        _faqItems.value = repository.searchFaq(value)
    }
}
