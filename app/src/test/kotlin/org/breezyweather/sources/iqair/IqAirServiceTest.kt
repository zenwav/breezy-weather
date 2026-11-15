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

package org.breezyweather.sources.iqair

import io.kotest.matchers.shouldBe
import org.breezyweather.domain.weather.index.PollutantIndex
import org.breezyweather.sources.iqair.json.IqAirPollution
import org.breezyweather.unit.computing.computePollutantInUgm3FromPpb
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.junit.jupiter.api.Test
import kotlin.math.abs

class IqAirServiceTest {

    @Test
    fun `convertPollutionToAirQuality inverts US-EPA breakpoints for PM25`() {
        val bp = listOf(
            IqAirService.Companion.EpaBreakpoint(0, 50, 0.0, 9.0),
            IqAirService.Companion.EpaBreakpoint(51, 100, 9.1, 35.4)
        )
        (abs(IqAirService.invertEpaAqi(75, bp)!! - 21.9816) < 1e-3) shouldBe true

        val aq50 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 50, mainus = "p2"))!!
        aq50.pM25 shouldBe 9.0.microgramsPerCubicMeter

        val aq100 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 100, mainus = "p2"))!!
        aq100.pM25 shouldBe 35.4.microgramsPerCubicMeter

        val aq151 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 151, mainus = "p2"))!!
        aq151.pM25 shouldBe 55.5.microgramsPerCubicMeter

        val aq500 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 500, mainus = "p2"))!!
        aq500.pM25 shouldBe 325.4.microgramsPerCubicMeter
    }

    @Test
    fun `convertPollutionToAirQuality inverts PM10 and gaseous pollutants accurately`() {
        val pm10 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 100, mainus = "p1"))!!
        pm10.pM10 shouldBe 154.0.microgramsPerCubicMeter

        val o3 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 50, mainus = "o3"))!!
        val expectedO3 = computePollutantInUgm3FromPpb(PollutantIndex.O3.molecularMass, 54.0)!!
        o3.o3 shouldBe expectedO3.microgramsPerCubicMeter

        val no2 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 100, mainus = "n2"))!!
        val expectedNo2 = computePollutantInUgm3FromPpb(PollutantIndex.NO2.molecularMass, 100.0)!!
        no2.nO2 shouldBe expectedNo2.microgramsPerCubicMeter

        val so2 = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 50, mainus = "s2"))!!
        val expectedSo2 = computePollutantInUgm3FromPpb(PollutantIndex.SO2.molecularMass, 35.0)!!
        so2.sO2 shouldBe expectedSo2.microgramsPerCubicMeter

        val co = IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = 50, mainus = "co"))!!
        val expectedCo = computePollutantInUgm3FromPpb(PollutantIndex.CO.molecularMass, 4400.0)!!
        co.cO shouldBe expectedCo.microgramsPerCubicMeter
    }

    @Test
    fun `convertPollutionToAirQuality returns null on missing or negative aqius`() {
        IqAirService.convertPollutionToAirQuality(null) shouldBe null
        IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = null, mainus = "p2")) shouldBe null
        IqAirService.convertPollutionToAirQuality(IqAirPollution(aqius = -5, mainus = "p2")) shouldBe null
    }
}
