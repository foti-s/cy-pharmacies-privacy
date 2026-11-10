package cy.pharmacies.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.time.LocalDate

/**
 * The Pharmaceutical Services' own page on moh.gov.cy, which carries two kinds of file:
 *
 * - The night-duty schedule per district. It changes every 1 May and 1 October; the
 *   ministry posts the new season here first and data.gov.cy can lag behind by weeks,
 *   so this is the fallback for the schedule.
 * - The register of every private pharmacy ("Κατάλογος Ιδιωτικών Φαρμακείων"), grouped
 *   under district headings. The copy on data.gov.cy is a year out of date, so this
 *   page is the only source for it.
 *
 * The file links carry internal IDs and the season in their names, so they are
 * looked up on the page each time rather than hard-coded.
 */
object MinistrySource {

    const val PAGE_URL = "https://www.moh.gov.cy/moh/phs/phs.nsf/All/091BC51661367EE1C225857B002F972D?OpenDocument"
    private const val HOST = "www.moh.gov.cy"
    private const val REGISTER_FILE_NAME = "Ιδιωτικών Φαρμακείων"

    /** Downloads the district's schedule and returns it as records shaped like the data.gov.cy API's. */
    fun download(district: District): String {
        val page = String(get(PAGE_URL), Charsets.UTF_8)
        val link = findScheduleLink(page, district) ?: throw IOException("No schedule file for ${district.name}")
        return toRecords(Spreadsheet.readFirstSheet(get(link))).toString()
    }

    /** Downloads the register of all private pharmacies; each record has a "district" field. */
    fun downloadRegister(): String {
        val page = String(get(PAGE_URL), Charsets.UTF_8)
        val link = findRegisterLink(page) ?: throw IOException("No pharmacy register file")
        return toRegisterRecords(Spreadsheet.readFirstSheet(get(link))).toString()
    }

    /**
     * Finds the attachment whose file name mentions the district, e.g.
     * `/Moh/phs/phs.nsf/All/1EF3…/$file/Λευκωσία 2026-2027 final.xls?OpenElement`.
     * If several match, the one naming the latest year wins.
     */
    fun findScheduleLink(html: String, district: District): String? = findAttachment(html, district.greekName)

    /** e.g. `…/$file/Κατάλογος Ιδιωτικών Φαρμακείων 2026-2027.xls?OpenElement` */
    fun findRegisterLink(html: String): String? = findAttachment(html, REGISTER_FILE_NAME)

    private fun findAttachment(html: String, nameContains: String): String? {
        val wanted = foldGreek(nameContains)
        return ATTACHMENT_LINK.findAll(html)
            .map { decodeHtml(it.groupValues[1]) }
            .filter { path ->
                val fileName = foldGreek(percentDecode(path.substringAfter("\$file/").substringBefore('?')))
                wanted in fileName && EXCEL_EXTENSION.containsMatchIn(fileName)
            }
            .maxByOrNull { path -> YEAR.findAll(percentDecode(path)).maxOfOrNull { it.value.toInt() } ?: 0 }
            ?.let(::absoluteUrl)
    }

    /** Turns spreadsheet rows (first non-empty row = headers) into JSON records keyed by header. */
    fun toRecords(rows: List<List<Any?>>): JSONArray {
        val records = JSONArray()
        val headerIndex = rows.indexOfFirst { row -> row.any { !isBlank(it) } }
        if (headerIndex < 0) return records
        val headers = headers(rows[headerIndex])
        val dateColumn = headers.indexOfFirst { foldGreek(it).filter(Char::isLetter) in DATE_HEADERS }

        for (row in rows.drop(headerIndex + 1)) {
            if (row.all(::isBlank)) continue
            records.put(toRecord(headers, row, dateColumn))
        }
        return records
    }

    /**
     * The register lists pharmacies under one-cell district heading rows ("Λευκωσία", …).
     * Each record gets a "district" field holding the [District] name.
     */
    fun toRegisterRecords(rows: List<List<Any?>>): JSONArray {
        val records = JSONArray()
        val headerIndex = rows.indexOfFirst { row -> row.count { !isBlank(it) } >= 3 }
        if (headerIndex < 0) return records
        val headers = headers(rows[headerIndex])

        var district: District? = null
        for (row in rows.drop(headerIndex + 1)) {
            val filled = row.filterNot(::isBlank)
            when {
                filled.isEmpty() -> continue
                filled.size == 1 -> {
                    val heading = foldGreek(filled[0].toString().trim())
                    District.entries.firstOrNull { foldGreek(it.greekName) == heading }?.let { district = it }
                }
                else -> records.put(toRecord(headers, row, dateColumn = -1).put("district", district?.name ?: ""))
            }
        }
        return records
    }

    private fun headers(row: List<Any?>) = row.map { it?.toString()?.trim().orEmpty() }

    private fun toRecord(headers: List<String>, row: List<Any?>, dateColumn: Int): JSONObject {
        val record = JSONObject()
        headers.forEachIndexed { i, header ->
            if (header.isEmpty()) return@forEachIndexed
            val cell = row.getOrNull(i)
            val text = when {
                cell == null -> ""
                i == dateColumn && cell is Double -> excelDate(cell).toString() // yyyy-MM-dd
                cell is Double && cell == Math.floor(cell) && !cell.isInfinite() -> cell.toLong().toString()
                else -> cell.toString()
            }
            record.put(header, text)
        }
        return record
    }

    private fun isBlank(cell: Any?) = cell == null || cell.toString().isBlank()

    /** Excel stores dates as days since 30 Dec 1899. */
    private fun excelDate(serial: Double): LocalDate = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())

    private fun get(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "CyprusPharmacies-Android/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** The page links with raw Greek and spaces; encode them so HttpURLConnection sends a valid URL. */
    private fun absoluteUrl(href: String): String {
        val path = percentDecode(href.substringBefore('?'))
        val query = href.substringAfter('?', "").ifEmpty { null }
        return URI("https", HOST, path, query, null).toASCIIString()
    }

    private fun percentDecode(s: String): String =
        if ('%' in s) URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") else s

    private fun decodeHtml(s: String) = s.replace("&amp;", "&").replace("&#36;", "$")

    private val ATTACHMENT_LINK = Regex("""href="([^"]*/\${'$'}file/[^"]+)"""", RegexOption.IGNORE_CASE)
    private val EXCEL_EXTENSION = Regex("""\.xlsx?$""")
    private val YEAR = Regex("""20\d\d""")
    private val DATE_HEADERS = setOf("ημερομηνια", "date")
}
