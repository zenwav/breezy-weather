/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.aqiin

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.breezyweather.common.options.StationFilterPreference
import org.breezyweather.sources.aqiin.json.AqiInIaqi
import org.breezyweather.sources.aqiin.json.AqiInStationData
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.junit.jupiter.api.Test

class AqiInServiceTest {

    @Test
    fun `formatBearerToken normalizes bearer prefix`() {
        AqiInService.formatBearerToken("eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE5MDAwMDAwMDB9.sig")
            .shouldBe("bearer eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE5MDAwMDAwMDB9.sig")
        AqiInService.formatBearerToken("  Bearer eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE5MDAwMDAwMDB9.sig  ")
            .shouldBe("bearer eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE5MDAwMDAwMDB9.sig")
        AqiInService.formatBearerToken("bearer abc.def.ghi").shouldBe("bearer abc.def.ghi")
        AqiInService.formatBearerToken("   ").shouldBe("")
    }

    @Test
    fun `extractJwtToken and extractJwtExpiryMillis parse token payload`() {
        // Header + {"exp":1900000000} + sig
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE5MDAwMDAwMDB9.testSignature123"
        val html = """<html><script>window.__TOKEN__="$jwt";</script></html>"""

        AqiInService.extractJwtToken(html) shouldBe jwt
        AqiInService.extractJwtExpiryMillis(jwt, nowMs = 1_000L) shouldBe 1_900_000_000_000L
        AqiInService.extractJwtToken("Just a moment... Cloudflare") shouldBe null
    }

    @Test
    fun `selectBestStation respects community vs all station filter`() {
        val govStation = AqiInStationData(
            locationId = "1",
            station = "IIT Delhi (DPCC)",
            locationSlug = "india/delhi/iit-delhi-dpcc",
            distance = 0.4,
            iaqi = AqiInIaqi(pm25 = 95.0, pm10 = 180.0)
        )
        val communityStation = AqiInStationData(
            locationId = "2",
            station = "Adchini",
            locationSlug = "india/delhi/adchini",
            distance = 0.9,
            iaqi = AqiInIaqi(pm25 = 110.0)
        )

        AqiInService.selectBestStation(
            stations = listOf(govStation, communityStation),
            stationFilter = StationFilterPreference.ALL
        )?.locationId shouldBe "1"

        AqiInService.selectBestStation(
            stations = listOf(govStation, communityStation),
            stationFilter = StationFilterPreference.COMMUNITY
        )?.locationId shouldBe "2"

        AqiInService.selectBestStation(
            stations = listOf(govStation),
            stationFilter = StationFilterPreference.COMMUNITY
        )?.locationId shouldBe "1"
    }

    @Test
    fun `convertIaqiToAirQuality and parseSlugStations map valid concentrations`() {
        val airQuality = AqiInService.convertIaqiToAirQuality(
            AqiInIaqi(
                pm25 = 84.5,
                pm10 = 152.0,
                no2 = 42.0,
                o3 = 31.0,
                so2 = 12.0,
                co = 324.0
            )
        )!!

        airQuality.isValid shouldBe true
        airQuality.pM25 shouldBe 84.5.microgramsPerCubicMeter
        airQuality.pM10 shouldBe 152.0.microgramsPerCubicMeter
        airQuality.nO2 shouldBe 42.0.microgramsPerCubicMeter
        airQuality.o3 shouldBe 31.0.microgramsPerCubicMeter
        airQuality.sO2 shouldBe 12.0.microgramsPerCubicMeter
        airQuality.cO shouldBe 324.0.microgramsPerCubicMeter

        val objectJson = Json.parseToJsonElement("""{"locationId":"9","station":"Adchini","iaqi":{"pm25":75.0}}""")
        val arrayJson = Json.parseToJsonElement("""[{"locationId":"9","station":"Adchini","iaqi":{"pm25":75.0}}]""")
        AqiInService.parseSlugStations(objectJson).single().station shouldBe "Adchini"
        AqiInService.parseSlugStations(arrayJson).single().station shouldBe "Adchini"
    }
}
