package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
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
    private val popularSort: String
        get() = preferences.getString(PREF_POPULAR_SORT, "popular-week") ?: "popular-week"

    private val json: Json by injectLazy()
    private var storedToken: String? = null

    private val ddosGuardIntercept = DDosGuardInterceptor(network.client)

    private fun imageServerInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        val isImageServer = imageServers.any { url.startsWith(it) }
        val isThumbServer = thumbServers.any { url.startsWith(it) }

        if (!isImageServer && !isThumbServer) {
            return chain.proceed(request)
        }

        val servers = if (isImageServer) imageServers else thumbServers
        val currentServer = servers.firstOrNull { url.startsWith(it) }
            ?: return chain.proceed(request)
        val remainingServers = servers.filter { it != currentServer }

        // coba server pertama
        val firstResponse = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.d("Nhentai", "server $currentServer gagal: ${e.message}, coba server lain")
            null
        }

        if (firstResponse != null && firstResponse.isSuccessful) return firstResponse
        firstResponse?.close()

        Log.d("Nhentai", "gagal load dari $currentServer, coba server lain")

        // coba server lain
        for (server in remainingServers) {
            val newUrl = url.replace(currentServer, server)
            Log.d("Nhentai", "coba ke server: $newUrl")

            val response = try {
                val newRequest = request.newBuilder().url(newUrl).build()
                chain.proceed(newRequest)
            } catch (e: Exception) {
                Log.d("Nhentai", "server $server juga gagal: ${e.message}")
                null
            }

            if (response != null && response.isSuccessful) {
                Log.d("Nhentai", "berhasil pakai server: $server")
                return response
            }
            response?.close()
        }

        // semua gagal, throw exception
        throw Exception("Semua image server gagal untuk: $url")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(ddosGuardIntercept)
        .addInterceptor(::tokenInterceptor)
        .addInterceptor(::imageServerInterceptor)
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

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val displayFullTitle: Boolean get() = preferences.getBoolean(PREF_TITLE, false)

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, "") ?: ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TITLE
            title = "Display manga title as full title"
            setDefaultValue(false)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POPULAR_SORT
            title = "Popular manga sort"
            entries = arrayOf(
                "Popular: All Time",
                "Popular: Month",
                "Popular: Week",
                "Popular: Today",
            )
            entryValues = arrayOf(
                "popular",
                "popular-month",
                "popular-week",
                "popular-today",
            )
            setDefaultValue("popular-week")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putString(PREF_POPULAR_SORT, newValue as String)
                    .commit()
            }
        }.also(screen::addPreference)

//        val baseUrlPref =
//            EditTextPreference(screen.context).apply {
//                key = PREF_API_KEY
//                title = "insert api key"
//                summary = "masukan api key"
//                "woilah cik"
//                dialogTitle = "ganti lah"
//                dialogMessage = "buka di web nya king, cari api keynya"
//
//                setOnPreferenceChangeListener { _, _ ->
//                    Toast.makeText(screen.context, "restart kang", Toast.LENGTH_LONG).show()
//                    true
//                }
//            }
//        screen.addPreference(baseUrlPref)
    }

    override fun popularMangaRequest(page: Int): Request {
        Log.d("Nhentai", "lewat ke popularMangaRequest")

        val url = if (nhentaiLang.isBlank()) {
            "$baseUrl/search/?q=\"\"&sort=$popularSort&page=$page"
        } else {
            "$baseUrl/language/$nhentaiLang/?sort=$popularSort&page=$page"
        }

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        Log.d("Nhentai", "lewat ke popularMangaParse")

        val document = response.asJsoup()
//        Log.d("Nhentai", "lewat ke popularMangaParse: $document")

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

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (nhentaiLang.isBlank()) {
            "$baseUrl/search/?q=\"\"&sort=date&page=$page"
        } else {
            "$baseUrl/language/$nhentaiLang/?sort=date&page=$page"
        }

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = when {
        // handle full URL paste
        query.startsWith("https://") -> {
            Log.d("Nhentai", "lewat ke searchMangaRequest, dari link")
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments[1]
            fetchSearchManga(page, "$PREFIX_ID$id", filters)
        }
        // handle PREFIX_ID search (misal "id:659710")
        query.startsWith(PREFIX_ID) -> {
            Log.d("Nhentai", "lewat ke searchMangaRequest, dari prefix id:")
            val id = query.substringAfter(PREFIX_ID)
            val manga = SManga.create().apply { url = "/g/$id/" }
            fetchMangaDetails(manga).map {
                MangasPage(listOf(it.apply { url = "/g/$id/" }), false)
            }
        }
        // handle pure integer (langsung ketik ID-nya)
        query.toIntOrNull() != null -> {
            Log.d("Nhentai", "lewat ke searchMangaRequest, full integer")
            val manga = SManga.create().apply { url = "/g/$query/" }
            fetchMangaDetails(manga).map {
                MangasPage(listOf(it.apply { url = "/g/$query/" }), false)
            }
        }
        // normal search
        else -> {
            Log.d("Nhentai", "lewat ke searchMangaRequest, ke else , $query")
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        Log.d("Nhentai", "lewat ke searchMangaRequest")

        val finalQuery: MutableList<String> = mutableListOf(query)

        if (lang != "all") {
            finalQuery.add("language:$nhentaiLang")
        }

        val offsetPage = filters.filterIsInstance<OffsetPageFilter>()
            .firstOrNull()?.state?.toIntOrNull()?.plus(page) ?: page

        val isFavorite = filters.filterIsInstance<FavoriteFilter>()
            .firstOrNull()?.state == true

        // ambil sort value duluan sebelum loop
        val sortValue = filters.filterIsInstance<SortFilter>()
            .firstOrNull()?.getValue() ?: "popular"

        filters.forEach { filter ->
            when (filter) {
                is TextFilter -> {
                    if (filter.state.isNotEmpty()) {
                        val noQuotes = filter.tag == "pages" || filter.tag == "uploaded"
                        finalQuery.addAll(
                            filter.state.split(",").filter { it.isNotBlank() }.map {
                                val trimmed = it.trim()
                                val prefix = if (trimmed.startsWith("-")) "-" else ""
                                val value = trimmed.removePrefix("-")
                                if (noQuotes) {
                                    "$prefix${filter.tag}:$value"
                                } else {
                                    "$prefix${filter.tag}:\"$value\""
                                }
                            },
                        )
                    }
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

        val baseSearchUrl = if (isFavorite) "$baseUrl/favorites/" else "$baseUrl/search/"

        val url = baseSearchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", finalQuery.joinToString(" ").trim().ifBlank { "\"\"" })
            .addQueryParameter("page", offsetPage.toString())
            .apply {
                // sort jadi parameter terpisah, bukan bagian dari q
                if (!isFavorite) addQueryParameter("sort", sortValue)
            }
            .build()

        Log.d("Nhentai", "hasil url: $url")

        return GET(url, headers)
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
//        Log.d("Nhentai", "$document")
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
            .replace(Regex("""//t(\d)\."""), "//i$1.") // t2. -> i2. (fix: preserve angka servernya)
            .replace(Regex("""(\d+)t\.(\w+)(\.\w+)?$"""), "$1.$2") // hapus suffix 't' dan double extension
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup().select(".entry-content img").attr("abs:src")

    override fun getFilterList(): FilterList = getFilters()

    companion object {
        const val PREFIX_ID = "id:"
        private const val PREF_TITLE = "pref_title"
        private const val PREF_API_KEY = "api_key"
        private val imageServers = listOf(
            "https://i1.nhentai.net",
            "https://i2.nhentai.net",
            "https://i3.nhentai.net",
            "https://i4.nhentai.net",
        )

        private val thumbServers = listOf(
            "https://t1.nhentai.net",
            "https://t2.nhentai.net",
            "https://t3.nhentai.net",
            "https://t4.nhentai.net",
        )
        private const val PREF_POPULAR_SORT = "pref_popular_sort"
    }
}
