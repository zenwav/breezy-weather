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

import io.reactivex.rxjava3.core.Observable
import org.breezyweather.sources.aqiin.json.AqiInNearestResult
import org.breezyweather.sources.aqiin.json.AqiInSlugResult
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface AqiInApi {

    @GET("aqi/v2/getNearestLocation")
    fun getNearestLocation(
        @Header("authorization") authorization: String,
        @Header("Referer") referer: String = "https://www.aqi.in/",
        @Query("lat") lat: Double,
        @Query("long") lon: Double,
        @Query("type") type: Int = 1,
        @Query("source") source: String = "web",
    ): Observable<AqiInNearestResult>

    @GET("aqi/v2/getLocationDetailsBySlug")
    fun getLocationDetailsBySlug(
        @Header("authorization") authorization: String,
        @Header("Referer") referer: String = "https://www.aqi.in/",
        @Query("slug") slug: String,
        @Query("type") type: Int,
        @Query("source") source: String = "web",
    ): Observable<AqiInSlugResult>
}
