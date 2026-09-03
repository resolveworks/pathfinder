package works.resolve.pathfinder.ai.api

const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64

fun clampOpenAIPromptCacheKey(key: String?): String? {
    if (key == null) return null
    val chars = key.codePoints().toArray()
    if (chars.size <= OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) return key
    return String(chars, 0, OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH)
}
