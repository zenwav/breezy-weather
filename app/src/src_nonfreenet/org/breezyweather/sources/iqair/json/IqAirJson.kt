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

package org.breezyweather.sources.iqair.json

import kotlinx.serialization.Serializable

@Serializable
data class IqAirResult(
    val status: String? = null,
    val data: IqAirData? = null,
)

@Serializable
data class IqAirData(
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val current: IqAirCurrent? = null,
    val message: String? = null,
)

@Serializable
data class IqAirCurrent(
    val pollution: IqAirPollution? = null,
)

@Serializable
data class IqAirPollution(
    val ts: String? = null,
    val aqius: Int? = null,
    val mainus: String? = null,
    val aqicn: Int? = null,
    val maincn: String? = null,
)
