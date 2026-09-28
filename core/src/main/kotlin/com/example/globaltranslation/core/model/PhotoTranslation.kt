package com.example.globaltranslation.core.model

enum class TextScript(val label: String, val description: String) {
    LATIN("拉丁字母", "英语、法语、意大利语、德语等"),
    CHINESE("中文", "简体、繁体中文"),
    JAPANESE("日文", "日文"),
    KOREAN("韩文", "韩文"),
    DEVANAGARI("天城文", "印地语、马拉地语、尼泊尔语等")
}

data class TargetLanguage(val code: String, val label: String, val instruction: String)

object TargetLanguages {
    val all = listOf(
        TargetLanguage("zh-Hans", "简体中文", "Simplified Chinese"),
        TargetLanguage("zh-Hant", "繁體中文", "Traditional Chinese"),
        TargetLanguage("en", "英语", "English"),
        TargetLanguage("ja", "日语", "Japanese"),
        TargetLanguage("fr", "法语", "French"),
        TargetLanguage("it", "意大利语", "Italian"),
        TargetLanguage("de", "德语", "German"),
        TargetLanguage("es", "西班牙语", "Spanish"),
        TargetLanguage("pt", "葡萄牙语", "Portuguese"),
        TargetLanguage("ko", "韩语", "Korean"),
        TargetLanguage("ru", "俄语", "Russian"),
        TargetLanguage("ar", "阿拉伯语", "Arabic"),
        TargetLanguage("hi", "印地语", "Hindi"),
        TargetLanguage("nl", "荷兰语", "Dutch"),
        TargetLanguage("pl", "波兰语", "Polish"),
        TargetLanguage("tr", "土耳其语", "Turkish"),
        TargetLanguage("th", "泰语", "Thai"),
        TargetLanguage("vi", "越南语", "Vietnamese"),
        TargetLanguage("id", "印度尼西亚语", "Indonesian"),
        TargetLanguage("ms", "马来语", "Malay"),
        TargetLanguage("bn", "孟加拉语", "Bengali")
    )
    fun find(code: String) = all.firstOrNull { it.code == code } ?: all.first()
}

data class PromptTemplate(val id: String, val name: String, val body: String)

data class TranslationSettings(
    val script: TextScript = TextScript.LATIN,
    val targetLanguage: String = "zh-Hans",
    val templates: List<PromptTemplate> = emptyList(),
    val selectedTemplateId: String? = null
) {
    val selectedTemplate get() = templates.firstOrNull { it.id == selectedTemplateId }
    val options get() = TranslationOptions(TargetLanguages.find(targetLanguage), selectedTemplate?.body.orEmpty())
}

data class TranslationOptions(val target: TargetLanguage, val additionalRequirements: String = "")

data class TextBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun clipped(width: Int, height: Int): TextBounds? {
        if (!listOf(left, top, right, bottom).all { it.isFinite() }) return null
        val result = TextBounds(left.coerceIn(0f, width.toFloat()), top.coerceIn(0f, height.toFloat()),
            right.coerceIn(0f, width.toFloat()), bottom.coerceIn(0f, height.toFloat()))
        return result.takeIf { it.width > 0 && it.height > 0 }
    }
}

data class PhotoTextBlock(val id: String, val text: String, val bounds: TextBounds)

data class TranslationResult(
    val translations: Map<String, String>,
    val error: String? = null
)

data class ApiKeyStatus(val isConfigured: Boolean = false, val revision: Long = 0)
