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

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.wrappers.AirQualityWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.exceptions.ApiUnauthorizedException
import org.breezyweather.common.exceptions.InvalidLocationException
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.options.StationFilterPreference
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.ListPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.domain.settings.SourceConfigStore
import org.breezyweather.sources.aqiin.json.AqiInIaqi
import org.breezyweather.sources.aqiin.json.AqiInStationData
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import retrofit2.HttpException
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Named

class AqiInService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
    private val okHttpClient: OkHttpClient,
) : AqiInServiceStub() {

    override val privacyPolicyUrl = "https://www.aqi.in/privacy-policy"

    override val attributionLinks = mapOf(
        name to AQI_IN_WEB_URL
    )

    private val mApi by lazy {
        client
            .baseUrl(AQI_IN_API_BASE_URL)
            .build()
            .create(AqiInApi::class.java)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        if (!requestedFeatures.contains(SourceFeature.AIR_QUALITY)) {
            return Observable.just(WeatherWrapper())
        }

        val filter = stationFilter

        return Observable.fromCallable { resolveAuthTokenBlocking() }
            .flatMap { (bearerToken, isAutoToken) ->
                mApi.getNearestLocation(
                    authorization = bearerToken,
                    lat = location.latitude,
                    lon = location.longitude
                )
                    .map { nearestResult -> nearestResult to bearerToken }
                    .onErrorResumeNext { error ->
                        if (error is HttpException && error.code() == 401) {
                            if (isAutoToken) {
                                clearCachedToken()
                                Observable.fromCallable { fetchAndCacheWebTokenBlocking() }
                                    .flatMap { freshToken ->
                                        mApi.getNearestLocation(
                                            authorization = freshToken,
                                            lat = location.latitude,
                                            lon = location.longitude
                                        ).map { nearestResult -> nearestResult to freshToken }
                                    }
                            } else {
                                Observable.error(ApiUnauthorizedException())
                            }
                        } else {
                            Observable.error(error)
                        }
                    }
                    .flatMap { (nearestResult, activeToken) ->
                        resolveStationAirQuality(
                            bearerToken = activeToken,
                            stations = nearestResult.data.orEmpty(),
                            stationFilter = filter
                        )
                    }
            }
            .map { airQuality ->
                WeatherWrapper(
                    airQuality = AirQualityWrapper(current = airQuality)
                )
            }
    }

    private fun resolveStationAirQuality(
        bearerToken: String,
        stations: List<AqiInStationData>,
        stationFilter: StationFilterPreference,
    ): Observable<AirQuality> {
        val selectedStation = selectBestStation(stations, stationFilter)
            ?: return Observable.error(InvalidLocationException())

        val directAirQuality = convertIaqiToAirQuality(selectedStation.iaqi)
        if (directAirQuality != null && directAirQuality.isValid) {
            return Observable.just(directAirQuality)
        }

        val slug = selectedStation.locationSlug?.trim().orEmpty()
        if (slug.isEmpty()) {
            return Observable.error(InvalidOrIncompleteDataException())
        }

        val slugType = slug.count { it == '/' } + 1
        return mApi.getLocationDetailsBySlug(
            authorization = bearerToken,
            slug = slug,
            type = slugType
        ).map { slugResult ->
            val slugStations = parseSlugStations(slugResult.data)
            val slugAirQuality = slugStations.firstNotNullOfOrNull { station ->
                convertIaqiToAirQuality(station.iaqi)?.takeIf { it.isValid }
            }
            slugAirQuality ?: throw InvalidOrIncompleteDataException()
        }
    }

    private fun resolveAuthTokenBlocking(): Pair<String, Boolean> {
        val configured = getConfiguredTokenOrDefault()
        if (configured.isNotEmpty()) {
            return formatBearerToken(configured) to false
        }

        val nowMs = System.currentTimeMillis()
        val cached = cachedToken
        if (cached.isNotEmpty() && nowMs + TOKEN_REFRESH_BUFFER_MS < cachedTokenExpMs) {
            return formatBearerToken(cached) to true
        }

        return fetchAndCacheWebTokenBlocking(nowMs) to true
    }

    private fun fetchAndCacheWebTokenBlocking(nowMs: Long = System.currentTimeMillis()): String {
        val request = Request.Builder()
            .url(AQI_IN_WEB_URL)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiKeyMissingException()
                }
                response.body.string()
            }
        } catch (e: ApiKeyMissingException) {
            throw e
        } catch (_: Exception) {
            throw ApiKeyMissingException()
        }

        val rawJwt = extractJwtToken(html) ?: throw ApiKeyMissingException()
        val expMs = extractJwtExpiryMillis(rawJwt, nowMs)
        config.edit()
            .putString(KEY_CACHED_TOKEN, rawJwt)
            .putLong(KEY_CACHED_TOKEN_EXP_MS, expMs)
            .apply()
        return formatBearerToken(rawJwt)
    }

    private fun clearCachedToken() {
        config.edit()
            .putString(KEY_CACHED_TOKEN, "")
            .putLong(KEY_CACHED_TOKEN_EXP_MS, 0L)
            .apply()
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

    private val cachedToken: String
        get() = (config.getString(KEY_CACHED_TOKEN, null) ?: "").trim()

    private val cachedTokenExpMs: Long
        get() = config.getLong(KEY_CACHED_TOKEN_EXP_MS, 0L)

    private fun getConfiguredTokenOrDefault(): String {
        return apikey.ifEmpty { BuildConfig.AQI_IN_KEY }.trim()
    }

    override val isConfigured = true

    override val isRestricted = false

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            EditTextPreference(
                titleId = R.string.settings_weather_source_aqiin_api_key,
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
                titleId = R.string.settings_weather_source_aqiin_station_filter,
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
        private const val AQI_IN_API_BASE_URL = "https://apiserver.aqi.in/"
        private const val AQI_IN_WEB_URL = "https://www.aqi.in/"
        private const val KEY_CACHED_TOKEN = "cached_token"
        private const val KEY_CACHED_TOKEN_EXP_MS = "cached_token_exp_ms"
        private const val TOKEN_REFRESH_BUFFER_MS = 5L * 60 * 1000
        private const val DEFAULT_TOKEN_TTL_MS = 6L * 60 * 60 * 1000
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        private val BEARER_PREFIX_REGEX = Regex("""^(?i)bearer\s+""")
        private val GOVERNMENT_STATION_REGEX = Regex(
            """\b(?:DPCC|CPCB|IMD|IITM|RSPCB|UPPCB|HSPCB|MPCB|GPCB|SPCB|PCB|EPA|GOV|GOVT)\b""",
            RegexOption.IGNORE_CASE
        )
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        internal fun formatBearerToken(rawToken: String): String {
            val trimmed = rawToken.trim()
            if (trimmed.isEmpty()) return ""
            val jwt = trimmed.replaceFirst(BEARER_PREFIX_REGEX, "").trim()
            return if (jwt.isEmpty()) "" else "bearer $jwt"
        }

        internal fun extractJwtToken(html: String): String? {
            return JWT_REGEX.find(html)?.value
        }

        internal fun extractJwtExpiryMillis(
            jwt: String,
            nowMs: Long = System.currentTimeMillis(),
        ): Long {
            return try {
                val parts = jwt.split('.')
                if (parts.size < 2) return nowMs + DEFAULT_TOKEN_TTL_MS
                val payloadJson = decodeBase64UrlToString(parts[1]) ?: return nowMs + DEFAULT_TOKEN_TTL_MS
                val expSeconds = jsonParser.parseToJsonElement(payloadJson)
                    .jsonObject["exp"]
                    ?.jsonPrimitive
                    ?.longOrNull
                if (expSeconds != null && expSeconds > 0L) {
                    expSeconds * 1000L
                } else {
                    nowMs + DEFAULT_TOKEN_TTL_MS
                }
            } catch (_: Exception) {
                nowMs + DEFAULT_TOKEN_TTL_MS
            }
        }

        private fun decodeBase64UrlToString(encoded: String): String? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val cleaned = encoded.trimEnd('=')
            val bytes = ArrayList<Byte>(cleaned.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (ch in cleaned) {
                val value = alphabet.indexOf(ch)
                if (value == -1) return null
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    bytes.add(((buffer shr bits) and 0xFF).toByte())
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }

        internal fun isGovernmentStation(station: AqiInStationData): Boolean {
            val combined = listOfNotNull(station.station, station.locationSlug).joinToString(" ")
            return GOVERNMENT_STATION_REGEX.containsMatchIn(combined)
        }

        internal fun selectBestStation(
            stations: List<AqiInStationData>,
            stationFilter: StationFilterPreference,
        ): AqiInStationData? {
            val validStations = stations.filter { station ->
                convertIaqiToAirQuality(station.iaqi)?.isValid == true ||
                    !station.locationSlug.isNullOrBlank()
            }
            if (validStations.isEmpty()) return null

            // Prefer stations that already carry valid concentrations before falling back to slug-only entries
            val withIaqi = validStations.filter { convertIaqiToAirQuality(it.iaqi)?.isValid == true }
            val activePool = withIaqi.ifEmpty { validStations }

            val filteredPool = if (stationFilter == StationFilterPreference.COMMUNITY) {
                activePool.filter { !isGovernmentStation(it) }.ifEmpty { activePool }
            } else {
                activePool
            }

            return filteredPool.minByOrNull { it.distance ?: Double.MAX_VALUE }
        }

        internal fun convertIaqiToAirQuality(iaqi: AqiInIaqi?): AirQuality? {
            if (iaqi == null) return null
            val airQuality = AirQuality(
                pM25 = validConcentration(iaqi.pm25),
                pM10 = validConcentration(iaqi.pm10),
                sO2 = validConcentration(iaqi.so2),
                nO2 = validConcentration(iaqi.no2),
                o3 = validConcentration(iaqi.o3),
                cO = validConcentration(iaqi.co)
            )
            return airQuality.takeIf { it.isValid }
        }

        private fun validConcentration(value: Double?) =
            value?.takeIf { it.isFinite() && it >= 0.0 }?.microgramsPerCubicMeter

        internal fun parseSlugStations(data: JsonElement?): List<AqiInStationData> {
            return when (data) {
                is JsonArray -> data.mapNotNull { element ->
                    runCatching { jsonParser.decodeFromJsonElement<AqiInStationData>(element) }.getOrNull()
                }
                is JsonObject -> listOfNotNull(
                    runCatching { jsonParser.decodeFromJsonElement<AqiInStationData>(data) }.getOrNull()
                )
                else -> emptyList()
            }
        }
    }
}
