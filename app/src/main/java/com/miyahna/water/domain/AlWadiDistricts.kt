package com.miyahna.water.domain

import kotlin.math.hypot

data class DistrictGeoPoint(
    val name: String,
    val lat: Double,
    val lng: Double
)

object AlWadiDistricts {
    const val DEFAULT_DISTRICT = "حصون آل جلال"

    val districts: List<DistrictGeoPoint> = listOf(
        DistrictGeoPoint("حصون آل جلال", 15.4920, 45.3620),
        DistrictGeoPoint("منطقة آل جلال", 15.4890, 45.3680),
        DistrictGeoPoint("العرقين", 15.4960, 45.3820),
        DistrictGeoPoint("الخشعة", 15.4810, 45.3740),
        DistrictGeoPoint("الصمدة", 15.4760, 45.3560),
        DistrictGeoPoint("مفرق الوادي", 15.4730, 45.3490),
        DistrictGeoPoint("المنين - الوادي", 15.4680, 45.3710),
        DistrictGeoPoint("الحدباء", 15.4840, 45.3910),
        DistrictGeoPoint("آل شبوان", 15.5020, 45.3980),
        DistrictGeoPoint("آل فجيح", 15.4720, 45.3850),
        DistrictGeoPoint("الحزم - الوادي", 15.4630, 45.3610),
        DistrictGeoPoint("الضمين", 15.4580, 45.3790),
        DistrictGeoPoint("آل مثنى", 15.4990, 45.3720),
        DistrictGeoPoint("سميح", 15.4690, 45.4020),
        DistrictGeoPoint("الخسيف - الوادي", 15.4550, 45.3440),
        DistrictGeoPoint("وادي عبيدة - الخط الدولي", 15.4860, 45.4120)
    )

    val districtNames: List<String> = districts.map { it.name }

    fun mergeDistrictLists(
        customDistricts: List<String> = emptyList(),
        customerDistricts: List<String> = emptyList(),
        pendingOfflineDistricts: List<String> = emptyList()
    ): List<String> {
        val seen = linkedSetOf<String>()
        fun pushUnique(value: String?) {
            val clean = value?.trim().orEmpty()
            if (clean.isEmpty() || clean == "__other__" || clean == "أخرى") return
            seen.add(clean)
        }
        districtNames.forEach(::pushUnique)
        customDistricts.forEach(::pushUnique)
        pendingOfflineDistricts.forEach(::pushUnique)
        customerDistricts.forEach(::pushUnique)
        return seen.toList()
    }

    fun getClosestDistrict(lat: Double, lng: Double): String {
        if (!lat.isFinite() || !lng.isFinite()) return DEFAULT_DISTRICT
        var closest = districts.first().name
        var minDist = Double.POSITIVE_INFINITY
        for (d in districts) {
            val dist = hypot(d.lat - lat, d.lng - lng)
            if (dist.isFinite() && dist < minDist) {
                minDist = dist
                closest = d.name
            }
        }
        return closest
    }

    fun coordinatesForDistrict(name: String): Pair<Double, Double> {
        val found = districts.firstOrNull { it.name == name.trim() }
        return if (found != null) {
            found.lat to found.lng
        } else {
            districts.first().lat to districts.first().lng
        }
    }
}
