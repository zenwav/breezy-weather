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

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.wrappers.AirQualityWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.exceptions.ApiLimitReachedException
import org.breezyweather.common.exceptions.ApiUnauthorizedException
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.domain.settings.SourceConfigStore
import org.breezyweather.domain.weather.index.PollutantIndex
import org.breezyweather.sources.iqair.json.IqAirPollution
import org.breezyweather.unit.computing.computePollutantInUgm3FromPpb
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import retrofit2.Retrofit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

class IqAirService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : IqAirServiceStub() {

    override val privacyPolicyUrl = "https://www.iqair.com/privacy-policy"

    override val attributionLinks = mapOf(
        name to "https://www.iqair.com/"
    )

    private val mApi by lazy {
        client
            .baseUrl(IQAIR_BASE_URL)
            .build()
            .create(IqAirApi::class.java)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        if (!requestedFeatures.contains(SourceFeature.AIR_QUALITY)) {
            return Observable.just(WeatherWrapper())
        }

        val apiKey = getApiKeyOrDefault()
        if (apiKey.isEmpty()) {
            return Observable.error(ApiKeyMissingException())
        }

        return mApi.getNearestCity(
            lat = location.latitude,
            lon = location.longitude,
            key = apiKey
        ).map { result ->
            if (result.status != "success") {
                when (result.data?.message?.lowercase(Locale.ENGLISH)) {
                    "incorrect_api_key", "api_key_expired" -> throw ApiUnauthorizedException()
                    "call_limit_reached", "permission_denied" -> throw ApiLimitReachedException()
                    else -> throw InvalidOrIncompleteDataException()
                }
            }

            val airQuality = convertPollutionToAirQuality(result.data?.current?.pollution)
                ?: throw InvalidOrIncompleteDataException()

            WeatherWrapper(
                airQuality = AirQualityWrapper(current = airQuality)
            )
        }
    }

    private val config = SourceConfigStore(context, id)

    private var apikey: String
        set(value) {
            config.edit().putString("apikey", value).apply()
        }
        get() = config.getString("apikey", null) ?: ""

    private fun getApiKeyOrDefault(): String {
        return apikey.ifEmpty { BuildConfig.IQAIR_KEY }.trim()
    }

    override val isConfigured
        get() = getApiKeyOrDefault().isNotEmpty()

    override val isRestricted = false

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_source_iqair_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = apikey,
                onValueChanged = {
                    apikey = it.trim()
                }
            )
        )
    }

    companion object {
        private const val IQAIR_BASE_URL = "https://api.airvisual.com/"

        internal data class EpaBreakpoint(
            val iLo: Int,
            val iHi: Int,
            val bpLo: Double,
            val bpHi: Double,
        )

        // US-EPA 40 CFR Part 58 Appendix G piecewise linear breakpoints (PM2.5 includes 2024 AQI 50 update)
        private val PM25_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 9.0),
            EpaBreakpoint(51, 100, 9.1, 35.4),
            EpaBreakpoint(101, 150, 35.5, 55.4),
            EpaBreakpoint(151, 200, 55.5, 125.4),
            EpaBreakpoint(201, 300, 125.5, 225.4),
            EpaBreakpoint(301, 500, 225.5, 325.4)
        )

        private val PM10_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 54.0),
            EpaBreakpoint(51, 100, 55.0, 154.0),
            EpaBreakpoint(101, 150, 155.0, 254.0),
            EpaBreakpoint(151, 200, 255.0, 354.0),
            EpaBreakpoint(201, 300, 355.0, 424.0),
            EpaBreakpoint(301, 400, 425.0, 504.0),
            EpaBreakpoint(401, 500, 505.0, 604.0)
        )

        // O3 in ppb (8-hr for 0..300, 1-hr for 301..500)
        private val O3_PPB_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 54.0),
            EpaBreakpoint(51, 100, 55.0, 70.0),
            EpaBreakpoint(101, 150, 71.0, 85.0),
            EpaBreakpoint(151, 200, 86.0, 105.0),
            EpaBreakpoint(201, 300, 106.0, 200.0),
            EpaBreakpoint(301, 400, 405.0, 504.0),
            EpaBreakpoint(401, 500, 505.0, 604.0)
        )

        // NO2 in ppb
        private val NO2_PPB_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 53.0),
            EpaBreakpoint(51, 100, 54.0, 100.0),
            EpaBreakpoint(101, 150, 101.0, 360.0),
            EpaBreakpoint(151, 200, 361.0, 649.0),
            EpaBreakpoint(201, 300, 650.0, 1249.0),
            EpaBreakpoint(301, 400, 1250.0, 1649.0),
            EpaBreakpoint(401, 500, 1650.0, 2049.0)
        )

        // SO2 in ppb
        private val SO2_PPB_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 35.0),
            EpaBreakpoint(51, 100, 36.0, 75.0),
            EpaBreakpoint(101, 150, 76.0, 185.0),
            EpaBreakpoint(151, 200, 186.0, 304.0),
            EpaBreakpoint(201, 300, 305.0, 604.0),
            EpaBreakpoint(301, 400, 605.0, 804.0),
            EpaBreakpoint(401, 500, 805.0, 1004.0)
        )

        // CO in ppm
        private val CO_PPM_BREAKPOINTS = listOf(
            EpaBreakpoint(0, 50, 0.0, 4.4),
            EpaBreakpoint(51, 100, 4.5, 9.4),
            EpaBreakpoint(101, 150, 9.5, 12.4),
            EpaBreakpoint(151, 200, 12.5, 15.4),
            EpaBreakpoint(201, 300, 15.5, 30.4),
            EpaBreakpoint(301, 400, 30.5, 40.4),
            EpaBreakpoint(401, 500, 40.5, 50.4)
        )

        internal fun invertEpaAqi(
            aqi: Int,
            breakpoints: List<EpaBreakpoint>,
        ): Double? {
            if (aqi < 0 || breakpoints.isEmpty()) return null
            val segment = breakpoints.firstOrNull { aqi in it.iLo..it.iHi } ?: breakpoints.last()
            val span = (segment.iHi - segment.iLo).toDouble()
            return if (span <= 0.0) {
                segment.bpLo
            } else {
                segment.bpLo + ((aqi - segment.iLo) / span) * (segment.bpHi - segment.bpLo)
            }
        }

        internal fun convertPollutionToAirQuality(pollution: IqAirPollution?): AirQuality? {
            val aqiUs = pollution?.aqius ?: return null
            if (aqiUs < 0) return null

            val airQuality = when (pollution.mainus?.trim()?.lowercase(Locale.ENGLISH)) {
                "p2", null, "" -> AirQuality(
                    pM25 = invertEpaAqi(aqiUs, PM25_BREAKPOINTS)?.microgramsPerCubicMeter
                )
                "p1" -> AirQuality(
                    pM10 = invertEpaAqi(aqiUs, PM10_BREAKPOINTS)?.microgramsPerCubicMeter
                )
                "o3" -> AirQuality(
                    o3 = invertEpaAqi(aqiUs, O3_PPB_BREAKPOINTS)?.let { ppb ->
                        computePollutantInUgm3FromPpb(
                            PollutantIndex.O3.molecularMass,
                            ppb
                        )?.microgramsPerCubicMeter
                    }
                )
                "n2" -> AirQuality(
                    nO2 = invertEpaAqi(aqiUs, NO2_PPB_BREAKPOINTS)?.let { ppb ->
                        computePollutantInUgm3FromPpb(
                            PollutantIndex.NO2.molecularMass,
                            ppb
                        )?.microgramsPerCubicMeter
                    }
                )
                "s2" -> AirQuality(
                    sO2 = invertEpaAqi(aqiUs, SO2_PPB_BREAKPOINTS)?.let { ppb ->
                        computePollutantInUgm3FromPpb(
                            PollutantIndex.SO2.molecularMass,
                            ppb
                        )?.microgramsPerCubicMeter
                    }
                )
                "co" -> AirQuality(
                    cO = invertEpaAqi(aqiUs, CO_PPM_BREAKPOINTS)?.let { ppm ->
                        computePollutantInUgm3FromPpb(
                            PollutantIndex.CO.molecularMass,
                            ppm * 1000.0
                        )?.microgramsPerCubicMeter
                    }
                )
                else -> AirQuality(
                    pM25 = invertEpaAqi(aqiUs, PM25_BREAKPOINTS)?.microgramsPerCubicMeter
                )
            }

            return airQuality.takeIf { it.isValid }
        }
    }
}
