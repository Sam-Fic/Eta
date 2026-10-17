package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.AppUpdateOffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal object AppUpdateParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLatestRelease(body: String): AppUpdateOffer? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root.boolean("draft") == true || root.boolean("prerelease") == true) return null
        val tagName = root.string("tag_name").orEmpty()
        val versionName = root.string("name")
            ?.takeIf { it.any(Char::isDigit) }
            ?: tagName
        if (versionName.isBlank() && tagName.isBlank()) return null
        val assets = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val apkFiles = assets.filter { asset ->
            asset.string("name").orEmpty().endsWith(".apk", ignoreCase = true)
        }
        val apk = apkFiles.firstOrNull { asset ->
            asset.string("name").orEmpty().startsWith("eta", ignoreCase = true)
        } ?: apkFiles.firstOrNull()
        val notes = root.string("body")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            .orEmpty()
        return AppUpdateOffer(
            versionName = versionName.ifBlank { tagName }.trim(),
            tagName = tagName.trim(),
            notes = notes,
            htmlUrl = root.string("html_url").orEmpty(),
            apkUrl = apk?.string("browser_download_url")?.takeIf { it.startsWith("http") },
            apkName = apk?.string("name"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull
}
