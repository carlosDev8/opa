package de.geeksfactory.opacclient.apis

import de.geeksfactory.opacclient.i18n.StringProvider
import de.geeksfactory.opacclient.networking.HttpClientFactory
import de.geeksfactory.opacclient.objects.*
import de.geeksfactory.opacclient.searchfields.SearchField
import de.geeksfactory.opacclient.searchfields.SearchQuery
import de.geeksfactory.opacclient.searchfields.TextSearchField
import de.geeksfactory.opacclient.utils.get
import de.geeksfactory.opacclient.utils.html
import de.geeksfactory.opacclient.utils.text
import okhttp3.FormBody
import org.joda.time.LocalDateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * OpacApi implementation for SLUB. https://slub-dresden.de
 *
 * @author Steffen Rehberg, Jan 2019
 */
open class SLUB : OkHttpBaseApi() {
    protected lateinit var baseurl: String
    protected val ENCODING = "UTF-8"
    protected lateinit var query: List<SearchQuery>

    private val mediaTypes = mapOf(
            "Article, E-Article" to SearchResult.MediaType.EDOC,
            "Book, E-Book" to SearchResult.MediaType.BOOK,
            "Video" to SearchResult.MediaType.EVIDEO,
            "Thesis" to SearchResult.MediaType.BOOK,
            "Manuscript" to SearchResult.MediaType.BOOK,
            "Musical Score" to SearchResult.MediaType.SCORE_MUSIC,
            "Website" to SearchResult.MediaType.URL,
            "Journal, E-Journal" to SearchResult.MediaType.NEWSPAPER,
            "Map" to SearchResult.MediaType.MAP,
            "Audio" to SearchResult.MediaType.EAUDIO,
            "Image" to SearchResult.MediaType.ART,
            //"Microfrom" to SearchResult.MediaType.MICROFORM    //TODO: define new media type microform
            "Visual Media" to SearchResult.MediaType.ART
    )

    private val fieldCaptions = mapOf(
            "format" to "Medientyp",
            "title" to "Titel",
            "contributor" to "Beteiligte",
            "publisher" to "Erschienen",
            "ispartof" to "Erschienen in",
            "identifier" to "ISBN",
            "language" to "Sprache",
            "subject" to "Schlagwörter",
            "description" to "Beschreibung"
    )

    override fun init(library: Library, factory: HttpClientFactory) {
        super.init(library, factory)
        baseurl = library.data.getString("baseurl")
    }

    override fun search(query: List<SearchQuery>): SearchRequestResult {
        this.query = query
        return searchGetPage(1)
    }

    override fun searchGetPage(page: Int): SearchRequestResult {
        val queryfbb = FormBody.Builder()
                .add("type", "1369315142")
                .add("tx_find_find[format]", "data")
                .add("tx_find_find[data-format]", "app")
                .add("tx_find_find[page]", page.toString())
        for (sq in query) {
            if (sq.value.isNotEmpty()) {
                queryfbb.add("tx_find_find[q][${sq.key}]", sq.value)
            }
        }
        val queryfb = queryfbb.build()
        if (queryfb.size() <= 4)
            throw OpacApi.OpacErrorException(stringProvider.getString(StringProvider.NO_CRITERIA_INPUT))
        val json = JSONObject(httpPost(baseurl, queryfb, ENCODING))
        return SearchRequestResult(json.optJSONArray("docs")
                ?.let { 0.until(it.length()).map { i -> it.optJSONObject(i) } }
                ?.map {
                    SearchResult().apply {
                        innerhtml = "<b>${it.optString("title")}</b><br>${it.optJSONArray("author")?.optString(0)
                                ?: ""}<br>(${it.optString("creationDate")})"
                        type = mediaTypes[it.optJSONArray("format")?.optString(0)]
                        id = it.optString("id")
                    }
                }, json.optInt("numFound"), 1)
        //TODO: get status (one request per item!)
    }

    override fun filterResults(filter: Filter, option: Filter.Option): SearchRequestResult {
        TODO("not implemented")
    }

    override fun getResultById(id: String, homebranch: String?): DetailedItem {

        fun getCopies(copiesArray: JSONArray, df: DateTimeFormatter): List<Copy> =
                copiesArray.run { 0.until(length()).map { optJSONObject(it) } }
                        .map {
                            Copy().apply {
                                barcode = it.getString("barcode")
                                branch = it.getString("location")
                                department = it.getString("sublocation") // or location = ...
                                shelfmark = it.getString("shelfmark")
                                status = Jsoup.parse(it.getString("statusphrase")).text()
                                it.getString("duedate").run {
                                    if (isNotEmpty()) {
                                        returnDate = df.parseLocalDate(this)
                                    }
                                }
                                if (it.getString("vormerken") == "1") {
                                    resInfo = barcode
                                }
                                // reservations: only available for reserved copies, not for reservable copies
                                // url: not for accessible online resources, only for lendable online copies
                            }
                        }

        val detailfb = FormBody.Builder()
                .add("type", "1369315142")
                .add("tx_find_find[format]", "data")
                .add("tx_find_find[data-format]", "app")
        val json = JSONObject(httpPost("$baseurl/id/$id/", detailfb.build(), ENCODING))
        val dateFormat = DateTimeFormat.forPattern("dd.MM.yyyy")

        return DetailedItem().apply {
            this.id = id
            val record = json.optJSONObject("record")
            for (key in record.keys()) {
                val v = record.get(key as String)
                var value = when (v) {
                    is String -> v
                    is Int -> v.toString()
                    is JSONArray -> 0.until(v.length()).map {
                        val arrayItem = v.get(it)
                        when (arrayItem) {
                            is String -> arrayItem
                            is JSONObject -> arrayItem.optString("title")
                            else -> null
                        }
                    }.joinToString("; ")
                    else -> ""
                }
                if (value.isNotEmpty()) {
                    value = Parser.unescapeEntities(value, false)
                    if (key.equals("title")) {
                        title = value
                    }
                    addDetail(Detail(fieldCaptions[key], value))
                }
            }
            val cps = json.opt("copies")
            if (cps is JSONArray) {
                getCopies(cps, dateFormat)?.let { copies = it }
            } else {  // multiple arrays
                val copiesList = mutableListOf<Copy>()
                for (key in (cps as JSONObject).keys()) {
                    val cpsi = cps.get(key as String)
                    if (cpsi is JSONArray) {
                        copiesList.addAll(getCopies(cpsi, dateFormat))
                    }
                }
                copies = copiesList
            }
            // TODO: volumes
            // TODO: collectionid
            // TODO: add linksAccess as detail (uri & hostLabel, note?, material?)
            // TODO: add other links (links, linksRelated, linksGeneral) as details?
        }
    }

    override fun getResult(position: Int): DetailedItem? {
        // not used (getResultById is implemented and every search result has an id set)
        return null
    }

    override fun reservation(item: DetailedItem, account: Account, useraction: Int, selection: String?): OpacApi.ReservationResult {
        TODO("not implemented")
    }

    override fun prolong(media: String, account: Account, useraction: Int, selection: String?): OpacApi.ProlongResult {
        return try {
            requestAccount(account, "renew", mapOf("tx_slubaccount_account[renewals][0]" to media))
            OpacApi.ProlongResult(OpacApi.MultiStepResult.Status.OK)
        } catch (e: Exception) {
            OpacApi.ProlongResult(OpacApi.MultiStepResult.Status.ERROR, e.message)
        }
    }

    override fun prolongAll(account: Account, useraction: Int, selection: String?): OpacApi.ProlongAllResult {
        return OpacApi.ProlongAllResult(OpacApi.MultiStepResult.Status.UNSUPPORTED)
    }

    override fun cancel(media: String, account: Account, useraction: Int, selection: String?): OpacApi.CancelResult {
        TODO("not implemented")
    }

    override fun account(account: Account): AccountData {
        val fmt = DateTimeFormat.shortDate()
        val json = requestAccount(account, "account")
        return AccountData(account.id).apply {
            pendingFees = json.optJSONObject("fees")?.optString("topay_list")
            validUntil = json.optJSONObject("memberInfo")?.optString("expires")
                    ?.substring(0, 10)?.let { fmt.print(LocalDateTime(it)) }
            lent = json.optJSONObject("items")?.optJSONArray("loan")
                    ?.run { 0.until(length()).map { optJSONObject(it) } }
                    ?.map {
                        LentItem().apply {
                            title = it.optString("about")
                            author = it.optJSONArray("X_author")?.optString(0)
                            setDeadline(it.optString("X_date_due"))
                            format = it.optString("X_medientyp")
                            barcode = it.optString("X_barcode")
                            status = when {
                                it.optInt("renewals") == 2 -> "2x verlängert"
                                it.optInt("X_is_reserved") != 0 -> "vorgemerkt"
                                else -> null
                            }
                            isRenewable = if (it.optInt("X_is_renewable") == 1) {
                                prolongData = barcode
                                true
                            } else {
                                false
                            }
                        }
                    } ?: emptyList()
            reservations = json.optJSONObject("items")?.optJSONArray("reserve")
                    ?.run { 0.until(length()).toMutableList().map { optJSONObject(it) } }
                    ?.map {
                        ReservedItem().apply {
                            title = it.optString("about")
                            author = it.optJSONArray("X_author")?.optString(0)
                            format = it.optString("X_medientyp")
                            status = it.optInt("X_queue_number").let { "Pos. $it" }
                        }
                    } ?: emptyList()
        }
    }

    private fun requestAccount(account: Account, action: String, parameters: Map<String, String>? = null): JSONObject {
        val formBody = FormBody.Builder()
                .add("type", "1")
                .add("tx_slubaccount_account[controller]", "API")
                .add("tx_slubaccount_account[action]", action)
                .add("tx_slubaccount_account[username]", account.name)
                .add("tx_slubaccount_account[password]", account.password)
        parameters?.map {
            formBody.add(it.key, it.value)
        }
        try {
            return JSONObject(httpPost("$baseurl/mein-konto/", formBody.build(), ENCODING)).also {
                if (it.optInt("status") != 1) {
                    throw OpacApi.OpacErrorException(stringProvider.getFormattedString(
                            StringProvider.UNKNOWN_ERROR_ACCOUNT_WITH_DESCRIPTION,
                            it.optString("message", "error requesting account data")))
                }
            }
        } catch (e: JSONException) {
            throw OpacApi.OpacErrorException(stringProvider.getFormattedString(
                    StringProvider.UNKNOWN_ERROR_ACCOUNT_WITH_DESCRIPTION,
                    "accountRequest didn't return JSON object: ${e.message}"))
        }
    }

    override fun checkAccountData(account: Account) {
        requestAccount(account, "validate")
    }

    override fun getShareUrl(id: String?, title: String?): String {
        return "$baseurl/id/$id"
    }

    override fun getSupportFlags(): Int {
        return 0
    }

    override fun getSupportedLanguages(): Set<String>? {
        //TODO("not implemented") 
        return null
    }

    override fun parseSearchFields(): List<SearchField> {
        val doc = httpGet(baseurl, ENCODING).html
        return doc.select("ul#search-in-field-options li").map {
            TextSearchField().apply {
                id = it["name"]
                displayName = it.text
            }
        }
    }

    override fun setLanguage(language: String?) {
        //TODO("not implemented")
    }

}
