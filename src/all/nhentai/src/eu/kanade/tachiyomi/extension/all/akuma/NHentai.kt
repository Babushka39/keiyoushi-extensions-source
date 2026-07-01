package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NHentai(
    override val lang: String,
    private val nhentaiLang: String,
) : HttpSource(),
    ConfigurableSource {

    override val name = "NHentai"

    override val baseUrl = "https://nhentai.net"

    // need ajdustment to fetch config from https://nhentai.net/api/v2/config
    private val imgServer = "https://i1.nhentai.net"
    private val thumbServer = "https://t1.nhentai.net"

    override val id by lazy { if (lang == "all") 7309872737163460316 else super.id }

    override val supportsLatest = true

    private var nextHash: String? = null
    private val apiPath = "api/v2"

    private val json: Json by injectLazy()
    private var storedToken: String? = null

    private val ddosGuardIntercept = DDosGuardInterceptor(network.client)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(ddosGuardIntercept)
        .addInterceptor(::tokenInterceptor)
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Authorization", "Bearer $apiKey")

    private fun tokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method == "POST" && request.header("X-CSRF-TOKEN") == null) {
            val modifiedRequest = request.newBuilder()
                .addHeader("X-Requested-With", "XMLHttpRequest")

            val token = getToken()
            val response = chain.proceed(
                modifiedRequest
                    .addHeader("X-CSRF-TOKEN", token)
                    .build(),
            )

            if (!response.isSuccessful && response.code == 419) {
                response.close()
                storedToken = null // reset the token
                val newToken = getToken()
                return chain.proceed(
                    modifiedRequest
                        .addHeader("X-CSRF-TOKEN", newToken)
                        .build(),
                )
            }

            return response
        }

        return chain.proceed(request)
    }

    private fun getToken(): String {
        if (storedToken.isNullOrEmpty()) {
            val request = GET(baseUrl, headers)
            val response = client.newCall(request).execute()

            val document = response.asJsoup()
            val token = document.select("head meta[name*=csrf-token]")
                .attr("content")

            if (token.isEmpty()) {
                throw IOException("Unable to find CSRF token")
            }

            storedToken = token
        }

        return storedToken!!
    }

    companion object {
        const val PREFIX_ID = "id:"
        private const val PREF_TITLE = "pref_title"
        private const val PREF_API_KEY = "api_key"
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val displayFullTitle: Boolean get() = preferences.getBoolean(PREF_TITLE, false)

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TITLE
            title = "Display manga title as full title"
            setDefaultValue(false)
        }.also(screen::addPreference)

        val baseUrlPref =
            EditTextPreference(screen.context).apply {
                key = PREF_API_KEY
                title = "insert api key"
                summary = "masukan api key"
                "woilah cik"
                dialogTitle = "ganti lah"
                dialogMessage = "buka di web nya king, cari api keynya"

                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, "restart kang", Toast.LENGTH_LONG).show()
                    true
                }
            }
        screen.addPreference(baseUrlPref)
    }

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, "") ?: ""

    override fun popularMangaRequest(page: Int): Request {
        Log.d("Nhentai", "lewat ke popularMangaRequest")

        val url = if (nhentaiLang.isBlank()) {
            "$baseUrl/search/?q=\"\"&sort=popular-week&page=$page"
        } else {
            "$baseUrl/language/$nhentaiLang/?sort=popular-week&page=$page"
        }

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        Log.d("Nhentai", "lewat ke popularMangaParse")

        val document = response.asJsoup()
        Log.d("Nhentai", "lewat ke popularMangaParse: $document")

        if (document.text().contains("Max keywords of 3 exceeded.")) {
            throw Exception("Login required for more than 3 filters")
        } else if (document.text().contains("Max keywords of 8 exceeded.")) {
            throw Exception("Only max of 8 filters are allowed")
        }

        val mangas = document.select(".gallery").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(
                    element.selectFirst("a.cover")!!.attr("href"),
                )

                title = element.selectFirst(".caption")!!
                    .text()
                    .replace("\"", "")
                    .let {
                        if (displayFullTitle) it else it.shortenTitle()
                    }

                thumbnail_url = element.selectFirst("img")
                    ?.attr("abs:src")
            }
        }

        val hasNextPage =
            document.selectFirst("a.next") != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = if (query.startsWith("https://")) {
        Log.d("Nhentai", "lewat ke fetchSearchManga")

        val url = query.toHttpUrl()
        if (url.host != baseUrl.toHttpUrl().host) {
            throw Exception("Unsupported url")
        }
        val id = url.pathSegments[1]
        fetchSearchManga(page, "$PREFIX_ID$id", filters)
    } else if (query.startsWith(PREFIX_ID)) {
        val url = "/g/${query.substringAfter(PREFIX_ID)}"
        val manga = SManga.create().apply { this.url = url }
        fetchMangaDetails(manga).map {
            MangasPage(listOf(it.apply { this.url = url }), false)
        }
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val request = popularMangaRequest(page)
        Log.d("Nhentai", "lewat ke searchMangaRequest")

        val finalQuery: MutableList<String> = mutableListOf(query)

        if (lang != "all") {
            finalQuery.add("language:$nhentaiLang$")
        }
        filters.forEach { filter ->
            when (filter) {
                is TextFilter -> {
                    if (filter.state.isNotEmpty()) {
                        finalQuery.addAll(
                            filter.state.split(",").filter { it.isNotBlank() }.map {
                                (if (it.trim().startsWith("-")) "-" else "") + "${filter.tag}:\"${it.trim().replace("-", "")}\""
                            },
                        )
                    }
                }

                is OptionFilter -> {
                    if (filter.state > 0) finalQuery.add("opt:${filter.getValue()}")
                }

                is CategoryFilter -> {
                    filter.state.forEach {
                        when {
                            it.isIncluded() -> finalQuery.add("category:\"${it.name}\"")
                            it.isExcluded() -> finalQuery.add("-category:\"${it.name}\"")
                        }
                    }
                }

                else -> {}
            }
        }

        val url = request.url.newBuilder()
            .setQueryParameter("q", finalQuery.joinToString(" "))
            .build()

        return request.newBuilder()
            .url(url)
            .build()
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        Log.d("Nhentai", "lewat ke mangaDetailsRequest")
        val id = manga.url.trimEnd('/').substringAfterLast('/')
//        return GET("$baseUrl/$apiPath/galleries/$id", headers)

        // pake method ke web langsung
        return GET("$baseUrl/g/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        Log.d("Nhentai", "lewat ke mangaDetailsParse")

//        val data = response.parseAs<GalleryDto>()
//        return data.toSManga()

        // pake method ke web langsung
        val document = response.asJsoup()
        Log.d("Nhentai", "$document")
        return SManga.create().apply {
            title = document.select("a[href^=/g/] img").attr("alt")
            thumbnail_url = document.select("a[href^=/g/] img").attr("src")

            val tagContainers = document.select(".tag-container")

            fun tagsFor(label: String): List<String> = tagContainers
                .firstOrNull { it.ownText().trim().startsWith(label) }
                ?.select("a .name")
                ?.eachText()
                ?: emptyList()

            val artists = tagsFor("Artists")
            val groups = tagsFor("Groups")
            val tags = tagsFor("Tags")
            val parodies = tagsFor("Parodies")
            val characters = tagsFor("Characters")
            val languages = tagsFor("Languages")
            val categories = tagsFor("Categories")

            artist = artists.joinToString()
            author = groups.joinToString().ifEmpty { artist }
            genre = tags.joinToString()

            description = buildString {
                parodies.takeIf { it.isNotEmpty() }?.let { append("Parodies: ${it.joinToString()}\n") }
                characters.takeIf { it.isNotEmpty() }?.let { append("Characters: ${it.joinToString()}\n") }
                append("Categories: ${categories.joinToString()}\n")
                append("Languages: ${languages.joinToString()}\n")
            }

            status = SManga.UNKNOWN
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        Log.d("Nhentai", "lewat ke chapterListParse")

        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain("${response.request.url}/1")
                name = "Chapter"
                date_upload = dateFormat.tryParse(document.select(".date .value>time").text())
            },
        )
    }

    override fun pageListRequest(chapter: SChapter): Request {
        Log.d("Nhentai", "masuk ke pageListRequest")
        Log.d("Nhentai", "${chapter.url}")

        // ini pake API
//        val mangaUrl = chapter.url.substringBeforeLast("/").removePrefix("/")
//        return GET("$baseUrl/$mangaUrl", headers)

        // ini fetch langsung ke docs/web

        val id = Regex("""/g/(\d+)/""")
            .find(chapter.url)
            ?.groupValues
            ?.get(1)
            ?: error("Invalid url: ${chapter.url}")

        return GET("$baseUrl/g/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        Log.d("Nhentai", "lewat ke pageListParse")

        //        val data = response.parseAs<GalleryDto>()
        //        return data.pages.mapIndexed { index, page ->
        //            Page(
        //                index = page.number,
        //                url = "$baseUrl/${
        //                    page.path
        //                        .removePrefix("galleries/")
        //                        .substringBeforeLast(".")
        //                }",
        //                imageUrl = "$imgServer/${page.path}",
        //            )
        //        }

        val document = response.asJsoup()
        //        Log.d("Nhentai", "document: $document")
        return document.select(".thumb-container .gallerythumb img").mapIndexed { index, img ->
            val thumbUrl = img.attr("abs:src")
            val fullImageUrl = thumbUrlToFullUrl(thumbUrl)
            Log.d("Nhentai", "before: $thumbUrl")
            Log.d("Nhentai", "after: $fullImageUrl")
            //            val pageUrl = "$baseUrl${img.parent()?.attr(" abs : href ") ?: ""}"
            //            Log.d("Nhentai", pageUrl)
            Page(index = index, imageUrl = fullImageUrl)
        }
    }

    private fun thumbUrlToFullUrl(thumbUrl: String): String {
        return thumbUrl
            .replace(Regex("""//t(\d)\."""), "//i1.") // t2. -> i2.
            .replace(Regex("""(\d+)t\.webp(\.webp)?$"""), "$1.webp") // 1t.webp.webp atau 1t.webp -> 1.webp
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup().select(".entry-content img").attr("abs:src")

    override fun getFilterList(): FilterList = getFilters()

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (nhentaiLang.isBlank()) {
            "$baseUrl/search/?q=\"\"&sort=date&page=$page"
        } else {
            "$baseUrl/language/$nhentaiLang/?sort=date&page=$page"
        }

        return GET(url, headers)
    }
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
}
