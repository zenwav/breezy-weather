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

package org.breezyweather.sources.openaq.json

import kotlinx.serialization.Serializable

@Serializable
data class OpenAqLocationsResult(
    val results: List<OpenAqLocation>? = null,
)

@Serializable
data class OpenAqLocation(
    val id: Int,
    val name: String? = null,
    val isMonitor: Boolean? = null,
    val provider: OpenAqProvider? = null,
    val coordinates: OpenAqCoordinates? = null,
    val sensors: List<OpenAqSensor>? = null,
    val datetimeLast: OpenAqDatetime? = null,
    val distance: Double? = null,
)

@Serializable
data class OpenAqProvider(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class OpenAqCoordinates(
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class OpenAqDatetime(
    val utc: String? = null,
    val local: String? = null,
)

@Serializable
data class OpenAqParameter(
    val id: Int? = null,
    val name: String? = null,
    val units: String? = null,
    val displayName: String? = null,
)

@Serializable
data class OpenAqSensorLatest(
    val datetime: OpenAqDatetime? = null,
    val value: Double? = null,
)

@Serializable
data class OpenAqSensor(
    val id: Int,
    val name: String? = null,
    val parameter: OpenAqParameter? = null,
    val latest: OpenAqSensorLatest? = null,
)

@Serializable
data class OpenAqLatestResult(
    val results: List<OpenAqLatestItem>? = null,
)

@Serializable
data class OpenAqLatestItem(
    val datetime: OpenAqDatetime? = null,
    val value: Double? = null,
    val coordinates: OpenAqCoordinates? = null,
    val sensorsId: Int? = null,
    val locationsId: Int? = null,
)
