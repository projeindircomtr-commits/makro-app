package com.kanka.makro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** type: saldiri, hedef, hp_pot, mp_pot, skill */
data class Nokta(
    var name: String,
    var type: String,
    var x: Int,
    var y: Int,
    var cd: Float = 0f,
    var on: Boolean = true
)

data class RenkNokta(val x: Int, val y: Int, val color: Int) {
    fun toJson(): JSONObject = JSONObject().put("x", x).put("y", y).put("c", color)

    companion object {
        fun from(o: JSONObject?) = o?.let { RenkNokta(it.getInt("x"), it.getInt("y"), it.getInt("c")) }
    }
}

/** Ekranda aranacak kucuk goruntu (izgara cozunurlugunde RGB) */
class Sablon(
    val w: Int,
    val h: Int,
    val px: IntArray,
    val tol: Int = 0,       // 0 = genel toleransi kullan
    val tx: Float = 0.5f,   // dokunulacak nokta (sablon icinde oran)
    val ty: Float = 0.5f
) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (v in px) a.put(v)
        return JSONObject().put("w", w).put("h", h).put("px", a)
            .put("tol", tol).put("tx", tx.toDouble()).put("ty", ty.toDouble())
    }

    companion object {
        fun from(o: JSONObject?): Sablon? {
            if (o == null) return null
            val a = o.optJSONArray("px") ?: return null
            val w = o.getInt("w")
            val h = o.getInt("h")
            if (a.length() != w * h) return null
            return Sablon(
                w, h, IntArray(a.length()) { a.getInt(it) },
                o.optInt("tol", 0), o.optDouble("tx", 0.5).toFloat(), o.optDouble("ty", 0.5).toFloat()
            )
        }
    }
}

class Config {
    val points = mutableListOf<Nokta>()
    var hp: RenkNokta? = null
    var mp: RenkNokta? = null
    var tgtBar: RenkNokta? = null
    var openT: Sablon? = null
    var collectT: Sablon? = null

    // Genel
    var minutes = 30
    var radius = 18
    var tol = 70
    var ttol = 30

    // Dokunuslar arasi bekleme (ms)
    var minDelay = 180
    var maxDelay = 550

    // Mola
    var pauseChance = 3      // % (her dokunustan sonra)
    var pauseMin = 1200
    var pauseMax = 3500

    // Hedef
    var tgtMin = 2500        // bar kayitli degilse: periyodik secim (ms)
    var tgtMax = 5000
    var tgtFast = 700        // bar kayitliysa: hedef yokken tekrar deneme (ms)

    // Pot ve skill
    var potCd = 1500         // ayni pot icin en az bekleme (ms)
    var skMin = 250          // skill cooldown'una eklenen rastgele sure (ms)
    var skMax = 1500

    // Kutu
    var lootEvery = 350      // ekran tarama araligi (ms)
    var collectWait = 3000   // Open'dan sonra Collect All bekleme (ms)

    // Guvenlik
    var hpStop = 25          // HP bu kadar sn dusuk kalirsa dur (0 = kapali)

    fun toJson(): JSONObject {
        val o = JSONObject()
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject().put("name", p.name).put("type", p.type)
                    .put("x", p.x).put("y", p.y).put("cd", p.cd.toDouble()).put("on", p.on)
            )
        }
        o.put("points", arr)
        hp?.let { o.put("hp", it.toJson()) }
        mp?.let { o.put("mp", it.toJson()) }
        tgtBar?.let { o.put("tgtBar", it.toJson()) }
        openT?.let { o.put("openT", it.toJson()) }
        collectT?.let { o.put("collectT", it.toJson()) }
        o.put("minutes", minutes).put("radius", radius).put("tol", tol).put("ttol", ttol)
            .put("minDelay", minDelay).put("maxDelay", maxDelay)
            .put("pauseChance", pauseChance).put("pauseMin", pauseMin).put("pauseMax", pauseMax)
            .put("tgtMin", tgtMin).put("tgtMax", tgtMax).put("tgtFast", tgtFast)
            .put("potCd", potCd).put("skMin", skMin).put("skMax", skMax)
            .put("lootEvery", lootEvery).put("collectWait", collectWait)
            .put("hpStop", hpStop)
        return o
    }

    fun save(ctx: Context) {
        prefs(ctx).edit().putString("cfg", toJson().toString()).apply()
    }

    companion object {
        private fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences("makro", Context.MODE_PRIVATE)

        private fun profPrefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences("makro_profiller", Context.MODE_PRIVATE)

        fun load(ctx: Context): Config {
            val s = prefs(ctx).getString("cfg", null) ?: return Config()
            return parse(s) ?: Config()
        }

        fun parse(s: String): Config? {
            val c = Config()
            try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("points") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    c.points.add(
                        Nokta(
                            p.getString("name"), p.getString("type"),
                            p.getInt("x"), p.getInt("y"),
                            p.optDouble("cd", 0.0).toFloat(), p.optBoolean("on", true)
                        )
                    )
                }
                c.hp = RenkNokta.from(o.optJSONObject("hp"))
                c.mp = RenkNokta.from(o.optJSONObject("mp"))
                c.tgtBar = RenkNokta.from(o.optJSONObject("tgtBar"))
                c.openT = Sablon.from(o.optJSONObject("openT"))
                c.collectT = Sablon.from(o.optJSONObject("collectT"))
                c.minutes = o.optInt("minutes", c.minutes)
                c.radius = o.optInt("radius", c.radius)
                c.tol = o.optInt("tol", c.tol)
                c.ttol = o.optInt("ttol", c.ttol)
                c.minDelay = o.optInt("minDelay", c.minDelay)
                c.maxDelay = o.optInt("maxDelay", c.maxDelay)
                c.pauseChance = o.optInt("pauseChance", c.pauseChance)
                c.pauseMin = o.optInt("pauseMin", c.pauseMin)
                c.pauseMax = o.optInt("pauseMax", c.pauseMax)
                c.tgtMin = o.optInt("tgtMin", c.tgtMin)
                c.tgtMax = o.optInt("tgtMax", c.tgtMax)
                c.tgtFast = o.optInt("tgtFast", c.tgtFast)
                c.potCd = o.optInt("potCd", c.potCd)
                c.skMin = o.optInt("skMin", c.skMin)
                c.skMax = o.optInt("skMax", c.skMax)
                c.lootEvery = o.optInt("lootEvery", c.lootEvery)
                c.collectWait = o.optInt("collectWait", c.collectWait)
                c.hpStop = o.optInt("hpStop", c.hpStop)
            } catch (e: Exception) {
                return null
            }
            return c
        }

        // ---------- Profiller ----------
        fun profiles(ctx: Context): List<String> =
            profPrefs(ctx).all.keys.sortedBy { it.lowercase() }

        fun saveProfile(ctx: Context, name: String, c: Config) {
            profPrefs(ctx).edit().putString(name, c.toJson().toString()).apply()
        }

        fun loadProfile(ctx: Context, name: String): Boolean {
            val s = profPrefs(ctx).getString(name, null) ?: return false
            val c = parse(s) ?: return false
            c.save(ctx)
            return true
        }

        fun deleteProfile(ctx: Context, name: String) {
            profPrefs(ctx).edit().remove(name).apply()
        }

        fun label(type: String) = when (type) {
            "saldiri" -> "Saldırı"
            "hedef" -> "Mob seç"
            "hp_pot" -> "HP pot"
            "mp_pot" -> "MP pot"
            "skill" -> "Skill"
            else -> type
        }
    }
}
