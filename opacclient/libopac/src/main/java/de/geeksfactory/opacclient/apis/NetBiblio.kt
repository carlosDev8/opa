package de.geeksfactory.opacclient.apis

import de.geeksfactory.opacclient.i18n.StringProvider
import de.geeksfactory.opacclient.networking.HttpClientFactory
import de.geeksfactory.opacclient.objects.*
import de.geeksfactory.opacclient.searchfields.DropdownSearchField
import de.geeksfactory.opacclient.searchfields.SearchField
import de.geeksfactory.opacclient.searchfields.SearchQuery
import de.geeksfactory.opacclient.searchfields.TextSearchField
import de.geeksfactory.opacclient.utils.get
import de.geeksfactory.opacclient.utils.html
import de.geeksfactory.opacclient.utils.jsonObject
import de.geeksfactory.opacclient.utils.text
import okhttp3.FormBody
import okhttp3.HttpUrl
import org.joda.time.format.DateTimeFormat
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URL
import java.time.LocalDate

open class NetBiblio : OkHttpBaseApi() {
    protected lateinit var opacUrl: String
    protected val ENCODING = "UTF-8"
    protected val PAGE_SIZE = 25
    protected var lang = "en"
    protected var searchResultId: String? = null

    override fun init(library: Library, http_client_factory: HttpClientFactory?) {
        super.init(library, http_client_factory)
        try {
            this.opacUrl = library.data["baseurl"] as String
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
    }

    override fun start() {
        super.start()
        val langCode = when {
            supportedLanguages.contains(lang) -> lang
            supportedLanguages.contains("en")
                // Fall back to English if language not available
            -> "en"
            supportedLanguages.contains("de")
                // Fall back to German if English not available
            -> "de"
            else -> ""
        }
        httpGet("$opacUrl/Site/ChangeLanguage?language=$langCode", ENCODING).html
    }

    override fun parseSearchFields(): List<SearchField>? {
        if (!initialised) start()

        val doc = httpGet("$opacUrl/search/extended", ENCODING).html

        // text fields
        val options = doc.select(".wo-searchfield-dropdown").first().select("option")
        val textFields = options.map { o ->
            TextSearchField().apply {
                id = o["value"]
                displayName = o.text
            }
        }

        // filter fields
        val filterFields = doc.select(".wo-filterfield").flatMap { panel ->
            val title = panel.select(".panel-title").text.trim()
            when (panel["data-filterfieldtype"]) {
                "Checkbox" -> {
                    val checkboxes = panel.select("input[type=checkbox]")
                    val modeSelector = panel.select("input[type=radio][name$=-mode][checked]").first()
                    listOf(
                            DropdownSearchField().apply {
                                id = checkboxes[0]["name"]
                                displayName = title
                                dropdownValues = listOf(DropdownSearchField.Option("", "")) +
                                        checkboxes.map { checkbox ->
                                            DropdownSearchField.Option(
                                                    checkbox["value"],
                                                    checkbox.nextElementSibling().text.trim())
                                        }
                                if (modeSelector != null) {
                                    data = JSONObject().apply {
                                        put("modeKey", modeSelector.attr("name"))
                                        put("modeValue", modeSelector.attr("value"))
                                    }
                                }
                            }
                    )
                }
                "Date" -> {
                    val textBoxes = panel.select("input[type=text]")
                    textBoxes.mapIndexed { i, field ->
                        TextSearchField().apply {
                            id = field["name"]
                            displayName = title
                            hint = (field.previousSibling() as TextNode).text.trim()
                            isHalfWidth = i == 1
                        }
                    }
                }
                else -> emptyList()
            }
        }

        return textFields + filterFields
    }

    override fun search(query: List<SearchQuery>): SearchRequestResult {
        if (!initialised) start()
        searchResultId = null
        val body = FormBody.Builder()

        var textCount = 0
        for (q in query) {
            if (q.searchField is TextSearchField && !q.key.startsWith("Filter.")) {
                if (q.value.isNullOrBlank()) continue
                if (textCount == 1) {
                    body.add("Request.SearchOperator", "AND")
                } else if (textCount >= 2) {
                    throw OpacApi.OpacErrorException(stringProvider.getString(StringProvider.COMBINATION_NOT_SUPPORTED))
                }
                body.add("Request.SearchTerm", q.value)
                body.add("Request.SearchField", q.key)
                textCount++
            }
        }
        if (textCount == 0) {
            body.add("Request.SearchTerm", "")
            body.add("Request.SearchField", "W")
            textCount++
        }
        if (textCount == 1) {
            body.add("Request.SearchOperator", "AND")
            body.add("Request.SearchTerm", "")
            body.add("Request.SearchField", "W")
        }

        for (q in query) {
            if (q.searchField is TextSearchField && q.key.startsWith("Filter.")) {
                body.add(q.key, q.value)
            } else if (q.searchField is DropdownSearchField) {
                if (q.searchField.data != null && q.searchField.data.has("modeKey")) {
                    body.add(q.searchField.data.getString("modeKey"), q.searchField.data.getString("modeValue"))
                }
                body.add(q.key, q.value)
            }
        }

        body.add("Request.PageSize", "$PAGE_SIZE")

        val doc = httpPost("$opacUrl/search/extended/submit", body.build(), ENCODING).html
        return parseSearch(doc, 1)
    }

    protected open fun parseSearch(doc: Document, page: Int): SearchRequestResult {
        // save search result ID for later access
        val href = doc.select(".next-page a").first()?.attr("href")
        if (href != null) searchResultId = BaseApi.getQueryParamsFirst(href)["searchResultId"]

        val resultcountElem = doc.select(".wo-grid-meta-resultcount").first()
                ?: return SearchRequestResult(emptyList(), 0, page)
        val countText = resultcountElem.text
        val totalCount = Regex("\\d+").findAll(countText).last().value.toInt()

        // status of eBooks is fetched via AJAX
        val divibibStatus = getDivibibStatus(doc)

        val results = doc.select(".wo-grid-table tbody tr").groupBy { row ->
            // there are multiple rows for one entry. Their CSS IDs all have the same prefix
            val id = row.attr("id")
            Regex("wo-row_(\\d+)").find(id)!!.groupValues[1]
        }.map { entry ->
            val rows = entry.value
            val firstRow = rows[0]
            val cols = firstRow.select("td[style=font-size: 14px;]")
            val titleCol = cols.first()
            SearchResult().apply {
                val title = titleCol.select("a").text
                val author = titleCol.ownText()
                val moreInfo = cols.drop(1).map { it.text }.joinToString(" / ")
                this.id = "noticeId=${entry.key}"
                innerhtml = "<b>$title</b><br>${author ?: ""}<br>${moreInfo ?: ""}"
                cover = firstRow.select(".wo-cover").first()?.attr("src")

                status = divibibStatus[entry.key] ?: getStatus(firstRow)
            }
        }

        return SearchRequestResult(results, totalCount, page)
    }

    private fun getStatus(elem: Element): SearchResult.Status? {
        val availIcon = elem.select(".wo-disposability-icon").first()
        if (availIcon != null) {
            val src = availIcon.attr("src")
            return when {
                src.endsWith("yes.png") -> SearchResult.Status.GREEN
                src.endsWith("no.png") -> SearchResult.Status.RED
                else -> SearchResult.Status.UNKNOWN
            }
        }
        return null
    }

    private fun getDivibibStatus(doc: Document): Map<String, SearchResult.Status> {
        val elements = doc.select(".wo-status-plc[data-entityid][data-divibibid], .wo-status-plc-icon[data-entityid][data-divibibid]")

        if (elements.size == 0) return emptyMap()

        val entityIds = elements.map { it["data-entityid"] }
        val divibibIds = elements.map { it["data-divibibid"] }

        // The 0 is contained in data-divibibagencyid, but seems to be always 0 and we don't have zip(a,b,c) readily available ;)
        val ids = entityIds.zip(divibibIds).map { (eid, did) -> "$eid#$did/0" }.joinToString(",")

        val body = FormBody.Builder()
                .add("format", "icon")
                .add("ids", ids).build()
        val result = httpPost("$opacUrl/handler/divibibstatus", body, ENCODING).jsonObject
        val arr = result.optJSONArray("DivibibStatus") ?: return emptyMap()

        return HashMap<String, SearchResult.Status>().apply {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val html = obj.getString("result").html
                val status = getStatus(html)
                if (status != null) put(obj.getString("entityId").replace("N", ""), status)
            }
        }
    }

    override fun filterResults(filter: Filter, option: Filter.Option): SearchRequestResult? {
        return null
    }

    override fun searchGetPage(page: Int): SearchRequestResult? {
        val doc = httpGet("$opacUrl/search/shortview?searchType=Extended" +
                "&searchResultId=$searchResultId&page=$page&pageSize=$PAGE_SIZE", ENCODING).html
        return parseSearch(doc, page)
    }

    override fun getResultById(id: String, homebranch: String?): DetailedItem {
        val doc = httpGet("$opacUrl/search/notice?$id", ENCODING).html
        return parseDetail(doc, id)
    }

    protected fun parseDetail(doc: Document, id: String): DetailedItem {
        return DetailedItem().apply {
            title = doc.select(".wo-marc-title, .wo-list-content-no-label[style*=\"font-weight: bold\"]").first().text
            this.id = id
            cover = doc.select(".wo-cover").first()?.attr("src")
            details.addAll(doc.select("#lst-fullview_Details .wo-list-label").toList()
                    .associateWith { label -> label.nextElementSibling() }
                    .map { entry ->
                        Detail(entry.key.text, entry.value.text)
                    })

            val description = doc.select(
                    ".wo-list-content-no-label[style=background-color:#F3F3F3;]").text
            details.add(Detail(stringProvider.getString(StringProvider.DESCRIPTION), description))

            val medialinks = doc.select(".wo-linklist-multimedialinks .wo-link a, .wo-btn-ebibliomedia, .wo-btn-divibib")
            for (link in medialinks) {
                val href = link.attr("href")
                if (href.contains("multimedialinks/link?url")) {
                    val url = BaseApi.getQueryParamsFirst(href)["url"]
                    addDetail(Detail(link.text(), url))
                } else if (href.contains("ebibliomedialink?ref")) {
                    val url = BaseApi.getQueryParamsFirst(href)["ref"]
                    addDetail(Detail(link.text(), url))
                } else if (href.contains("divibibrequestitem?divibibId")) {
                    val url = BaseApi.getQueryParamsFirst(href)["divibibId"]
                    addDetail(Detail("_onleihe_id", url))
                }
            }

            val copyCols = doc.select(".wo-grid-table > thead > tr > th").map { it.text.trim() }
            val df = DateTimeFormat.forPattern("dd.MM.yyyy")
            copies = doc.select(".wo-grid-table > tbody > tr").map { row ->
                Copy().apply {
                    var lastType: String? = null
                    row.select("td").zip(copyCols).forEach { (col, header) ->
                        val headers = header.split(" / ").map { it.trim() }
                        val data = col.html().split("<br>").map { it.html.text.trim() }
                        for ((type, content) in headers.zip(data)) {
                            println("$type $content $lastType")
                            if (content.isEmpty()) {
                                if (type.isNotEmpty()) lastType = type
                                continue
                            }
                            when (type) {
                                "", "Stockwerk" -> {
                                    when (lastType) {
                                        "Aktueller Standort", "Standorte", "Standort" -> {
                                            // Bern: multiple location columns
                                            if (location != null) {
                                                location += " · $content"
                                            }
                                        }
                                    }
                                }
                                "Bibliothek" -> branch = content
                                "Aktueller Standort", "Standorte", "Standort" -> location = content
                                "Signatur", "Call number", "Cote" -> shelfmark = content
                                "Verfügbarkeit", "Disposability", "Disponsibilité" -> status = content
                                "Fälligkeitsdatum", "Due date", "Date d'échéance" ->
                                    returnDate = if (content.isEmpty()) {
                                        null
                                    } else {
                                        df.parseLocalDate(content)
                                    }
                                "Anz. Res." -> reservations = content
                                "Reservieren", "Reserve", "Réserver", "Bestellen", "Commander" -> {
                                    val button = col.select("a").first()
                                    if (button != null) {
                                        resInfo = BaseApi.getQueryParamsFirst(button.attr("href"))["selectedItems"]
                                        isReservable = true
                                    }
                                }
                                "Exemplarnr", "Item number", "No d'exemplaire" -> barcode = content
                            }
                            if (type.isNotEmpty()) lastType = type
                        }
                    }
                }
            }
        }
    }

    override fun getResult(position: Int): DetailedItem? {
        return null
    }

    private fun getBestCopy(copies: List<Copy>, branch: String? = null): Copy {
        return copies.sortedWith(compareBy(
                { it.returnDate?.toDate()?.time ?: 0L },
                { if (it.branch == branch) 0 else 1 }
        )).first()
    }

    override fun reservation(item: DetailedItem, account: Account, useraction: Int,
                             selection: String?): OpacApi.ReservationResult? {
        if (useraction == 0 && selection == null) {
            val reservableCopies = item.copies.filter { it.resInfo != null }
            if (reservableCopies.size == 0) {
                return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.ERROR, stringProvider.getString(StringProvider.NO_COPY_RESERVABLE))
            }

            // select any item, just to know which options we'll get
            var doc = httpGet("$opacUrl/account/makeitemreservation?selectedItems%5B0%5D=${reservableCopies[0].resInfo}", ENCODING).html
            if (doc.select("#wo-frm-login").count() > 0) {
                login(account)
                doc = httpGet("$opacUrl/account/makeitemreservation?selectedItems%5B0%5D=${reservableCopies[0].resInfo}", ENCODING).html
            }

            val resKind = doc.select(".wo-reservationkind[checked]").`val`()
            val resKindText = doc.select("label:has(.wo-reservationkind[checked])").text

            if (doc.select("input[name=CheckoutKind]").size > 0) {
                // "PickUp" or "Mail", e.g. in Basel
                // In this case, user should select a checkout kind and maybe a pickup and address location,
                // but we choose the copy
                val options = mutableListOf<Map<String, String>>()
                for (cklabel in doc.select("label:has(input[name=CheckoutKind])")) {
                    val ck = cklabel.select("input").first().`val`()

                    if (ck == "PickUp") {
                        val addressId = doc.select("input[name=AddessId]").first().`val`()
                        for (opt in doc.select("select[name=BranchofficeId] option")) {
                            val key = JSONObject()
                            key.put("ItemId", getBestCopy(reservableCopies, opt.text().trim()).resInfo)
                            key.put("ReservationKind", resKind)
                            key.put("CheckoutKind", ck)
                            key.put("BranchofficeId", opt.`val`())
                            key.put("AddessId" /* sic! */, addressId)
                            options.add(
                                    hashMapOf(
                                            "key" to key.toString(),
                                            "value" to "${opt.text()} / ${cklabel.text()}"
                                    )
                            )
                        }
                    } else if (ck == "Mail") {
                        for (alabel in doc.select("label:has(input[name=AddessId])")) {
                            val key = JSONObject()
                            key.put("ItemId", getBestCopy(reservableCopies).resInfo)
                            key.put("ReservationKind", resKind)
                            key.put("CheckoutKind", ck)
                            key.put("AddessId" /* sic! */, alabel.select("input").first().`val`())
                            options.add(
                                    0, // insert at beginning
                                    hashMapOf(
                                            "key" to key.toString(),
                                            "value" to "${cklabel.text()} / ${alabel.text()}"
                                    )
                            )
                        }
                    } else {
                        // whwatever this is
                        val addressId = doc.select("input[name=AddessId]").first().`val`()
                        val key = JSONObject()
                        key.put("ItemId", getBestCopy(reservableCopies).resInfo)
                        key.put("ReservationKind", resKind)
                        key.put("CheckoutKind", ck)
                        key.put("AddessId" /* sic! */, addressId)
                        options.add(
                                hashMapOf(
                                        "key" to key.toString(),
                                        "value" to cklabel.text()
                                )
                        )
                    }
                }
                return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.SELECTION_NEEDED).apply {
                    actionIdentifier = OpacApi.ReservationResult.ACTION_BRANCH
                    this.selection = options
                }
            } else {
                // Reservation, e.g. in Bern
                // In this case, user should select a copy

                val addressId = doc.select("input[name=AddessId]").first().`val`()
                val options = reservableCopies.map { copy ->
                    val key = JSONObject()
                    key.put("ItemId", copy.resInfo)
                    key.put("ReservationKind", resKind)
                    key.put("AddessId" /* sic! */, addressId)
                    hashMapOf(
                            "key" to key.toString(),
                            "value" to "${copy.branch} ${copy.status} ${copy.returnDate} (${resKindText})"
                    )
                }
                return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.SELECTION_NEEDED).apply {
                    actionIdentifier = OpacApi.ReservationResult.ACTION_BRANCH
                    this.selection = options
                }
            }
        } else if (useraction == OpacApi.ReservationResult.ACTION_BRANCH) {
            val data = JSONObject(selection)

            val bodyBuilder = FormBody.Builder()
            for (k in data.keys().iterator()) {
                bodyBuilder.add(k as String, data.getString(k))
            }

            val doc = httpPost("$opacUrl/account/makeitemreservation", bodyBuilder.build(), ENCODING).html
            if (doc.select(".alert-success").size == 1) {
                return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.OK)
            } else {
                return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.ERROR)
            }
        }
        return OpacApi.ReservationResult(OpacApi.MultiStepResult.Status.ERROR)
    }

    override fun prolong(media: String, account: Account, useraction: Int, selection: String?): OpacApi.ProlongResult? {
        if (!initialised) start()
        login(account)

        val doc = httpGet("$opacUrl/account/renew?selectedItems%5B0%5D=$media", ENCODING).html
        if (doc.select(".alert-success").size == 1) {
            return OpacApi.ProlongResult(OpacApi.MultiStepResult.Status.OK)
        } else {
            return OpacApi.ProlongResult(OpacApi.MultiStepResult.Status.ERROR,
                    doc.select(".alert-danger").text)
        }
    }

    override fun prolongAll(account: Account, useraction: Int, selection: String?): OpacApi.ProlongAllResult? {
        if (!initialised) start()
        login(account)

        val lentDoc = paginatedGet("$opacUrl/account/circulations")
        val lent = lentDoc.map { parseItems(it, ::LentItem) }.flatten()

        val builder = HttpUrl.parse("$opacUrl/account/renew")!!.newBuilder()
        lent.forEachIndexed { index, item ->
            if (item.prolongData != null) {
                builder.addQueryParameter("selectedItems[$index]", item.prolongData)
            }
        }
        builder.addQueryParameter("returnUrl", "${URL(opacUrl).path}/account/circulations")

        val doc = httpGet(builder.build().toString(), ENCODING).html
        if (doc.select(".alert-success").size == 1) {
            return OpacApi.ProlongAllResult(OpacApi.MultiStepResult.Status.OK)
        } else {
            return OpacApi.ProlongAllResult(OpacApi.MultiStepResult.Status.ERROR,
                    doc.select(".alert-danger").text)
        }
    }

    override fun prolongMultiple(media: List<String>,
                                 account: Account, useraction: Int, selection: String?): OpacApi.ProlongAllResult {
        return return OpacApi.ProlongAllResult(OpacApi.MultiStepResult.Status.UNSUPPORTED)
    }

    override fun cancel(media: String, account: Account, useraction: Int, selection: String?): OpacApi.CancelResult? {
        if (!initialised) start()
        login(account)

        val doc = httpGet("$opacUrl/account/deletereservations?selectedItems%5B0%5D=$media", ENCODING).html
        if (doc.select(".alert-success").size == 1) {
            return OpacApi.CancelResult(OpacApi.MultiStepResult.Status.OK)
        } else {
            return OpacApi.CancelResult(OpacApi.MultiStepResult.Status.ERROR,
                    doc.select(".alert-danger").text)
        }
    }

    private fun paginatedGet(firstUrl: String?): List<Document> {
        val res = mutableListOf<Document>()
        var url = firstUrl

        while (url != null) {
            val doc = httpGet(url, ENCODING).html
            doc.setBaseUri(url)
            res.add(doc)
            url = doc.select(".pagination .next-page a[href]").not("a[href=#]").first()?.absUrl("href")
        }

        return res
    }

    override fun account(account: Account): AccountData? {
        if (!initialised) start()
        val login = login(account)

        val overview = httpGet("$opacUrl/account", ENCODING).html
        val resDoc = paginatedGet("$opacUrl/account/reservations")
        val readyDoc = paginatedGet("$opacUrl/account/orders")
        val lentDoc = paginatedGet("$opacUrl/account/circulations")

        return AccountData(account.id).apply {
            if (overview.select(".alert").size > 0) {
                warning = overview.select(".alert").first().ownText()
            } else if (login.select(".alert").size > 0) {
                warning = login.select(".alert").first().ownText()
            }

            val feesString = overview.select("a[href$=fees]").first()?.text
            pendingFees = if (feesString != null) {
                Regex("\\(([^\\)]+)\\)").find(feesString)?.groups?.get(1)?.value
            } else null
            validUntil = overview.select(
                    ".wo-list-label:contains(Abonnement (Ende)) + .wo-list-content, " +
                            ".wo-list-label:contains(Subscription (end)) + .wo-list-content," +
                            " .wo-list-label:contains(Abonnement (Fin)) + .wo-list-content").first()?.text
            reservations = resDoc.map { parseItems(it, ::ReservedItem) }.flatten() + readyDoc.map { parseItems(it, ::ReservedItem, ready = true) }.flatten()
            lent = lentDoc.map { parseItems(it, ::LentItem) }.flatten()
        }
    }

    internal fun <I : AccountItem> parseItems(doc: Document, constructor: () -> I, ready: Boolean = false): List<I> {
        val table = doc.select(".wo-grid-table").first() ?: return emptyList()
        val cols = table.select("> thead > tr > th").map { it.text.trim() }
        val rows = table.select("> tbody > tr")

        val df = DateTimeFormat.forPattern("dd.MM.yyyy")

        return rows.map { row ->
            constructor().apply {
                var renewals: Int? = null
                var reservations: Int? = null

                row.select("td").zip(cols).forEach { (col, header) ->
                    val headers = header.split(" / ").map { it.trim() }
                    val data = col.html().split("<br>").map { it.html.text.trim() }

                    headers.zip(data).forEach { (type, data) ->
                        when (type) {
                            "" -> {
                                val checkbox = col.select("input[type=checkbox]").first()
                                val coverImg = col.select(".wo-cover").first()
                                if (checkbox != null) {
                                    when {
                                        this is LentItem -> prolongData = checkbox["value"]
                                        this is ReservedItem -> cancelData = checkbox["value"]
                                    }
                                } else if (coverImg != null) {
                                    cover = coverImg["src"]
                                }
                            }
                            "Bibliothek" -> when {
                                this is LentItem -> lendingBranch = data
                                this is ReservedItem -> branch = data
                            }
                            "Autor", "Author", "Auteur" -> {
                                author = data
                                val link = col.select("a").first()
                                if (link != null) {
                                    val nr = BaseApi.getQueryParamsFirst(link["href"])["noticeNr"]
                                    id = "noticeNr=$nr"
                                }
                            }
                            "Titel", "Title", "Titre" -> {
                                title = data
                                val link = col.select("a").first()
                                if (link != null) {
                                    val nr = BaseApi.getQueryParamsFirst(link["href"])["noticeNr"]
                                    id = "noticeNr=$nr"
                                }
                            }
                            "Medienart" -> format = data
                            "Fälligkeitsdatum", "Due date", "Date d'échéance" -> when {
                                this is LentItem -> deadline = df.parseLocalDate(data)
                            }
                            "Exemplarnr.", "Item number", "No d'exemplaire" -> when {
                                this is LentItem -> barcode = data
                            }
                            "Verlängerungen", "Renewals", "Prolongations" ->
                                renewals = data.toIntOrNull()
                            "Anz. Res." -> reservations = data.toIntOrNull()
                            //"Aktueller Standort" ->
                            //"Signatur", "Call number", "Cote" ->
                            //"Bereich" ->
                            //"Jahr" ->
                            //"Datum der Verlängerung", "Renewal date", "Date de prolongation"
                        }
                    }
                }

                val readyText = if (ready) stringProvider.getString(StringProvider.RESERVATION_READY) else null
                val renewalsText = if (renewals != null && renewals!! > 0)
                    "${renewals}x ${stringProvider.getString(StringProvider.PROLONGED_ABBR)}"
                else null
                val reservationsText = if (reservations != null && reservations!! > 0) {
                    stringProvider.getQuantityString(StringProvider.RESERVATIONS_NUMBER, reservations!!, reservations)
                } else null
                status = listOf(readyText, renewalsText, reservationsText).filter { it != null }
                        .joinToString(", ")
                if (status.isEmpty()) status = null
            }
        }
    }

    override fun checkAccountData(account: Account) {
        login(account)
    }

    private fun login(account: Account): Document {
        val formData = FormBody.Builder()
                .add("ReturnUrl", "${URL(opacUrl).path}/account")
                .add("Username", account.name)
                .add("Password", account.password)
                .add("SaveUsernameInCookie", "false")
                .add("StayLoggedIn", "false").build()
        val doc = httpPost("$opacUrl/account/login", formData, ENCODING).html
        if (doc.select(".alert").size > 0 && doc.select(".wo-com-account-overview").size < 1) {
            throw OpacApi.OpacErrorException(doc.select(".alert").first().ownText())
        }
        return doc
    }

    override fun getShareUrl(id: String, title: String?): String? {
        return "$opacUrl/search/notice?$id"
    }

    override fun getSupportFlags(): Int {
        return OpacApi.SUPPORT_FLAG_ENDLESS_SCROLLING or OpacApi
                .SUPPORT_FLAG_WARN_RESERVATION_FEES or OpacApi.SUPPORT_FLAG_ACCOUNT_PROLONG_ALL
    }

    override fun getSupportedLanguages(): Set<String>? {
        val doc = httpGet(opacUrl, ENCODING).html
        val languages = findLanguages(doc)

        // change language once to find out what the current language was
        val doc2 = httpGet("$opacUrl/Site/ChangeLanguage?language=${languages[0]}", ENCODING).html
        val myLanguage = (findLanguages(doc2) - languages).first()

        // change back to previous language
        httpGet("$opacUrl/Site/ChangeLanguage?language=$myLanguage", ENCODING).html
        return languages.toSet() + myLanguage
    }

    private fun findLanguages(doc: Document): List<String> {
        val regex = Regex("language=([^&]+)")
        return doc.select(".dropdown-menu a[href*=ChangeLanguage]").map { a ->
            regex.find(a["href"])!!.groups[1]!!.value
        }
    }

    override fun setLanguage(language: String) {
        lang = language
    }
}
