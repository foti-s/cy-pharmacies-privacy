package feed

import cy.pharmacies.app.data.District
import cy.pharmacies.app.data.Feed
import cy.pharmacies.app.data.MinistrySource
import cy.pharmacies.app.data.PortalSource
import cy.pharmacies.app.data.Records
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Daily health check of everything the app depends on: the live feed and what's in it, the
 * sources it's built from, the map tiles, the privacy policy page and the feed job itself.
 *
 * Usage: run with the repository root as the only argument. Writes a Markdown report to
 * health-report.md there and exits with 1 if anything failed (the workflow then opens an issue).
 * MAPTILER_KEY and GITHUB_TOKEN / GITHUB_REPOSITORY come from the environment when available.
 */

private enum class Status(val icon: String) { OK("✅"), WARN("⚠️"), FAIL("❌"), INFO("ℹ️") }

private class Check(val name: String, val status: Status, val detail: String)

private val checks = mutableListOf<Check>()

private fun ok(name: String, detail: String) = checks.add(Check(name, Status.OK, detail))
private fun warn(name: String, detail: String) = checks.add(Check(name, Status.WARN, detail))
private fun fail(name: String, detail: String) = checks.add(Check(name, Status.FAIL, detail))
private fun info(name: String, detail: String) = checks.add(Check(name, Status.INFO, detail))

private val CYPRUS = ZoneId.of("Asia/Nicosia")
private const val HOUR = 60 * 60 * 1000L
private const val DAY = 24 * HOUR

/** The app ignores a feed older than this (PharmacyRepository.FEED_MAX_AGE_MS). */
private const val FEED_MAX_AGE_H = 18L
/** The feed is rebuilt every 3 hours; this much older means a few runs failed. */
private const val FEED_WARN_AGE_H = 8L
/** The app distrusts a register older than this (PharmacyRepository.REGISTER_MAX_AGE_MS). */
private const val REGISTER_APP_MAX_DAYS = 7L
/** Main.kt's REGISTER_TRUST_DAYS is 120: warn well before, fail just before. */
private const val REGISTER_WARN_DAYS = 60L
private const val REGISTER_FAIL_DAYS = 110L
private const val MIN_REGISTER_SIZE = 600

private const val POLICY_URL = "https://foti-s.github.io/cy-pharmacies-privacy/"

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    val now = System.currentTimeMillis()
    val today = LocalDate.now(CYPRUS)

    val data = checkFeed(now)
    if (data != null) {
        checkDuty(data, now, today)
        checkRegister(data, root, now)
        checkPositions(data, today)
    }
    checkSources(today)
    checkPolicy()
    checkTiles()
    checkFeedJob(now)

    val report = report(now)
    File(root, "health-report.md").writeText(report)
    println(report)
    exitProcess(if (checks.any { it.status == Status.FAIL }) 1 else 0)
}

private fun checkFeed(now: Long): Feed.Data? {
    val name = "Live feed"
    val body = try {
        get("${Feed.URL}?health=$now")
    } catch (e: Exception) {
        fail(name, "Couldn't download ${Feed.URL}: ${e.message}")
        return null
    }
    val data = try {
        Feed.decode(body)
    } catch (e: Exception) {
        fail(name, "Downloaded but couldn't be read: ${e.message}")
        return null
    }
    ok(name, "Downloads and reads (${body.length / 1024} KB)")

    val ageH = (now - data.generatedAt) / HOUR
    val built = "built ${ageH} h ago (${time(data.generatedAt)})"
    when {
        ageH >= FEED_MAX_AGE_H -> fail("Feed freshness", "$built: the app ignores a feed over $FEED_MAX_AGE_H h old")
        ageH >= FEED_WARN_AGE_H -> warn("Feed freshness", "$built: several 3-hourly runs must have failed")
        else -> ok("Feed freshness", built)
    }
    return data
}

private fun checkDuty(data: Feed.Data, now: Long, today: LocalDate) {
    val tomorrow = today.plusDays(1)
    for (district in District.entries) {
        val name = "Duty pharmacies: ${district.name.lowercase().replaceFirstChar { it.uppercase() }}"
        val nights = data.nights[district]
        if (nights == null) {
            fail(name, "Missing from the feed")
            continue
        }
        val tonight = nights.pharmacies.count { it.date == today }
        val next = nights.pharmacies.count { it.date == tomorrow }
        val ageH = (now - nights.updatedAt) / HOUR
        val summary = "$tonight tonight, $next tomorrow; read from the sources $ageH h ago"
        when {
            tonight == 0 -> fail(name, "None listed for tonight ($today). $summary")
            ageH >= FEED_MAX_AGE_H -> fail(name, "$summary: the app ignores a district this old")
            next == 0 -> warn(name, "None listed for tomorrow ($tomorrow) yet. $summary")
            else -> ok(name, summary)
        }
    }
}

private fun checkRegister(data: Feed.Data, root: File, now: Long) {
    val size = data.register.size
    val vouched = data.registerUpdatedAt
    when {
        size < MIN_REGISTER_SIZE -> fail("Pharmacy register", "Only $size pharmacies (expected $MIN_REGISTER_SIZE+)")
        vouched == null -> fail("Pharmacy register", "$size pharmacies but no date: the app won't use it")
        now - vouched >= REGISTER_APP_MAX_DAYS * DAY ->
            fail("Pharmacy register", "$size pharmacies, dated ${(now - vouched) / DAY} days ago: the app distrusts it after $REGISTER_APP_MAX_DAYS")
        else -> ok("Pharmacy register", "$size pharmacies, served as current")
    }

    val downloaded = File(root, "generator/register-downloaded-at.txt").takeIf { it.exists() }
        ?.readText()?.trim()?.toLongOrNull()
    if (downloaded == null) {
        info("Register download date", "No record yet (register-downloaded-at.txt)")
        return
    }
    val days = (now - downloaded) / DAY
    val detail = "Last downloaded from the ministry $days days ago (${time(downloaded)})"
    when {
        days >= REGISTER_FAIL_DAYS -> fail("Register download date", "$detail. The feed stops vouching for it at 120 days: get a fresh copy")
        days >= REGISTER_WARN_DAYS -> warn("Register download date", "$detail. Get a fresh copy before it reaches 120 days")
        else -> ok("Register download date", detail)
    }
}

private fun checkPositions(data: Feed.Data, today: LocalDate) {
    val everyone = (data.register + data.nights.values.flatMap { n -> n.pharmacies.filter { it.date != null && it.date >= today } })
        .distinctBy(Feed::positionKey)
    val missing = everyone.filter { data.positions[Feed.positionKey(it)] == null }
    val byPrecision = everyone.mapNotNull { data.positions[Feed.positionKey(it)]?.precision }.groupingBy { it }.eachCount()
    val summary = Feed.Precision.entries.joinToString(", ") { "${byPrecision[it] ?: 0} ${it.name.lowercase()}" }
    if (missing.isEmpty()) {
        ok("Map pins", "All ${everyone.size} addresses placed: $summary")
    } else {
        val names = missing.take(5).joinToString("; ") { "${it.name}, ${it.address}, ${it.area}" }
        fail("Map pins", "${missing.size} of ${everyone.size} have no position (e.g. $names). $summary")
    }
}

private fun checkSources(today: LocalDate) {
    val reached = mutableListOf<String>()
    val failed = mutableListOf<String>()
    for (district in District.entries) {
        try {
            val schedule = Records.parseSchedule(PortalSource.download(district), today, today.plusDays(1))
            reached += "${district.name.lowercase()} (to ${schedule.latest ?: "?"})"
        } catch (e: Exception) {
            failed += "${district.name.lowercase()}: ${e.message}"
        }
    }
    if (failed.isEmpty()) ok("data.gov.cy", "All districts reachable: ${reached.joinToString(", ")}")
    else warn("data.gov.cy", "Not reachable for ${failed.joinToString("; ")}. The feed keeps its last copy meanwhile")

    try {
        val register = Records.parseRegister(MinistrySource.downloadRegister())
        info("gov.cy register download", "Working again (${register.size} pharmacies): the next feed run refreshes the register")
    } catch (e: Exception) {
        info("gov.cy register download", "Still blocked (${e.message}), as since November 2026; the feed serves its last copy")
    }
}

private fun checkPolicy() {
    try {
        val page = get(POLICY_URL)
        if ("Effective" in page) ok("Privacy policy page", "Online at $POLICY_URL")
        else warn("Privacy policy page", "Online but doesn't look like the policy")
    } catch (e: Exception) {
        fail("Privacy policy page", "Not reachable ($POLICY_URL): ${e.message}. Play requires it")
    }
}

private fun checkTiles() {
    val key = System.getenv("MAPTILER_KEY").orEmpty()
    if (key.isBlank()) {
        warn("MapTiler map tiles", "Not checked: the MAPTILER_KEY repository secret isn't set")
        return
    }
    // A tile over Nicosia, the same style the app uses.
    val url = "https://api.maptiler.com/maps/streets-v2/256/12/2427/1620.png?key=$key"
    try {
        val (code, bytes) = getBytes(url)
        if (code == 200 && bytes > 1000) ok("MapTiler map tiles", "A Nicosia tile loads ($bytes bytes)")
        else fail("MapTiler map tiles", "HTTP $code, $bytes bytes: the app's map would be blank (key revoked or quota used up?)")
    } catch (e: Exception) {
        fail("MapTiler map tiles", "Request failed: ${e.message}")
    }
}

private fun checkFeedJob(now: Long) {
    val token = System.getenv("GITHUB_TOKEN").orEmpty()
    val repo = System.getenv("GITHUB_REPOSITORY").orEmpty()
    if (token.isBlank() || repo.isBlank()) {
        info("Feed job", "Not checked here (needs GitHub Actions)")
        return
    }
    try {
        val body = get("https://api.github.com/repos/$repo/actions/workflows/feed.yml/runs?per_page=8", token)
        val runs = JSONObject(body).getJSONArray("runs")
        val finished = (0 until runs.length()).map { runs.getJSONObject(it) }.filter { it.optString("status") == "completed" }
        val last = finished.firstOrNull() ?: run { info("Feed job", "No finished runs yet"); return }
        val failedRecently = finished.take(4).count { it.optString("conclusion") != "success" }
        val at = Instant.parse(last.getString("created_at")).toEpochMilli()
        val detail = "Last run ${(now - at) / HOUR} h ago: ${last.optString("conclusion")}; $failedRecently of the last ${minOf(4, finished.size)} didn't succeed"
        if (last.optString("conclusion") == "success") ok("Feed job", detail) else warn("Feed job", "$detail (${last.optString("html_url")})")
    } catch (e: Exception) {
        warn("Feed job", "Couldn't read its runs: ${e.message}")
    }
}

private fun report(now: Long): String {
    val worst = when {
        checks.any { it.status == Status.FAIL } -> "❌ Something is broken"
        checks.any { it.status == Status.WARN } -> "⚠️ Working, with warnings"
        else -> "✅ Everything is OK"
    }
    return buildString {
        appendLine("## CY Pharmacies health check: $worst")
        appendLine()
        appendLine("Checked ${time(now)} (Cyprus time).")
        appendLine()
        appendLine("| | Check | Details |")
        appendLine("|---|---|---|")
        for (c in checks) appendLine("| ${c.status.icon} | ${c.name} | ${c.detail.replace("|", "/")} |")
    }
}

private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(CYPRUS)
private fun time(ms: Long) = TIME.format(Instant.ofEpochMilli(ms))

private fun get(url: String, token: String? = null): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 40_000
        setRequestProperty("User-Agent", "cy-pharmacies-health")
        token?.let {
            setRequestProperty("Authorization", "Bearer $it")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }
    try {
        if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun getBytes(url: String): Pair<Int, Int> {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 40_000
        setRequestProperty("User-Agent", "cy-pharmacies-health")
    }
    try {
        val code = connection.responseCode
        val size = if (code in 200..299) connection.inputStream.use { it.readBytes().size } else 0
        return code to size
    } finally {
        connection.disconnect()
    }
}
