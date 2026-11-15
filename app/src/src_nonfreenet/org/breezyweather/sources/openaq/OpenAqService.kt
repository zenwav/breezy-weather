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

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.wrappers.AirQualityWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import com.google.maps.android.SphericalUtil
import com.google.maps.android.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.exceptions.InvalidLocationException
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.options.StationFilterPreference
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.ListPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.domain.settings.SourceConfigStore
import org.breezyweather.domain.weather.index.PollutantIndex
import org.breezyweather.sources.openaq.json.OpenAqLatestItem
import org.breezyweather.sources.openaq.json.OpenAqLocation
import org.breezyweather.sources.openaq.json.OpenAqSensor
import org.breezyweather.unit.computing.computePollutantInUgm3FromPpb
import org.breezyweather.unit.pollutant.PollutantConcentration
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.milligramsPerCubicMeter
import retrofit2.Retrofit
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Named

class OpenAqService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : OpenAqServiceStub() {

    override val privacyPolicyUrl = "https://openaq.org/privacy/"

    override val attributionLinks = mapOf(
        name to "https://openaq.org/"
    )

    private val mApi by lazy {
        client
            .baseUrl(OPENAQ_BASE_URL)
            .build()
            .create(OpenAqApi::class.java)
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

        val coordinates = String.format(Locale.US, "%.4f,%.4f", location.latitude, location.longitude)
        val filter = stationFilter

        return mApi.getLocations(apiKey = apiKey, coordinates = coordinates)
            .flatMap { locationsResult ->
                val candidates = rankCandidateLocations(
                    locations = locationsResult.results.orEmpty(),
                    targetLat = location.latitude,
                    targetLon = location.longitude,
                    stationFilter = filter
                )
                if (candidates.isEmpty()) {
                    Observable.error(InvalidLocationException())
                } else {
                    Observable.fromIterable(candidates.take(MAX_CANDIDATE_ATTEMPTS))
                        .concatMap { candidate ->
                            mApi.getLocationLatest(apiKey = apiKey, locationId = candidate.id)
                                .map { latestResult ->
                                    convertToAirQuality(
                                        sensors = candidate.sensors,
                                        latestItems = latestResult.results
                                    )
                                }
                        }
                        .filter { it.isValid }
                        .firstElement()
                        .toObservable()
                        .switchIfEmpty(Observable.error(InvalidOrIncompleteDataException()))
                        .map { airQuality ->
                            WeatherWrapper(
                                airQuality = AirQualityWrapper(current = airQuality)
                            )
                        }
                }
            }
    }

    private val config = SourceConfigStore(context, id)

    private var apikey: String
        set(value) {
            config.edit().putString("apikey", value).apply()
        }
        get() = config.getString("apikey", null) ?: ""

    private var stationFilter: StationFilterPreference
        set(value) {
            config.edit().putString("station_filter", value.id).apply()
        }
        get() = StationFilterPreference.getInstance(
            config.getString("station_filter", null) ?: StationFilterPreference.ALL.id
        )

    private fun getApiKeyOrDefault(): String {
        return apikey.ifEmpty { BuildConfig.OPENAQ_KEY }.trim()
    }

    override val isConfigured
        get() = getApiKeyOrDefault().isNotEmpty()

    override val isRestricted = false

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_source_openaq_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = apikey,
                onValueChanged = {
                    apikey = it.trim()
                }
            ),
            ListPreference(
                titleId = R.string.settings_weather_source_openaq_station_filter,
                valueArrayId = stationFilter.valueArrayId,
                nameArrayId = stationFilter.nameArrayId,
                selectedKey = stationFilter.id,
                onValueChanged = {
                    stationFilter = StationFilterPreference.getInstance(it)
                }
            )
        )
    }

    companion object {
        private const val OPENAQ_BASE_URL = "https://api.openaq.org/"
        private const val MAX_STATION_AGE_MS = 24L * 60 * 60 * 1000
        private const val MAX_CANDIDATE_ATTEMPTS = 2
        private val SUPPORTED_PARAMETERS = setOf("pm25", "pm10", "o3", "no2", "so2", "co")
        private val UTC_ISO_REGEX = Regex(
            """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]00:00)?$"""
        )

        internal fun rankCandidateLocations(
            locations: List<OpenAqLocation>,
            targetLat: Double,
            targetLon: Double,
            stationFilter: StationFilterPreference,
            nowMs: Long = System.currentTimeMillis(),
        ): List<OpenAqLocation> {
            val valid = locations.filter { hasSupportedPollutant(it) }
            if (valid.isEmpty()) return emptyList()

            val pool = if (stationFilter == StationFilterPreference.COMMUNITY) {
                valid.filter { it.isMonitor == false }.ifEmpty { valid }
            } else {
                valid
            }

            val recent = pool.filter { isRecentLocation(it, nowMs) }
            val activePool = recent.ifEmpty { pool }

            val target = LatLng(targetLat, targetLon)
            return activePool.sortedBy { loc ->
                loc.distance ?: loc.coordinates?.let { coords ->
                    val lat = coords.latitude
                    val lon = coords.longitude
                    if (lat != null && lon != null) {
                        SphericalUtil.computeDistanceBetween(target, LatLng(lat, lon))
                    } else {
                        null
                    }
                } ?: Double.MAX_VALUE
            }
        }

        internal fun hasSupportedPollutant(location: OpenAqLocation): Boolean {
            return location.sensors.orEmpty().any { sensor ->
                sensor.parameter?.name?.lowercase(Locale.ENGLISH) in SUPPORTED_PARAMETERS
            }
        }

        private fun isRecentLocation(location: OpenAqLocation, nowMs: Long): Boolean {
            val lastMs = parseUtcIsoTimestampMillis(location.datetimeLast?.utc) ?: return false
            return nowMs - lastMs in 0..MAX_STATION_AGE_MS
        }

        internal fun parseUtcIsoTimestampMillis(timestamp: String?): Long? {
            val match = UTC_ISO_REGEX.matchEntire(timestamp?.trim() ?: return null) ?: return null
            val (year, month, day, hour, minute, second) = match.destructured
            return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        internal fun convertToAirQuality(
            sensors: List<OpenAqSensor>?,
            latestItems: List<OpenAqLatestItem>?,
        ): AirQuality {
            val sensorsById = sensors.orEmpty().associateBy { it.id }

            // Build sensorId -> (value, timestampMs) from /latest, falling back to embedded sensor.latest
            val measurementsByParam = mutableMapOf<String, Pair<Double, Long>>()
            val itemsToProcess = if (!latestItems.isNullOrEmpty()) {
                latestItems.mapNotNull { item ->
                    val sensor = item.sensorsId?.let { sensorsById[it] } ?: return@mapNotNull null
                    val value = item.value ?: return@mapNotNull null
                    Triple(sensor, value, parseUtcIsoTimestampMillis(item.datetime?.utc) ?: 0L)
                }
            } else {
                sensors.orEmpty().mapNotNull { sensor ->
                    val value = sensor.latest?.value ?: return@mapNotNull null
                    Triple(sensor, value, parseUtcIsoTimestampMillis(sensor.latest.datetime?.utc) ?: 0L)
                }
            }

            val concentrations = mutableMapOf<String, PollutantConcentration>()
            for ((sensor, value, timestampMs) in itemsToProcess) {
                if (!value.isFinite() || value < 0.0) continue
                val paramName = sensor.parameter?.name?.lowercase(Locale.ENGLISH) ?: continue
                if (paramName !in SUPPORTED_PARAMETERS) continue

                val prevTimestamp = measurementsByParam[paramName]?.second
                if (prevTimestamp != null && timestampMs < prevTimestamp) continue

                val concentration = toConcentration(paramName, sensor.parameter.units, value) ?: continue
                measurementsByParam[paramName] = value to timestampMs
                concentrations[paramName] = concentration
            }

            return AirQuality(
                pM25 = concentrations["pm25"],
                pM10 = concentrations["pm10"],
                sO2 = concentrations["so2"],
                nO2 = concentrations["no2"],
                o3 = concentrations["o3"],
                cO = concentrations["co"]
            )
        }

        internal fun toConcentration(
            paramName: String,
            units: String?,
            value: Double,
        ): PollutantConcentration? {
            val normalizedUnit = units?.trim()?.lowercase(Locale.ENGLISH)
            val molecularMass = when (paramName) {
                "pm25", "pm10" -> null
                "o3" -> PollutantIndex.O3.molecularMass
                "no2" -> PollutantIndex.NO2.molecularMass
                "so2" -> PollutantIndex.SO2.molecularMass
                "co" -> PollutantIndex.CO.molecularMass
                else -> return null
            }

            if (molecularMass == null) {
                return when (normalizedUnit) {
                    null, "", "µg/m³", "µg/m3", "ug/m³", "ug/m3" -> value.microgramsPerCubicMeter
                    "mg/m³", "mg/m3" -> value.milligramsPerCubicMeter
                    else -> null
                }
            }

            return when (normalizedUnit) {
                "ppm" -> computePollutantInUgm3FromPpb(molecularMass, value * 1000.0)?.microgramsPerCubicMeter
                "ppb" -> computePollutantInUgm3FromPpb(molecularMass, value)?.microgramsPerCubicMeter
                "mg/m³", "mg/m3" -> value.milligramsPerCubicMeter
                "µg/m³", "µg/m3", "ug/m³", "ug/m3" -> value.microgramsPerCubicMeter
                else -> null
            }
        }
    }
}
