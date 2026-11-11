package com.aniob.core.auth

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException

/** Tiny Moshi wrapper so core-agent parses JSON without `org.json` (an Android class). */
object AniobAuthJson {
    private val moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val listAdapter: JsonAdapter<List<Map<String, Any?>>> = moshi.adapter(
        Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
    )

    /** Null for anything that is not a JSON object. Never throws. */
    fun parseObject(text: String): Map<String, Any?>? = try {
        mapAdapter.fromJson(text)
    } catch (e: IOException) {
        null
    } catch (e: JsonDataException) {
        null
    }

    /** Null for anything that is not a JSON array of objects. Never throws. */
    fun parseList(text: String): List<Map<String, Any?>>? = try {
        listAdapter.fromJson(text)
    } catch (e: IOException) {
        null
    } catch (e: JsonDataException) {
        null
    }

    fun toJson(map: Map<String, Any?>): String = mapAdapter.toJson(map)

    fun toJsonList(list: List<Map<String, Any?>>): String = listAdapter.toJson(list)

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun str(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

    fun long(value: Any?): Long? = (value as? Number)?.toLong()
}
