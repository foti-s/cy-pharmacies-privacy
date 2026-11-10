package cy.pharmacies.app.data

import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The five districts published by the Pharmaceutical Services on data.gov.cy.
 * Each district is a separate datastore resource in the dataset
 * "Κατάλογος Διημερευόντων Φαρμακείων".
 */
enum class District(
    val resourceId: String,
    /** As it appears in the Ministry of Health's file names. */
    val greekName: String,
    val lat: Double,
    val lng: Double,
) {
    NICOSIA("468b39e8-811d-4586-8e5e-37533d801575", "Λευκωσία", 35.1856, 33.3823),
    LIMASSOL("97282b19-bc01-48e4-983e-5ff65a1fb135", "Λεμεσός", 34.7071, 33.0226),
    LARNACA("84ff41ba-65b8-4ec7-9f31-8130fbe2d1b1", "Λάρνακα", 34.9003, 33.6232),
    FAMAGUSTA("cdf7ff43-1928-4e6a-a3b9-ff4228cefbfe", "Αμμόχωστος", 35.0375, 33.9833),
    PAPHOS("802df2db-2b28-4bb3-b355-e437acdf728d", "Πάφος", 34.7754, 32.4245);

    companion object {
        /** Picks the district whose main town is closest to the given point. */
        fun nearest(lat: Double, lng: Double): District = entries.minBy { d ->
            val dLat = d.lat - lat
            val dLng = (d.lng - lng) * cos(Math.toRadians(lat))
            sqrt(dLat * dLat + dLng * dLng)
        }

        fun fromName(name: String?): District? = entries.firstOrNull { it.name == name }
    }
}

data class Pharmacy(
    val regNo: String,
    /** Pharmacies in Cyprus are named after the pharmacist. */
    val name: String,
    val address: String,
    /** Landmark hints such as "Next to the EKO petrol station". */
    val directions: String,
    val area: String,
    val phone: String,
    /** The pharmacist's home number, used for night calls. */
    val housePhone: String,
    /** The night this pharmacy is on duty; null for entries from the full register. */
    val date: LocalDate?,
    /** Set for register entries, which are grouped by district. */
    val district: District? = null,
    /** Postal code (register entries; night entries get it from the register). Helps place it on the map. */
    val postcode: String = "",
)

data class LoadResult(
    val pharmacies: List<Pharmacy>,
    val savedAt: Long,
    /** True when the network failed and we are showing an older saved copy. */
    val offline: Boolean,
    /** The last night the schedule covers, which may lie outside the nights in [pharmacies]. */
    val latestDate: LocalDate? = null,
    /** With [offline]: the download failed because there was no internet, not because the site failed. */
    val noConnection: Boolean = false,
)
