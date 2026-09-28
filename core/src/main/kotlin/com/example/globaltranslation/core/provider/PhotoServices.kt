package com.example.globaltranslation.core.provider

import com.example.globaltranslation.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PhotoTextRecognizer {
    suspend fun recognize(image: Any, script: TextScript): List<PhotoTextBlock>
}

interface PhotoTranslator {
    suspend fun translate(blocks: List<PhotoTextBlock>, options: TranslationOptions, apiKey: String): TranslationResult
}

interface TranslationPreferences {
    val settings: Flow<TranslationSettings>
    suspend fun update(transform: (TranslationSettings) -> TranslationSettings)
}

interface ApiKeyRepository {
    val status: StateFlow<ApiKeyStatus>
    suspend fun read(): String
    suspend fun save(value: String)
    suspend fun clear()
}
