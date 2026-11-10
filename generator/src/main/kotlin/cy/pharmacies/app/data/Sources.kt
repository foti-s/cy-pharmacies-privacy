package cy.pharmacies.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException
import java.text.ParsePosition
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/*
 * Downloading and reading the public pharmacy data, with no Android in it. The app uses this
 * file, and the feed generator (in the cy-pharmacies-privacy repository) uses an exact copy,
 * so both read the sources the same way. Change it here, then copy it there.
 */

/**
 * The duty-pharmacy schedule on the Cyprus open data portal.
 *
 * API: https://www.data.gov.cy/api/action/datastore/search.json?resource_id=...
 * Response: { success: true, result: { total: N, records: [ {date, day, reg__no_, ...} ] } }
 */
object PortalSource {

    /** Fetches every record for the district (the API pages results) and returns them as one JSON array. */
    fun download(district: District): String {
        val all = JSONArray()
        var offset = 0
        var pageSize = PAGE_SIZE
        do {
            val (root, sizeThatWorked) = fetchPageAdapting(district, offset, pageSize)
            pageSize = sizeThatWorked
            val result = root.getJSONObject("result")
            val records = result.optJSONArray("records") ?: JSONArray()
            for (i in 0 until records.length()) all.put(records.get(i))
            val total = result.optInt("total", all.length())
            // The portal caps each page (currently at 100) regardless of `limit`, so advance by what it sent.
            offset += records.length()
        } while (offset < total && records.length() > 0)

        return all.toString()
    }

    /**
     * One page, at [pageSize] records if the portal allows it. The portal sometimes fails a page
     * with "Failed to get datastore variable lock": now and then that passes a moment later, and
     * sometimes it only goes away for smaller pages (in October 2026 every 100-record page failed
     * while 50 worked). So each failure retries with half the page size, down to a floor, then
     * waits and tries again. Returns the page and the size that worked, used for the next pages.
     */
    private fun fetchPageAdapting(district: District, offset: Int, pageSize: Int): Pair<JSONObject, Int> {
        var size = pageSize
        var attempt = 0
        while (true) {
            val url = URL("$API_BASE?resource_id=${district.resourceId}&limit=$size&offset=$offset")
            try {
                return fetchPage(url) to size
            } catch (e: IOException) {
                // No internet: smaller pages won't help, so don't keep the user waiting.
                if (isNoConnection(e) || ++attempt >= MAX_PAGE_ATTEMPTS) throw e
                if (size > MIN_PAGE_SIZE) size = maxOf(MIN_PAGE_SIZE, size / 2) else Thread.sleep(PAGE_RETRY_DELAY_MS)
            }
        }
    }

    private fun fetchPage(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CyprusPharmacies-Android/1.0")
        }
        val body = try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("The data portal sent an unreadable response", e)
        }
        if (!root.optBoolean("success", false)) throw IOException("The data portal returned an error")
        return root
    }

    private const val API_BASE = "https://www.data.gov.cy/api/action/datastore/search.json"
    private const val PAGE_SIZE = 100
    private const val MIN_PAGE_SIZE = 25
    private const val MAX_PAGE_ATTEMPTS = 4
    private const val PAGE_RETRY_DELAY_MS = 1_500L
}

/**
 * Whether a download failed because the device couldn't reach the internet at all, rather
 * than because the site answered with an error. Only the first deserves "you're offline".
 */
fun isNoConnection(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
    }

/** Nights read from a schedule file, and the last night the file covers. */
class Schedule(val pharmacies: List<Pharmacy>, val latest: LocalDate?)

/** Turns the records of the portal and the ministry's files (same shape) into pharmacies. */
object Records {

    val PHARMACY_ORDER = compareBy<Pharmacy>({ it.date }, { it.area }, { it.name })

    /**
     * The night-duty schedule. Every record has a date; nights outside [from]..[until] are only scanned
     * for their date (to know the latest one), not turned into pharmacies.
     */
    fun parseSchedule(json: String, from: LocalDate, until: LocalDate): Schedule {
        val array = JSONArray(json)
        val list = ArrayList<Pharmacy>()
        var latest: LocalDate? = null
        var columns: Columns? = null
        // Records come in runs of the same night, so the same date text repeats a few times in a row.
        var lastRaw: String? = null
        var lastDate: LocalDate? = null
        for (i in 0 until array.length()) {
            val r = array.optJSONObject(i) ?: continue
            val c = columns ?: Columns(r).also { columns = it }
            val raw = c.value(r, DATE)
            val date = (if (raw == lastRaw) lastDate else parseDate(raw)) ?: continue
            lastRaw = raw
            lastDate = date
            if (latest == null || date > latest) latest = date
            if (date < from || date > until) continue
            toPharmacy(r, c, date)?.let(list::add)
        }
        return Schedule(list.sortedWith(PHARMACY_ORDER), latest)
    }

    /**
     * The portal's nights, with any it lacks taken from the ministry's file (the portal lags
     * behind each new season).
     */
    fun merge(portal: Schedule?, ministry: Schedule?): Schedule {
        val portalList = portal?.pharmacies.orEmpty()
        val covered = portalList.mapTo(HashSet()) { it.date }
        val extra = ministry?.pharmacies.orEmpty().filter { it.date !in covered }
        return Schedule(
            (portalList + extra).sortedWith(PHARMACY_ORDER),
            listOfNotNull(portal?.latest, ministry?.latest).maxOrNull(),
        )
    }

    /** The register: no dates, but each record carries its district. */
    fun parseRegister(json: String): List<Pharmacy> {
        val array = JSONArray(json)
        val list = ArrayList<Pharmacy>(array.length())
        var columns: Columns? = null
        for (i in 0 until array.length()) {
            val r = array.optJSONObject(i) ?: continue
            val c = columns ?: Columns(r).also { columns = it }
            toPharmacy(r, c, date = null, district = District.fromName(r.optString("district")))?.let(list::add)
        }
        return list.sortedWith(compareBy({ it.district }, { it.area }, { it.name }))
    }

    private fun toPharmacy(r: JSONObject, c: Columns, date: LocalDate?, district: District? = null): Pharmacy? {
        val name = "${c.value(r, SURNAME)} ${c.value(r, FIRST_NAME)}".trim()
        if (name.isEmpty()) return null
        return Pharmacy(
            regNo = c.value(r, REG_NO),
            name = name,
            address = c.value(r, ADDRESS),
            directions = c.value(r, DIRECTIONS),
            area = c.value(r, AREA),
            phone = cleanPhone(c.value(r, PHONE)),
            housePhone = cleanPhone(c.value(r, HOUSE_PHONE)),
            date = date,
            district = district,
            postcode = c.value(r, POSTCODE),
        )
    }

    /**
     * Which of a file's column names holds each field. The portal has renamed its columns more
     * than once, so names are compared ignoring accents, case, spaces and punctuation. Worked out
     * once from the file's first record rather than searched for in every record.
     */
    private class Columns(sample: JSONObject) {
        private val byNormalizedName = sample.keys().asSequence().associateBy(::normalizeKey)

        fun value(r: JSONObject, names: Array<String>): String {
            val key = names.firstNotNullOfOrNull { byNormalizedName[normalizeKey(it)] }
            return if (key != null && r.has(key)) cleanValue(r.optString(key)) else r.field(names)
        }
    }

    /** Slow path for a record whose columns differ from the first record's. */
    private fun JSONObject.field(names: Array<String>): String {
        val wanted = names.map(::normalizeKey).toSet()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (normalizeKey(key) in wanted) return cleanValue(optString(key))
        }
        return ""
    }

    // Called for every column of every record (tens of thousands of times per load) but there are only a
    // few dozen distinct column names, and accent folding is slow, so each name is folded once.
    private val normalizedKeys = ConcurrentHashMap<String, String>()

    private fun normalizeKey(key: String) =
        normalizedKeys.getOrPut(key) { foldGreek(key).filter { it.isLetterOrDigit() } }

    private fun cleanValue(value: String): String {
        val text = value.trim().replace(WHITESPACE, " ")
        return if (text == "0" || text.equals("null", ignoreCase = true)) "" else text
    }

    private fun cleanPhone(value: String) = value.filter { it.isDigit() || it == '+' }

    /**
     * Tries each format without throwing: a failed LocalDate.parse throws, and with thousands of
     * records whose dates only match a later format, the exceptions alone took seconds.
     */
    private fun parseDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        val value = raw.substringBefore('T').trim()
        for (formatter in DATE_FORMATS) {
            val position = ParsePosition(0)
            formatter.parseUnresolved(value, position) ?: continue
            if (position.errorIndex >= 0 || position.index != value.length) continue
            try {
                return LocalDate.from(formatter.parse(value)) // matched the pattern; resolve it
            } catch (e: DateTimeException) {
                // the pattern matched but the date isn't valid (e.g. 31/02): try the next format
            }
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")

    // Each field's column name as data.gov.cy (English) and the ministry (Greek) have spelled it.
    private val DATE = arrayOf("date", "Ημερομηνία")
    private val SURNAME = arrayOf("surmame", "surname", "Επίθετο")
    private val FIRST_NAME = arrayOf("name", "Όνομα")
    private val REG_NO = arrayOf("reg__no_", "Reg. No.", "AM", "ΑΜ")
    private val ADDRESS = arrayOf("address", "Διεύθυνση")
    private val DIRECTIONS = arrayOf("additional_address_info", "Συμπληρωματική Διεύθυνση")
    private val AREA = arrayOf("muniuciplity__community", "municipality__community", "Δήμος / Κοινότητα")
    private val PHONE = arrayOf("pharmacy_tel__no_", "Pharmacy Tel. No.", "Τηλέφωνο Φαρμακείου")
    private val HOUSE_PHONE = arrayOf("house_tel__no_", "House Tel. No.", "Τηλέφωνο Οικίας")
    private val POSTCODE = arrayOf("ΤΤ", "TT", "postal_code", "Ταχυδρομικός Κώδικας")

    // The portal currently uses "30/09/26"; the others cover past format changes.
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("d/M/yy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("d-M-yy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/M/d"),
    )
}
