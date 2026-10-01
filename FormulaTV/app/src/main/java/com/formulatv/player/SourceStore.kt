package com.formulatv.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("formula_sources", 0)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("formula_sources_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("formula_sources_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(sources: List<SourceConfig>) {
        val array = JSONArray()
        sources.forEach { s -> array.put(JSONObject().put("id", s.id).put("name", s.name).put("type", s.type.name)
            .put("url", s.url).put("username", s.username).put("password", s.password).put("mac", s.mac)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("sources", Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)).apply()
    }
    fun load(): List<SourceConfig> {
        val stored = prefs.getString("sources", "").orEmpty()
        if (stored.isBlank()) return emptyList()
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { s ->
            SourceConfig(s.getString("id"), s.getString("name"), SourceType.valueOf(s.getString("type")),
                s.getString("url"), s.optString("username"), s.optString("password"), s.optString("mac"))
        } }
    }
    private fun writeEncrypted(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }
    private fun readEncrypted(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun saveItems(name: String, items: List<Channel>) {
        val array = JSONArray()
        items.forEach { c -> array.put(JSONObject().put("id", c.id).put("name", c.name).put("command", c.command)
            .put("category", c.categoryId).put("kind", c.kind.name).put("poster", c.poster).put("series", c.series)
            .put("extension", c.extension).put("container", c.isContainer).put("locked", c.locked)
            .put("is_season", c.isSeason).put("portal_movie", c.portalMovieId).put("portal_season", c.portalSeasonId).put("portal_episode", c.portalEpisodeId)
            .put("season", c.season).put("episode", c.episodeNumber).put("catchup", c.catchupDays)) }
        writeEncrypted(name, array.toString())
    }
    fun loadItems(name: String): List<Channel> = runCatching {
        val array = JSONArray(readEncrypted(name) ?: "[]")
        (0 until array.length()).map { i -> array.getJSONObject(i).let { c ->
            Channel(c.getString("id"), c.getString("name"), c.getString("command"), c.getString("category"),
                MediaKind.valueOf(c.getString("kind")), c.optString("poster"), c.optString("series", "0"),
                c.optString("extension"), isContainer = c.optBoolean("container"), locked = c.optBoolean("locked"),
                season = c.optString("season"), episodeNumber = c.optInt("episode"), catchupDays = c.optInt("catchup"), isSeason = c.optBoolean("is_season"), portalMovieId = c.optString("portal_movie"),
                portalSeasonId = c.optString("portal_season"), portalEpisodeId = c.optString("portal_episode"))
        } }
    }.getOrDefault(emptyList())

}

