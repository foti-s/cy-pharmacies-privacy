package feed

import cy.pharmacies.app.data.District
import cy.pharmacies.app.data.Feed
import cy.pharmacies.app.data.MinistrySource
import cy.pharmacies.app.data.Pharmacy
import cy.pharmacies.app.data.PortalSource
import cy.pharmacies.app.data.Records
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.system.exitProcess

/**
 * Builds feed/v1/pharmacies.json: every district's duty pharmacies from yesterday's night to
 * three nights ahead, the register of all pharmacies, and a map position for each address.
 * Run every few hours by the "Update feed" workflow.
 *
 * A source that fails keeps its part of the previous file (with its old date, so the app can
 * tell how fresh each part is). If everything fails, nothing is written and the run fails.
 *
 * Usage: run with the repository root as the only argument; MAPTILER_KEY in the environment
 * lets it look up new addresses (without it, only addresses already in the cache get positions).
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: "..")
    val out = File(root, "feed/v1/pharmacies.json")
    val previous = out.takeIf { it.exists() }?.let { runCatching { Feed.decode(it.readText()) }.getOrNull() }
    val now = System.currentTimeMillis()

    // The night that is "on" now: until 08:00 it's still the previous date's list (as in the app).
    val tonight = ZonedDateTime.now(ZoneId.of("Asia/Nicosia")).minusHours(8).toLocalDate()
    val from = tonight.minusDays(1)
    val until = tonight.plusDays(3)

    var failures = 0
    val nights = District.entries.mapNotNull { district ->
        val fresh = runCatching {
            val portal = runCatching { Records.parseSchedule(PortalSource.download(district), from, until) }
            val ministry = if (portal.getOrNull()?.latest?.let { it >= until } == true) null else {
                runCatching { Records.parseSchedule(MinistrySource.download(district), from, until) }
                    .onFailure { println("  $district ministry: ${it.message}") }.getOrNull()
            }
            portal.onFailure { println("  $district portal: ${it.message}") }
            if (portal.isFailure && ministry == null) error("no source worked")
            val merged = Records.merge(portal.getOrNull(), ministry)
            Feed.Nights(now, merged.latest, merged.pharmacies)
        }
        val n = fresh.getOrElse {
            failures++
            println("$district: ${it.message}; keeping the previous copy")
            previous?.nights?.get(district)
        }
        n?.let { district to it }
    }.toMap()

    val registerResult = runCatching { Records.parseRegister(MinistrySource.downloadRegister()) }
    val register = registerResult.getOrElse {
        failures++
        println("Register: ${it.message}; keeping the previous copy")
        previous?.register.orEmpty()
    }
    val registerUpdatedAt = if (registerResult.isSuccess) now else previous?.registerUpdatedAt

    if (failures == District.entries.size + 1) {
        System.err.println("Every source failed; leaving the feed as it was.")
        exitProcess(1)
    }

    // Night lists have no postcodes; the register has them, by registration number.
    val postcodes = register.associate { it.regNo to it.postcode }
    val withPostcodes = nights.mapValues { (_, n) ->
        Feed.Nights(n.updatedAt, n.latest, n.pharmacies.map { p -> if (p.postcode.isBlank()) p.copy(postcode = postcodes[p.regNo].orEmpty()) else p })
    }

    val geocoder = Geocoder(
        key = System.getenv("MAPTILER_KEY").orEmpty(),
        cacheFile = File(root, "generator/geocode-cache.json"),
        overridesFile = File(root, "generator/overrides.json"),
    )
    val everyone: List<Pharmacy> = register + withPostcodes.values.flatMap { it.pharmacies }
    val positions = HashMap<String, Feed.Position>()
    for ((i, p) in everyone.distinctBy(Feed::positionKey).withIndex()) {
        geocoder.position(p)?.let { positions[Feed.positionKey(p)] = it }
        // Saved as it goes, so a run cut short (the job's time limit, a crash) keeps what it found.
        if ((i + 1) % 50 == 0) {
            geocoder.save()
            println("  ${i + 1} addresses done (${geocoder.lookups} MapTiler requests)")
        }
    }
    geocoder.save()

    out.parentFile.mkdirs()
    out.writeText(Feed.encode(Feed.Data(now, withPostcodes, registerUpdatedAt, register, positions)))
    writeToCheck(File(root, "generator/to-check.json"), everyone, positions, withPostcodes)

    val byPrecision = positions.values.groupingBy { it.precision }.eachCount()
    val addresses = everyone.distinctBy(Feed::positionKey).size
    println(
        "Wrote ${out.path}: ${withPostcodes.values.sumOf { it.pharmacies.size }} duty entries, " +
            "${register.size} registered pharmacies, $addresses addresses: " +
            "${byPrecision[Feed.Precision.STREET] ?: 0} street, ${byPrecision[Feed.Precision.POSTCODE] ?: 0} postcode, " +
            "${byPrecision[Feed.Precision.TOWN] ?: 0} town, ${addresses - positions.size} unplaced " +
            "(${geocoder.lookups} MapTiler requests)."
    )
}

/**
 * The addresses whose position is only approximate (postcode or town) or missing, for checking
 * by hand: duty pharmacies first, then by district and area. A checked position goes in
 * overrides.json and the address drops off this list on the next run.
 */
private fun writeToCheck(file: File, everyone: List<Pharmacy>, positions: Map<String, Feed.Position>, nights: Map<District, Feed.Nights>) {
    val onDuty = nights.values.flatMap { it.pharmacies }.mapTo(HashSet()) { Feed.positionKey(it) }
    val entries = everyone.distinctBy(Feed::positionKey)
        .filter { positions[Feed.positionKey(it)]?.precision != Feed.Precision.STREET }
        .sortedWith(compareBy({ Feed.positionKey(it) !in onDuty }, { it.district }, { it.area }, { it.name }))
        .map { p ->
            val key = Feed.positionKey(p)
            val pos = positions[key]
            JSONObject().apply {
                put("key", key)
                put("name", p.name)
                put("address", p.address)
                if (p.directions.isNotBlank()) put("directions", p.directions)
                put("area", p.area)
                p.district?.let { put("district", it.name) }
                if (p.postcode.isNotBlank()) put("postcode", p.postcode)
                put("onDuty", key in onDuty)
                put("now", pos?.precision?.name ?: "MISSING")
                pos?.let { put("lat", it.lat); put("lng", it.lng) }
            }
        }
    file.writeText(JSONArray(entries).toString(1))
    println("${entries.size} addresses to check by hand (generator/to-check.json).")
}
