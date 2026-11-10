package cy.pharmacies.app.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double) {

    /** Straight-line ("as the crow flies") distance in kilometres. */
    fun distanceKm(other: LatLng): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    private companion object {
        const val EARTH_RADIUS_KM = 6371.0
    }
}

/** A rectangle of the map, such as the part currently on screen. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {

    val center: LatLng get() = LatLng((south + north) / 2, (west + east) / 2)

    operator fun contains(p: LatLng) = p.lat in south..north && p.lng in west..east

    /** Kilometres from [p] to the nearest point of the rectangle; 0 when it's inside. */
    fun distanceKmTo(p: LatLng): Double =
        p.distanceKm(LatLng(p.lat.coerceIn(south, north), p.lng.coerceIn(west, east)))
}

/** A pharmacy whose address we could place on the map. */
data class PlacedPharmacy(
    val pharmacy: Pharmacy,
    val position: LatLng,
    /** Placed by its postcode area or town rather than its street address, so only near it. */
    val approximate: Boolean = false,
)

/** Rough box around the island, used to reject geocoder matches elsewhere in the world. */
object CyprusBounds {
    const val SOUTH = 34.45
    const val WEST = 32.20
    const val NORTH = 35.75
    const val EAST = 34.65

    fun contains(p: LatLng) = p.lat in SOUTH..NORTH && p.lng in WEST..EAST
}
