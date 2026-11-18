package com.multitrack.app.audio

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 噪声样本库：捕捉/导入的噪声谱落盘，下次开工程还能直接用。
 *
 * 一张谱只有 1025 个 float（约 4KB），Base64 后塞进 SharedPreferences 完全够用，
 * 不值得为它建数据库。上限 12 条，超出时覆盖最旧的一条。
 */
object NoiseProfileStore {

    private const val PREF = "cyril_noise_profiles"
    private const val KEY = "profiles"
    private const val MAX = 12

    fun list(ctx: Context): List<NoiseProfile> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> decode(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 同名覆盖；返回保存后的完整列表 */
    fun save(ctx: Context, profile: NoiseProfile): List<NoiseProfile> {
        val current = list(ctx).filter { it.name != profile.name }.toMutableList()
        current.add(0, profile)
        while (current.size > MAX) current.removeAt(current.size - 1)
        write(ctx, current)
        return current
    }

    fun remove(ctx: Context, name: String): List<NoiseProfile> {
        val next = list(ctx).filter { it.name != name }
        write(ctx, next)
        return next
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun write(ctx: Context, items: List<NoiseProfile>) {
        val arr = JSONArray()
        for (p in items) arr.put(encode(p))
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun encode(p: NoiseProfile): JSONObject {
        val buf = ByteBuffer.allocate(p.magnitude.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in p.magnitude) buf.putFloat(v)
        return JSONObject()
            .put("name", p.name)
            .put("sr", p.sampleRate)
            .put("fft", p.fftSize)
            .put("src", p.source)
            .put("mag", Base64.encodeToString(buf.array(), Base64.NO_WRAP))
    }

    private fun decode(o: JSONObject): NoiseProfile? {
        return try {
            val bytes = Base64.decode(o.getString("mag"), Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes.size / 4
            val mag = FloatArray(n) { buf.getFloat() }
            NoiseProfile(
                name = o.optString("name", "噪声样本"),
                sampleRate = o.optInt("sr", 44100),
                fftSize = o.optInt("fft", 2048),
                magnitude = mag,
                source = o.optString("src", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
