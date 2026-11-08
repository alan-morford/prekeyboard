package io.github.alan_morford.prekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * GIFs and stickers from KLIPY (docs.klipy.com). The requests go straight from the phone to
 * KLIPY and the pictures are loaded straight from the addresses KLIPY gives, as its terms ask:
 * nothing is kept on disk but the one file being handed to the app it is sent to.
 *
 * The app key is KLIPY's for this keyboard, put in at build time from local.properties
 * (`klipy.key=...`); without one, the GIF and sticker tabs say so.
 *
 * KLIPY counts calls to its API (a key in testing is allowed 100 an hour), not the pictures,
 * which come from its file servers. So each answer is kept for an hour and asked for again
 * only after that, and no more than [HOURLY] calls go out in any hour.
 */
object Klipy {

    enum class Kind(val path: String, val noun: String) { GIF("gifs", "GIFs"), STICKER("stickers", "stickers") }

    /** One rendition of an item: a size (hd, md, sm, xs) in one format (gif, webp, jpg, png, mp4, webm). */
    class File(val url: String, val width: Int, val height: Int, val size: Long)

    class Item(
        val id: String,
        val slug: String,
        val title: String,
        /** size -> format -> file. */
        val files: Map<String, Map<String, File>>,
        /** A tiny blurred picture, as a data: URL, to show till the real one is in. */
        val blur: String?,
    ) {
        fun file(size: String, format: String): File? = files[size]?.get(format)

        /** The first of [sizes] that has [format]. */
        fun first(format: String, vararg sizes: String): File? = sizes.firstNotNullOfOrNull { file(it, format) }

        /** Width over height, from whichever rendition says. */
        val aspect: Float get() {
            val f = files.values.firstNotNullOfOrNull { m -> m.values.firstOrNull { it.width > 0 && it.height > 0 } }
            return if (f == null) 1f else f.width.toFloat() / f.height
        }
    }

    class Page(val items: List<Item>, val page: Int, val hasNext: Boolean)

    /** The build's key; the tests blank it, so they never go to the network. */
    var key: String = BuildConfig.KLIPY_KEY
    val configured: Boolean get() = key.isNotBlank()

    private const val API = "https://api.klipy.com/api/v1/"
    const val PER_PAGE = 24

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** The trending page, or a search when [query] is given; [done] runs on the main thread. */
    fun fetch(context: Context, kind: Kind, query: String?, page: Int, done: (Result<Page>) -> Unit) {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val cacheKey = "${kind.path}|${q ?: ""}|$page"
        val now = SystemClock.elapsedRealtime()
        answers[cacheKey]?.let { (at, p) ->
            if (now - at < KEEP_ANSWERS) { main.post { done(Result.success(p)) }; return }
            answers.remove(cacheKey)
        }
        if (!allowCall(context)) {
            main.post { done(Result.failure(Refused("that's enough for this hour. Try again later."))) }
            return
        }
        val url = buildString {
            append(API).append(enc(key)).append('/').append(kind.path)
            append(if (query.isNullOrBlank()) "/trending" else "/search")
            append("?page=").append(page).append("&per_page=").append(PER_PAGE)
            if (!query.isNullOrBlank()) append("&q=").append(enc(query.trim()))
            append("&customer_id=").append(enc(customerId(context)))
            locale()?.let { append("&locale=").append(it) }
            append("&content_filter=medium")
        }
        pool.execute {
            val r = runCatching { parse(String(get(url), Charsets.UTF_8)) }
            main.post {
                r.onSuccess { answers[cacheKey] = SystemClock.elapsedRealtime() to it }
                done(r)
            }
        }
    }

    /** Answers already had, by kind, search and page, with when they came: an hour, the last few dozen. */
    private val answers = object : LinkedHashMap<String, Pair<Long, Page>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Page>>?) = size > 40
    }
    private const val KEEP_ANSWERS = 60 * 60_000L
    /** Calls to KLIPY's API allowed in any hour, a little under a testing key's 100. */
    const val HOURLY = 90
    private const val CALLS = "klipyCalls"

    /**
     * Whether another call may go out, counting it if so. The times of the last hour's calls
     * are kept in the settings, so the count holds across the keyboard being restarted.
     */
    @Synchronized private fun allowCall(context: Context): Boolean {
        val p = Settings.prefs(context)
        val now = System.currentTimeMillis()
        val recent = (p.getString(CALLS, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { it in (now - 3_600_000L)..now }
        if (recent.size >= HOURLY) return false
        p.edit().putString(CALLS, (recent + now).joinToString(",")).apply()
        return true
    }

    /** Tells KLIPY [item] was sent (its share trigger), from the search [query] if there was one. */
    fun shared(context: Context, kind: Kind, item: Item, query: String?) {
        if (!configured || !allowCall(context)) return
        val url = API + enc(key) + "/" + kind.path + "/share/" + enc(item.slug)
        val body = JSONObject().put("customer_id", customerId(context)).apply { if (!query.isNullOrBlank()) put("q", query) }
        pool.execute {
            runCatching {
                val conn = Net.open(url) {
                    it.requestMethod = "POST"; it.doOutput = true
                    it.connectTimeout = 10_000; it.readTimeout = 10_000
                    it.setRequestProperty("Content-Type", "application/json")
                    it.outputStream.use { o -> o.write(body.toString().toByteArray()) }
                }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    /** The bytes at [url], off the main thread; [done] runs on it. */
    fun load(url: String, done: (ByteArray?) -> Unit) {
        pool.execute {
            val b = runCatching { get(url) }.getOrNull()
            main.post { done(b) }
        }
    }

    fun get(url: String): ByteArray {
        val conn = Net.open(url) { it.connectTimeout = 10_000; it.readTimeout = 20_000 }
        try {
            if (conn.responseCode !in 200..299) {
                // KLIPY says what was wrong ("The provided API key is invalid."): that, not a number.
                val said = runCatching { message(conn.errorStream.bufferedReader().readText()) }.getOrNull()
                throw Refused(said ?: "HTTP ${conn.responseCode}")
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally { conn.disconnect() }
    }

    /** Off the main thread, for work that must follow a download (writing the file to send). */
    fun background(work: () -> Unit) = pool.execute(work)

    fun parse(json: String): Page {
        val root = JSONObject(json)
        if (!root.optBoolean("result", true)) throw Refused(message(json) ?: "KLIPY said no")
        val data = root.optJSONObject("data") ?: throw RuntimeException("no data")
        val list = data.optJSONArray("data")
        val items = ArrayList<Item>()
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            // Only pictures: an ad (not asked for) has no file.
            val fileObj = o.optJSONObject("file") ?: continue
            val files = HashMap<String, Map<String, File>>()
            for (size in fileObj.keys()) {
                val formats = fileObj.optJSONObject(size) ?: continue
                val m = HashMap<String, File>()
                for (format in formats.keys()) {
                    val f = formats.optJSONObject(format) ?: continue
                    val u = f.optString("url")
                    if (u.isNotEmpty()) m[format] = File(u, f.optInt("width"), f.optInt("height"), f.optLong("size"))
                }
                if (m.isNotEmpty()) files[size] = m
            }
            if (files.isEmpty()) continue
            items += Item(o.optString("id"), o.optString("slug"), o.optString("title"), files,
                o.optString("blur_preview").takeIf { it.startsWith("data:") })
        }
        return Page(items, data.optInt("current_page", 1), data.optBoolean("has_next", false))
    }

    /** KLIPY answered, but with no: the reason it gave. */
    class Refused(message: String) : RuntimeException(message)

    /** The reason in an answer of KLIPY's: {"errors":{"message":["..."]}} or {"message":"..."}. */
    private fun message(json: String): String? {
        val o = JSONObject(json)
        o.optJSONObject("errors")?.let { e ->
            e.optJSONArray("message")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
            e.optString("message").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return o.optString("message").takeIf { it.isNotEmpty() }
    }

    /** A stable, anonymous id for this phone's keyboard, as KLIPY asks: a random one, made once. */
    private fun customerId(context: Context): String {
        val p = Settings.prefs(context)
        p.getString(CUSTOMER, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(CUSTOMER, id).apply()
        return id
    }

    private fun locale(): String? = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private const val CUSTOMER = "klipyCustomer"
}
