package com.example.util

object OccurrenceIdentity {
    fun key(provider: String, id: String): String = "${provider.lowercase()}:$id"
    fun canonical(provider: String, id: String, occurrenceId: String?): String {
        val value = occurrenceId?.trim()?.trimEnd('/').orEmpty()
        val inat = Regex("(?:https?://)?(?:www\\.)?inaturalist\\.(?:org|ala\\.org\\.au)/observations/(\\d+)").find(value)
        if (inat != null) return key("inaturalist", inat.groupValues[1])
        if (provider.equals("iNaturalist", true)) return key(provider, id)
        return if (value.isNotBlank()) "occurrence:$value" else key(provider, id)
    }
}
