package com.example.globaltranslation.data.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.TranslationPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.photoPreferences by preferencesDataStore(
    name = "photo_translation",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class PhotoPreferences @Inject constructor(@ApplicationContext context: Context) : TranslationPreferences {
    private val store = context.photoPreferences
    private val key = stringPreferencesKey("settings_v1")
    override val settings = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { decode(it[key]) }

    override suspend fun update(transform: (TranslationSettings) -> TranslationSettings) {
        store.edit { preferences -> preferences[key] = encode(transform(decode(preferences[key]))) }
    }

    companion object {
        fun encode(value: TranslationSettings): String = JSONObject().apply {
            put("script", value.script.name)
            put("target", value.targetLanguage)
            put("selected", value.selectedTemplateId ?: JSONObject.NULL)
            put("templates", JSONArray().apply {
                value.templates.forEach { template -> put(JSONObject().apply {
                    put("id", template.id); put("name", template.name); put("body", template.body)
                }) }
            })
        }.toString()

        fun decode(value: String?): TranslationSettings {
            if (value == null) return TranslationSettings()
            return try {
                val json = JSONObject(value)
                val array = json.optJSONArray("templates") ?: JSONArray()
                val templates = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isBlank() || name.isBlank()) null else PromptTemplate(id, name, item.optString("body"))
                }.distinctBy { it.id }
                TranslationSettings(
                    script = TextScript.entries.firstOrNull { it.name == json.optString("script") } ?: TextScript.LATIN,
                    targetLanguage = TargetLanguages.find(json.optString("target")).code,
                    templates = templates,
                    selectedTemplateId = json.optString("selected").takeIf { id -> templates.any { it.id == id } }
                )
            } catch (_: org.json.JSONException) { TranslationSettings() }
        }
    }
}
