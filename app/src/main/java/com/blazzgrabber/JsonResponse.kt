package com.blazzgrabber

/**
 * Membangun JSON satu baris untuk dikirim lewat [android.content.BroadcastReceiver.setResultData].
 * Tanpa dependensi eksternal supaya APK tetap ringan.
 */
class JsonResponse {
    private val fields = LinkedHashMap<String, String>()

    fun bool(key: String, value: Boolean) = apply { fields[key] = value.toString() }
    fun num(key: String, value: Long) = apply { fields[key] = value.toString() }
    fun num(key: String, value: Int) = apply { fields[key] = value.toString() }
    fun str(key: String, value: String) = apply { fields[key] = quote(value) }
    fun raw(key: String, value: String) = apply { fields[key] = value }

    fun build(): String = fields.entries.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ","
    ) { (key, value) -> "${quote(key)}:$value" }

    companion object {
        fun ok() = JsonResponse().bool("ok", true)
        fun fail(error: String) = JsonResponse().bool("ok", false).str("error", error)

        fun quote(value: String): String {
            val builder = StringBuilder(value.length + 2)
            builder.append('"')
            for (character in value) {
                when (character) {
                    '\\' -> builder.append("\\\\")
                    '"' -> builder.append("\\\"")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> if (character < ' ') {
                        builder.append(String.format("\\u%04x", character.code))
                    } else {
                        builder.append(character)
                    }
                }
            }
            builder.append('"')
            return builder.toString()
        }
    }
}
