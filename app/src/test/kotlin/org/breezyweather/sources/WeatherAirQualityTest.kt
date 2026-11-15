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

package org.breezyweather.sources

import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.model.Current
import breezyweather.domain.weather.model.Weather
import io.kotest.matchers.shouldBe
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.junit.jupiter.api.Test
import java.util.Date

class WeatherAirQualityTest {

    @Test
    fun `toAirQualityWrapperList preserves valid current air quality when hourly and daily are empty`() {
        val currentAq = AirQuality(pM25 = 92.0.microgramsPerCubicMeter)
        val weather = Weather(current = Current(airQuality = currentAq))

        val wrapper = weather.toAirQualityWrapperList(Date(0L))
        wrapper?.current shouldBe currentAq
        wrapper?.hourlyForecast shouldBe emptyMap()
        wrapper?.dailyForecast shouldBe emptyMap()
    }

    @Test
    fun `toAirQualityWrapperList returns null when current air quality is invalid and forecasts are empty`() {
        val invalidAq = AirQuality()
        val weather = Weather(current = Current(airQuality = invalidAq))

        weather.toAirQualityWrapperList(Date(0L)) shouldBe null
        Weather().toAirQualityWrapperList(Date(0L)) shouldBe null
    }
}
