/**
 * Stage 8A — Safe Site Intelligence API Service
 *
 * Connects directly to backend Stage 5 Safe Site endpoints:
 * - GET /api/v1/safe-sites/district/{district}
 * - GET /api/v1/safe-sites/candidates
 * - GET /api/v1/safe-sites/search
 */
import { apiClient } from "../apiClient.js";
import { API_CONFIG } from "../config.js";

export const safeSiteService = {
    /**
     * Fetches safe sites for a specific district.
     */
    async getSitesByDistrict(district = "Sitamarhi") {
        return apiClient.get(API_CONFIG.ENDPOINTS.SAFE_SITES.DISTRICT(district));
    },

    /**
     * Fetches candidate safe sites within radius from a coordinate.
     */
    async getCandidateSites(lat, lon, maxDistanceKm = 25.0, minSuitability = "MARGINAL") {
        const params = new URLSearchParams({
            latitude: lat.toString(),
            longitude: lon.toString(),
            radiusKm: maxDistanceKm.toString(),
            minSuitability
        });
        return apiClient.get(`${API_CONFIG.ENDPOINTS.SAFE_SITES.CANDIDATES}?${params.toString()}`);
    },

    /**
     * Fetches RFC 7946 GeoJSON FeatureCollection of candidate safe sites for GIS mapping.
     */
    async getSafeSitesGeoJson(district = "Sitamarhi", category = null, suitabilityClass = null) {
        const params = new URLSearchParams();
        if (district) params.append("district", district);
        if (category) params.append("category", category);
        if (suitabilityClass) params.append("suitabilityClass", suitabilityClass);

        const url = params.toString()
            ? `${API_CONFIG.ENDPOINTS.SAFE_SITES.GEOJSON}?${params.toString()}`
            : API_CONFIG.ENDPOINTS.SAFE_SITES.GEOJSON;

        return apiClient.get(url);
    }
};
