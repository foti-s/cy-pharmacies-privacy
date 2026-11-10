package feed

import cy.pharmacies.app.data.CyprusBounds
import cy.pharmacies.app.data.District
import cy.pharmacies.app.data.Feed
import cy.pharmacies.app.data.LatLng
import cy.pharmacies.app.data.Pharmacy
import cy.pharmacies.app.data.foldGreek
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.hypot

/**
 * Finds map positions for pharmacy addresses with MapTiler's geocoding API, whose results may
 * be stored permanently. Each address is looked up once and kept in [cacheFile] (committed to
 * the repository), so a run only asks about new addresses; ones that couldn't be placed at
 * their street are tried again after a month.
 *
 * For each address, in order: its street (see [street]), the postcode area
 * (within a few hundred metres in a town), and as a last resort the town's centre.
 * Positions in [overridesFile] (hand-checked corrections) win over all of these.
 */
class Geocoder(private val key: String, private val cacheFile: File, overridesFile: File) {

    private val cache: JSONObject = cacheFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    private val overrides: JSONObject = overridesFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    var lookups = 0
        private set

    /**
     * The position for [p]'s address, or null if not even its town could be found. A town's
     * centre is a last resort, so that every pharmacy is on the map; like a postcode position,
     * the app marks it as approximate.
     */
    fun position(p: Pharmacy): Feed.Position? {
        val key = Feed.positionKey(p)
        overrides.optJSONArray(key)?.let { return Feed.Position(it.getDouble(0), it.getDouble(1), Feed.Precision.STREET) }
        val saved = cache.optJSONObject(key)?.takeUnless(::isStale) ?: lookUp(p)?.also { cache.put(key, it) } ?: return null
        if (!saved.has("lat")) return null
        val precision = Feed.Precision.valueOf(saved.getString("precision"))
        return Feed.Position(saved.getDouble("lat"), saved.getDouble("lng"), precision)
    }

    fun save() {
        cacheFile.writeText(cache.toString(1))
    }

    /** Null when the lookup itself failed (no key, network), so nothing is saved and it's retried next run. */
    private fun lookUp(p: Pharmacy): JSONObject? {
        if (key.isBlank() || p.address.isBlank()) return null
        return try {
            val town = town(p.area, p.district)
            val match = street(p, town)?.let { it to Feed.Precision.STREET }
                ?: postcode(p.postcode, town)?.let { it to Feed.Precision.POSTCODE }
                ?: town?.let { it.center to Feed.Precision.TOWN }
            JSONObject().apply {
                if (match != null) {
                    put("lat", match.first.lat)
                    put("lng", match.first.lng)
                    put("precision", match.second.name)
                }
                put("at", System.currentTimeMillis())
            }
        } catch (e: IOException) {
            System.err.println("Lookup failed for ${p.address}, ${p.area}: ${e.message}")
            null
        }
    }

    /** A town or village: its centre, and the box around it (when MapTiler gives one). */
    private class Town(val center: LatLng, val south: Double, val west: Double, val north: Double, val east: Double) {
        /**
         * Whether [p] belongs to the town: within its box plus [marginKm], or within [radiusKm]
         * of its centre. Both, because MapTiler gives some cities (Limassol, Kato Paphos) as a
         * bare point and Nicosia's box only covers the old town, while big suburbs like
         * Strovolos stretch further than any fixed radius.
         */
        fun contains(p: LatLng, marginKm: Double, radiusKm: Double): Boolean {
            if (p.distanceKm(center) <= radiusKm) return true
            val dLat = marginKm / 111.32
            val dLng = marginKm / (111.32 * kotlin.math.cos(Math.toRadians(center.lat)))
            return p.lat in (south - dLat)..(north + dLat) && p.lng in (west - dLng)..(east + dLng)
        }
    }

    /** The pharmacy's town or village, kept in the cache under "town:…". */
    private fun town(area: String, district: District?): Town? {
        if (area.isBlank()) return null
        val cacheKey = "town2:${area.trim().lowercase()}|${district?.name.orEmpty()}"
        cache.optJSONObject(cacheKey)?.let { return townFrom(it) }
        val near = district?.let { LatLng(it.lat, it.lng) }
        // Only places, or "Στρόβολος" finds a street called Στροβόλου somewhere.
        val feature = first(area, "&types=$TOWN_TYPES" + near?.let { "&proximity=${it.lng},${it.lat}" }.orEmpty())
        val center = feature?.let(::centerOf)?.takeIf { near == null || it.distanceKm(near) <= DISTRICT_REACH_KM }
        val saved = JSONObject().apply {
            if (center != null) {
                put("lat", center.lat)
                put("lng", center.lng)
                feature.optJSONArray("bbox")?.let { put("bbox", it) }
            }
        }
        cache.put(cacheKey, saved)
        return townFrom(saved)
    }

    private fun townFrom(saved: JSONObject): Town? {
        if (!saved.has("lat")) return null
        val center = LatLng(saved.getDouble("lat"), saved.getDouble("lng"))
        val box = saved.optJSONArray("bbox")
        return if (box != null) Town(center, box.getDouble(1), box.getDouble(0), box.getDouble(3), box.getDouble(2))
        else Town(center, center.lat, center.lng, center.lat, center.lng)
    }

    /**
     * The street address. Cyprus streets on the map mostly lack house numbers, so MapTiler
     * usually finds the street rather than the building. That counts when the street has the
     * same name and is in the pharmacy's town (MapTiler readily returns a namesake in another
     * town), and either the house number matches or the street is short enough that its middle
     * is close to every building on it. A long avenue's middle could be a kilometre or two off.
     */
    private fun street(p: Pharmacy, town: Town?): LatLng? {
        town ?: return null
        val address = expandAbbreviations(p.address)
        val name = streetName(address).ifBlank { return null }
        val number = HOUSE_NUMBER.findAll(address).lastOrNull()?.value
        val feature = first("$address, ${p.area}", "&types=address&proximity=${town.center.lng},${town.center.lat}") ?: return null
        val found = streetName(feature.optString("text"))
        if (found.isBlank() || !(found.contains(name) || name.contains(found))) return null
        val position = centerOf(feature)?.takeIf { town.contains(it, TOWN_MARGIN_KM, TOWN_RADIUS_KM) } ?: return null
        val exact = number != null && Regex("""^$number\p{L}?\s""").containsMatchIn(feature.optString("place_name"))
        val box = feature.optJSONArray("bbox")
        val length = box?.let {
            val sw = LatLng(it.getDouble(1), it.getDouble(0))
            sw.distanceKm(LatLng(it.getDouble(3), it.getDouble(2)))
        } ?: 0.0
        return position.takeIf { exact || length <= SHORT_STREET_KM }
    }

    /** A street's name for comparing: accents, case, "Λεωφόρος", numbers and punctuation dropped. */
    private fun streetName(text: String): String =
        foldGreek(text.substringBefore(','))
            .replace(Regex("""(?<!\p{L})(λεωφορος|λεωφ|οδος|οδ|αρχιεπισκοπου|αρχ)(?!\p{L})"""), " ")
            .filter { it.isLetter() }

    /** The middle of the postcode's area, if it's small and near the town; kept under "pc:…". */
    private fun postcode(postcode: String, town: Town?): LatLng? {
        val code = postcode.filter { it.isDigit() }
        if (code.length != 4) return null
        val cacheKey = "pc:$code"
        val area = cache.optJSONObject(cacheKey)?.let { if (it.has("lat")) LatLng(it.getDouble("lat"), it.getDouble("lng")) else null }
            ?: if (cache.has(cacheKey)) null else fetchPostcode(code).also { found ->
                cache.put(cacheKey, JSONObject().apply { found?.let { put("lat", it.lat); put("lng", it.lng) } })
            }
        return area?.takeIf { town == null || town.contains(it, POSTCODE_MARGIN_KM, POSTCODE_RADIUS_KM) }
    }

    private fun fetchPostcode(code: String): LatLng? {
        val feature = first(code, "&types=postal_code") ?: return null
        if (feature.optString("text") != code) return null
        feature.optJSONArray("bbox")?.let { box ->
            val south = LatLng(box.getDouble(1), box.getDouble(0))
            val width = south.distanceKm(LatLng(box.getDouble(1), box.getDouble(2)))
            val height = south.distanceKm(LatLng(box.getDouble(3), box.getDouble(0)))
            if (hypot(width, height) > POSTCODE_MAX_SIZE_KM) return null
        }
        return centerOf(feature)
    }

    /** MapTiler's best match for [text] in Cyprus. */
    private fun first(text: String, extra: String): JSONObject? {
        lookups++
        Thread.sleep(REQUEST_GAP_MS)
        val path = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val url = URI("https://api.maptiler.com/geocoding/$path.json?key=$key&country=cy&limit=1&language=el$extra").toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "cy-pharmacies-feed")
        }
        val body = try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        return JSONObject(body).optJSONArray("features")?.optJSONObject(0)
    }

    private fun centerOf(feature: JSONObject): LatLng? {
        val c: JSONArray = feature.optJSONArray("center") ?: return null
        return LatLng(c.getDouble(1), c.getDouble(0)).takeIf { CyprusBounds.contains(it) }
    }

    /** Street-level results stay; the rest are tried again now and then, in case the map improved. */
    private fun isStale(saved: JSONObject): Boolean {
        val street = saved.optString("precision") == Feed.Precision.STREET.name
        return !street && System.currentTimeMillis() - saved.optLong("at") > RETRY_AFTER_MS
    }

    // Word boundaries are written as (?<!\p{L}) because \b is ASCII-only on the JVM.
    private fun expandAbbreviations(address: String) = address
        .replace(Regex("""(?<!\p{L})Λεωφ\.\s*"""), "Λεωφόρος ")
        .replace(Regex("""(?<!\p{L})Αρχ\.\s*"""), "Αρχιεπισκόπου ")
        .replace(Regex("""(?<!\p{L})Αγ\.\s*"""), "Αγίου ")
        .replace(Regex(""",?\s*(?<!\p{L})(Κατάστημα|Κατ\.?|κατ\.?)\s*[\d&+\s,-]+.*$"""), "") // shop numbers confuse the geocoder
        .trim()

    private companion object {
        const val REQUEST_GAP_MS = 60L
        const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        const val DISTRICT_REACH_KM = 60.0
        const val TOWN_MARGIN_KM = 1.0
        const val POSTCODE_MARGIN_KM = 2.0
        const val TOWN_RADIUS_KM = 4.0
        const val POSTCODE_RADIUS_KM = 8.0

        /** Streets up to this long (corner to corner) are placed at their middle. */
        const val SHORT_STREET_KM = 0.8
        const val POSTCODE_MAX_SIZE_KM = 4.0

        /** The last number in an address, its house number ("25ης Μαρτίου 31" → 31). */
        val HOUSE_NUMBER = Regex("""(?<![\p{L}\d])\d+(?!\d)""")

        /** MapTiler result types that are towns, villages or parts of them. */
        const val TOWN_TYPES = "municipality,municipal_district,locality,neighbourhood,place"
    }
}
