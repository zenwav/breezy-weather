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

package org.breezyweather.sources.openaq

import io.kotest.matchers.shouldBe
import org.breezyweather.common.options.StationFilterPreference
import org.breezyweather.domain.weather.index.PollutantIndex
import org.breezyweather.sources.openaq.json.OpenAqCoordinates
import org.breezyweather.sources.openaq.json.OpenAqDatetime
import org.breezyweather.sources.openaq.json.OpenAqLatestItem
import org.breezyweather.sources.openaq.json.OpenAqLocation
import org.breezyweather.sources.openaq.json.OpenAqParameter
import org.breezyweather.sources.openaq.json.OpenAqSensor
import org.breezyweather.unit.computing.computePollutantInUgm3FromPpb
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.milligramsPerCubicMeter
import org.junit.jupiter.api.Test

class OpenAqServiceTest {

    private val nowMs = OpenAqService.parseUtcIsoTimestampMillis("2026-02-01T12:00:00Z")!!
    private val recentTime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")
    private val staleTime = OpenAqDatetime(utc = "2026-01-25T11:30:00Z")

    private fun pm25Sensors() = listOf(
        OpenAqSensor(
            id = 101,
            parameter = OpenAqParameter(name = "pm25", units = "µg/m³")
        )
    )

    @Test
    fun `rankCandidateLocations respects station filter and recency`() {
        val govIitDelhi = OpenAqLocation(
            id = 1,
            name = "IIT Delhi - CPCB",
            isMonitor = true,
            coordinates = OpenAqCoordinates(latitude = 28.5450, longitude = 77.1926),
            sensors = pm25Sensors(),
            datetimeLast = recentTime
        )
        val communityAdchini = OpenAqLocation(
            id = 2,
            name = "AirGradient Adchini",
            isMonitor = false,
            coordinates = OpenAqCoordinates(latitude = 28.5380, longitude = 77.1980),
            sensors = pm25Sensors(),
            datetimeLast = recentTime
        )
        val staleCloserCommunity = OpenAqLocation(
            id = 3,
            name = "Stale Community Sensor",
            isMonitor = false,
            coordinates = OpenAqCoordinates(latitude = 28.5449, longitude = 77.1926),
            sensors = pm25Sensors(),
            datetimeLast = staleTime
        )

        val adchiniLat = 28.5448
        val adchiniLon = 77.1927

        val allRanked = OpenAqService.rankCandidateLocations(
            locations = listOf(communityAdchini, govIitDelhi, staleCloserCommunity),
            targetLat = adchiniLat,
            targetLon = adchiniLon,
            stationFilter = StationFilterPreference.ALL,
            nowMs = nowMs
        )
        allRanked.first().id shouldBe govIitDelhi.id

        val communityRanked = OpenAqService.rankCandidateLocations(
            locations = listOf(communityAdchini, govIitDelhi, staleCloserCommunity),
            targetLat = adchiniLat,
            targetLon = adchiniLon,
            stationFilter = StationFilterPreference.COMMUNITY,
            nowMs = nowMs
        )
        communityRanked.first().id shouldBe communityAdchini.id
    }

    @Test
    fun `rankCandidateLocations falls back when no community stations exist`() {
        val govOnly = OpenAqLocation(
            id = 10,
            name = "IIT Delhi",
            isMonitor = true,
            distance = 300.0,
            sensors = pm25Sensors(),
            datetimeLast = recentTime
        )

        val ranked = OpenAqService.rankCandidateLocations(
            locations = listOf(govOnly),
            targetLat = 28.5450,
            targetLon = 77.1926,
            stationFilter = StationFilterPreference.COMMUNITY,
            nowMs = nowMs
        )
        ranked.single().id shouldBe 10
    }

    @Test
    fun `convertToAirQuality converts units and ignores negative readings`() {
        val sensors = listOf(
            OpenAqSensor(id = 1, parameter = OpenAqParameter(name = "pm25", units = "µg/m³")),
            OpenAqSensor(id = 2, parameter = OpenAqParameter(name = "pm10", units = "µg/m³")),
            OpenAqSensor(id = 3, parameter = OpenAqParameter(name = "o3", units = "ppb")),
            OpenAqSensor(id = 4, parameter = OpenAqParameter(name = "no2", units = "ppm")),
            OpenAqSensor(id = 5, parameter = OpenAqParameter(name = "so2", units = "ppb")),
            OpenAqSensor(id = 6, parameter = OpenAqParameter(name = "co", units = "ppm"))
        )

        val latest = listOf(
            OpenAqLatestItem(sensorsId = 1, value = 112.4, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")),
            OpenAqLatestItem(sensorsId = 2, value = -999.0, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")),
            OpenAqLatestItem(sensorsId = 3, value = 40.0, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")),
            OpenAqLatestItem(sensorsId = 4, value = 0.05, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")),
            OpenAqLatestItem(sensorsId = 5, value = 10.0, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z")),
            OpenAqLatestItem(sensorsId = 6, value = 1.2, datetime = OpenAqDatetime(utc = "2026-02-01T11:30:00Z"))
        )

        val airQuality = OpenAqService.convertToAirQuality(sensors, latest)
        airQuality.isValid shouldBe true
        airQuality.pM25 shouldBe 112.4.microgramsPerCubicMeter
        airQuality.pM10 shouldBe null

        val expectedO3 = computePollutantInUgm3FromPpb(PollutantIndex.O3.molecularMass, 40.0)!!
        val expectedNo2 = computePollutantInUgm3FromPpb(PollutantIndex.NO2.molecularMass, 50.0)!!
        val expectedSo2 = computePollutantInUgm3FromPpb(PollutantIndex.SO2.molecularMass, 10.0)!!
        val expectedCo = computePollutantInUgm3FromPpb(PollutantIndex.CO.molecularMass, 1200.0)!!

        airQuality.o3 shouldBe expectedO3.microgramsPerCubicMeter
        airQuality.nO2 shouldBe expectedNo2.microgramsPerCubicMeter
        airQuality.sO2 shouldBe expectedSo2.microgramsPerCubicMeter
        airQuality.cO shouldBe expectedCo.microgramsPerCubicMeter
        OpenAqService.toConcentration("co", "mg/m3", 1.2) shouldBe 1.2.milligramsPerCubicMeter
    }
}
