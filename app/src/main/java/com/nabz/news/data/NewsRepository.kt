package com.nabz.news.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NewsRepository {
    private const val PREFS = "nabz_translation_cache"
    private const val SETTINGS = "nabz_settings"
    private const val NEWS_CACHE = "news_cache_v6"
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun settings(context: Context): SharedPreferences = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val translationLock = Mutex()
    private data class Feed(val name: String, val url: String, val lang: String)

    // Official/public RSS endpoints where available; Google News RSS is used only as a fallback for sources that commonly block automated RSS access.
    private val feeds = listOf(
        Feed("BBC", "https://feeds.bbci.co.uk/news/world/rss.xml", "en"),
        Feed("BBC Persian", "https://feeds.bbci.co.uk/persian/rss.xml", "fa"),
        Feed("Deutsche Welle", "https://rss.dw.com/xml/rss-en-all", "en"),
        Feed("Euronews", "https://www.euronews.com/rss", "en"),
        Feed("France 24", "https://www.france24.com/en/middle-east/rss", "en"),
        Feed("The Guardian", "https://www.theguardian.com/world/middleeast/rss", "en"),
        Feed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "en"),
        Feed("تسنیم", "https://www.tasnimnews.com/fa/rss/feed/0/8/0/مهمترین-اخبار-تسنیم", "fa"),
        Feed("ایسنا", "https://www.isna.ir/rss", "fa"),
        Feed("ایرنا", "https://www.irna.ir/rss", "fa"),
        Feed("فارس", "https://news.google.com/rss/search?q=site%3Afarsnews.ir+when%3A2d&hl=fa&gl=IR&ceid=IR%3Afa", "fa")
    )

    suspend fun fetch(context: Context): List<NewsItem> = withContext(Dispatchers.IO) {
        val enabledFeeds = feeds.filter { settings(context).getBoolean("source_${it.name}", true) }
        val fresh = coroutineScope {
            enabledFeeds.map { feed ->
                async { runCatching { parseFeed(feed) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
            .filter { relevant(it) }
            .map { it.copy(category = classify(it), importance = importance(it), sources = listOf(it.source)) }
            .distinctBy { canonicalId(it.link) }
            .let { clusterRelated(it) }
            .sortedByDescending { it.published }
            .take(80)
        if (fresh.isNotEmpty()) saveCachedNews(context, fresh)
        fresh.ifEmpty { loadCachedNews(context) }.sortedByDescending { it.published }
    }

    fun loadCachedNews(context: Context): List<NewsItem> = runCatching {
        val raw = settings(context).getString(NEWS_CACHE, null) ?: return emptyList()
        gson.fromJson<List<NewsItem>>(raw, object : TypeToken<List<NewsItem>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveCachedNews(context: Context, items: List<NewsItem>) {
        settings(context).edit().putString(NEWS_CACHE, gson.toJson(items.take(80))).apply()
    }

    private fun parseFeed(feed: Feed): List<NewsItem> {
        val req = Request.Builder().url(feed.url).header("User-Agent", "Mozilla/5.0 NabzNews/2.0").build()
        val response = client.newCall(req).execute()
        val xml = response.use { it.body?.string().orEmpty() }
        if (xml.isBlank()) return emptyList()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val desc = item.selectFirst("description")?.text()?.let { Jsoup.parse(it).text() }.orEmpty()
            if (title.isBlank() || link.isBlank()) return@mapNotNull null
            val image = item.selectFirst("content[url], media|content[url], enclosure[url]")?.attr("url")?.ifBlank { null }
                ?: Regex("https?://[^\\s\"']+\\.(?:jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE).find(item.html())?.value
            val rawDate = item.selectFirst("pubDate")?.text()
                ?: item.selectFirst("dc|date")?.text()
                ?: item.selectFirst("published")?.text()
                ?: item.selectFirst("updated")?.text()
            val published = parseDate(rawDate) ?: System.currentTimeMillis()
            NewsItem(canonicalId(link), clean(title), clean(desc), link, feed.name, image, feed.lang, published)
        }
    }


    /** Merge highly similar stories from different outlets into one event card. */
    private fun clusterRelated(items: List<NewsItem>): List<NewsItem> {
        val result = mutableListOf<NewsItem>()
        for (item in items.sortedByDescending { it.published }) {
            val existingIndex = result.indexOfFirst { existing ->
                existing.category == item.category &&
                    kotlin.math.abs(existing.published - item.published) <= 24L * 60L * 60L * 1000L &&
                    titleSimilarity(existing.title, item.title) >= 0.48
            }
            if (existingIndex < 0) {
                result += item.copy(sources = listOf(item.source))
            } else {
                val old = result[existingIndex]
                val sources = (old.sources + item.source).distinct()
                val better = if (item.importance > old.importance) item else old
                result[existingIndex] = better.copy(
                    sources = sources,
                    importance = (maxOf(old.importance, item.importance) + if (sources.size >= 2) 8 else 0).coerceAtMost(100)
                )
            }
        }
        return result
    }

    private fun titleSimilarity(a: String, b: String): Double {
        fun tokens(v: String) = v.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size.toDouble()
    }

    private fun canonicalId(link: String): String = link.trim().lowercase(Locale.ROOT).replace(Regex("[?#].*$"), "").hashCode().toString()

    private fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
        )
        for (p in patterns) runCatching { return SimpleDateFormat(p, Locale.ENGLISH).parse(raw)?.time }
        return null
    }

    private fun relevant(n: NewsItem): Boolean {
        val s = (n.title + " " + n.summary).lowercase(Locale.ROOT)
        val keys = listOf(
            "iran", "tehran", "middle east", "israel", "gaza", "lebanon", "syria", "iraq", "yemen", "saudi", "hormuz", "nuclear", "irgc", "persian gulf",
            "ایران", "تهران", "خاورمیانه", "اسرائیل", "غزه", "لبنان", "سوریه", "عراق", "یمن", "عربستان", "هرمز", "هسته ای", "سپاه", "خلیج فارس"
        )
        return keys.any { s.contains(it) }
    }

    private fun classify(n: NewsItem): NewsCategory {
        val s = (n.title + " " + n.summary).lowercase(Locale.ROOT)
        val iran = listOf("iran", "tehran", "iranian", "ایران", "تهران", "ایرانی", "سپاه", "هسته ای", "nuclear").count { s.contains(it) }
        val me = listOf("israel", "gaza", "lebanon", "syria", "iraq", "yemen", "saudi", "hormuz", "middle east", "اسرائیل", "غزه", "لبنان", "سوریه", "عراق", "یمن", "عربستان", "هرمز", "خاورمیانه").count { s.contains(it) }
        return when {
            iran >= me && iran > 0 -> NewsCategory.IRAN
            me > 0 -> NewsCategory.MIDDLE_EAST
            else -> NewsCategory.WORLD
        }
    }

    /** Heuristic importance score: it is intentionally explainable and local, not a claim about factual truth. */
    private fun importance(n: NewsItem): Int {
        val s = (n.title + " " + n.summary).lowercase(Locale.ROOT)
        var score = 15
        val critical = listOf("breaking", "urgent", "attack", "strike", "war", "missile", "killed", "death", "ceasefire", "nuclear", "sanctions", "earthquake", "explosion", "ترور", "حمله", "جنگ", "موشک", "کشته", "آتش بس", "تحریم", "انفجار", "زلزله", "فوری")
        val strong = listOf("iran", "israel", "gaza", "tehran", "hormuz", "us", "trump", "ایران", "اسرائیل", "غزه", "تهران", "هرمز", "آمریکا")
        score += critical.count { s.contains(it) } * 18
        score += strong.count { s.contains(it) } * 7
        if (n.title.length > 25) score += 5
        if (n.source in setOf("BBC", "BBC Persian", "Deutsche Welle", "France 24", "The Guardian", "Al Jazeera", "ایرنا", "ایسنا", "تسنیم", "فارس")) score += 5
        return score.coerceAtMost(100)
    }

    fun cachedTranslation(context: Context, id: String): String? = prefs(context).getString(id, null)

    fun saveTranslation(context: Context, id: String, text: String) { prefs(context).edit().putString(id, text).apply() }

    suspend fun translateToPersian(text: String): String = translationLock.withLock {
        withContext(Dispatchers.IO) {
            if (text.isBlank() || looksPersian(text)) return@withContext text
            runCatching {
                val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.PERSIAN).build())
                try {
                    tr.downloadModelIfNeeded().await()
                    text.split("\n", limit = 2).map { part ->
                        if (part.isBlank()) part else tr.translate(part).await()
                    }.joinToString("\n")
                } finally {
                    tr.close()
                }
            }.getOrDefault(text)
        }
    }

    private fun looksPersian(text: String): Boolean {
        val fa = text.count { it in '\u0600'..'\u06FF' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return fa >= 8 && fa > latin
    }

    suspend fun dollarRate(): String = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://www.tgju.org/profile/price_dollar_rl").header("User-Agent", "Mozilla/5.0 NabzNews/4.0").build()
            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val doc = Jsoup.parse(html)
            val last = doc.selectFirst("span[data-col='info.last_price']")?.text()?.trim()
                ?: doc.selectFirst("#l-price_dollar_rl")?.text()?.trim()
            val yesterday = doc.selectFirst("span[data-col='info.yesterday']")?.text()?.trim()
            val change = doc.selectFirst("span[data-col='info.change']")?.text()?.trim()
            if (last.isNullOrBlank()) "در دسترس نیست" else {
                fun rialToToman(raw: String): String {
                    val value = raw.replace(",", "").replace("٬", "").replace(" ", "").toLongOrNull() ?: return raw
                    return String.format(Locale.US, "%,d", value / 10L)
                }
                buildString {
                    append(rialToToman(last))
                    append(" تومان")
                    if (!yesterday.isNullOrBlank()) append("\nدیروز: ${rialToToman(yesterday)} تومان")
                    if (!change.isNullOrBlank()) append(" • تغییر: ${rialToToman(change)} تومان")
                }
            }
        }.getOrDefault("در دسترس نیست")
    }

    fun sourceNames(): List<String> = feeds.map { it.name }
    fun isSourceEnabled(context: Context, source: String): Boolean = settings(context).getBoolean("source_$source", true)
    fun setSourceEnabled(context: Context, source: String, enabled: Boolean) = settings(context).edit().putBoolean("source_$source", enabled).apply()

    fun savedIds(context: Context): Set<String> = settings(context).getStringSet("saved_ids", emptySet()) ?: emptySet()
    fun setSavedIds(context: Context, ids: Set<String>) = settings(context).edit().putStringSet("saved_ids", ids).apply()

}
