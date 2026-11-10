package cy.pharmacies.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The pharmacy feed: one small file, rebuilt every few hours by the generator in the
 * cy-pharmacies-privacy repository (from data.gov.cy and the Ministry of Health) and served
 * by GitHub Pages. It holds every district's duty pharmacies for the next few nights, the
 * register of all pharmacies, and map positions for their addresses, so the app gets
 * everything in one quick download instead of a hundred-odd slow ones.
 *
 * The app treats it as a fast path only: when it's missing or old, the app downloads from
 * the sources directly. Like Sources.kt, this file is copied as-is into the generator.
 */
object Feed {

    const val URL = "https://foti-s.github.io/cy-pharmacies-privacy/feed/v1/pharmacies.json"
    const val VERSION = 1

    /** How far a position can be trusted: the street address, its postcode area, or the town. */
    enum class Precision { STREET, POSTCODE, TOWN }

    class Position(val lat: Double, val lng: Double, val precision: Precision)

    /** One district's duty nights, and when they were last read from the sources. */
    class Nights(val updatedAt: Long, val latest: LocalDate?, val pharmacies: List<Pharmacy>)

    class Data(
        val generatedAt: Long,
        val nights: Map<District, Nights>,
        val registerUpdatedAt: Long?,
        val register: List<Pharmacy>,
        /** By [positionKey]. */
        val positions: Map<String, Position>,
    )

    /** Addresses, not pharmacies, have positions: the same key the app's own geocode cache uses. */
    fun positionKey(p: Pharmacy) = "${p.address.trim().lowercase()}|${p.area.trim().lowercase()}"

    fun encode(data: Data): String = JSONObject().apply {
        put("version", VERSION)
        put("generatedAt", data.generatedAt)
        put("credit", CREDIT)
        put("nights", JSONObject().apply {
            for ((district, n) in data.nights) {
                put(district.name, JSONObject().apply {
                    put("updatedAt", n.updatedAt)
                    n.latest?.let { put("latest", it.toString()) }
                    put("pharmacies", JSONArray().apply { n.pharmacies.forEach { put(encode(it)) } })
                })
            }
        })
        data.registerUpdatedAt?.let { put("registerUpdatedAt", it) }
        put("register", JSONArray().apply { data.register.forEach { put(encode(it)) } })
        put("positions", JSONObject().apply {
            for ((key, p) in data.positions.toSortedMap()) {
                put(key, JSONArray().put(round(p.lat)).put(round(p.lng)).put(p.precision.name))
            }
        })
    }.toString()

    /** Throws if the file isn't a feed this app version understands. */
    fun decode(json: String): Data {
        val root = JSONObject(json)
        require(root.optInt("version") == VERSION) { "Unknown feed version" }
        val nightsJson = root.getJSONObject("nights")
        val nights = HashMap<District, Nights>()
        for (name in nightsJson.keys()) {
            val district = District.fromName(name) ?: continue
            val n = nightsJson.getJSONObject(name)
            nights[district] = Nights(
                updatedAt = n.getLong("updatedAt"),
                latest = n.optString("latest").ifBlank { null }?.let(LocalDate::parse),
                pharmacies = n.getJSONArray("pharmacies").objects().map(::decodePharmacy),
            )
        }
        val positionsJson = root.getJSONObject("positions")
        val positions = HashMap<String, Position>(positionsJson.length())
        for (key in positionsJson.keys()) {
            val a = positionsJson.getJSONArray(key)
            val precision = runCatching { Precision.valueOf(a.getString(2)) }.getOrNull() ?: continue
            positions[key] = Position(a.getDouble(0), a.getDouble(1), precision)
        }
        return Data(
            generatedAt = root.getLong("generatedAt"),
            nights = nights,
            registerUpdatedAt = if (root.has("registerUpdatedAt")) root.getLong("registerUpdatedAt") else null,
            register = root.getJSONArray("register").objects().map(::decodePharmacy),
            positions = positions,
        )
    }

    private fun encode(p: Pharmacy) = JSONObject().apply {
        put("regNo", p.regNo)
        put("name", p.name)
        if (p.address.isNotBlank()) put("address", p.address)
        if (p.directions.isNotBlank()) put("directions", p.directions)
        if (p.area.isNotBlank()) put("area", p.area)
        if (p.phone.isNotBlank()) put("phone", p.phone)
        if (p.housePhone.isNotBlank()) put("housePhone", p.housePhone)
        p.date?.let { put("date", it.toString()) }
        p.district?.let { put("district", it.name) }
        if (p.postcode.isNotBlank()) put("postcode", p.postcode)
    }

    private fun decodePharmacy(o: JSONObject) = Pharmacy(
        regNo = o.optString("regNo"),
        name = o.optString("name"),
        address = o.optString("address"),
        directions = o.optString("directions"),
        area = o.optString("area"),
        phone = o.optString("phone"),
        housePhone = o.optString("housePhone"),
        date = o.optString("date").ifBlank { null }?.let(LocalDate::parse),
        district = District.fromName(o.optString("district").ifBlank { null }),
        postcode = o.optString("postcode"),
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Six decimals is about 10 cm: plenty, and it keeps the file small. */
    private fun round(v: Double) = Math.round(v * 1e6) / 1e6

    private const val CREDIT =
        "Pharmaceutical Services, Ministry of Health, Cyprus, via data.gov.cy (CC BY 4.0) and moh.gov.cy. " +
            "Positions: MapTiler, © OpenStreetMap contributors."
}
