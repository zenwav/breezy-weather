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

import io.reactivex.rxjava3.core.Observable
import org.breezyweather.sources.openaq.json.OpenAqLatestResult
import org.breezyweather.sources.openaq.json.OpenAqLocationsResult
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenAqApi {

    @GET("v3/locations")
    fun getLocations(
        @Header("X-API-Key") apiKey: String,
        @Query("coordinates") coordinates: String,
        @Query("radius") radius: Int = 25000,
        @Query("limit") limit: Int = 100,
    ): Observable<OpenAqLocationsResult>

    @GET("v3/locations/{locationId}/latest")
    fun getLocationLatest(
        @Header("X-API-Key") apiKey: String,
        @Path("locationId") locationId: Int,
    ): Observable<OpenAqLatestResult>
}
