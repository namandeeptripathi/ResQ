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

const hasNumericValue = (value) =>
    value !== null && value !== undefined && value !== "" && Number.isFinite(Number(value));

export function normalizeSafeSite(site) {
    if (!site) return site;

    const totalCapacity = hasNumericValue(site.totalCapacity)
        ? Number(site.totalCapacity)
        : hasNumericValue(site.capacity) ? Number(site.capacity) : null;

    let allocatedCapacity = hasNumericValue(site.allocatedCapacity)
        ? Number(site.allocatedCapacity)
        : hasNumericValue(site.allocatedOccupancy) ? Number(site.allocatedOccupancy) : null;

    let availableCapacity = hasNumericValue(site.availableCapacity)
        ? Number(site.availableCapacity)
        : null;

    if (availableCapacity === null && totalCapacity !== null && allocatedCapacity !== null) {
        availableCapacity = Math.max(0, totalCapacity - allocatedCapacity);
    }
    if (allocatedCapacity === null && totalCapacity !== null && availableCapacity !== null) {
        allocatedCapacity = Math.max(0, totalCapacity - availableCapacity);
    }

    return {
        ...site,
        name: site.name || site.siteName || site.assetName || site.siteId || "Unnamed site",
        totalCapacity,
        allocatedCapacity,
        availableCapacity
    };
}

function normalizeSafeSiteResponse(response) {
    if (!response?.success || response.data == null) return response;

    if (Array.isArray(response.data)) {
        return { ...response, data: response.data.map(normalizeSafeSite) };
    }
    if (Array.isArray(response.data.sites)) {
        return {
            ...response,
            data: {
                ...response.data,
                sites: response.data.sites.map(normalizeSafeSite)
            }
        };
    }
    return response;
}

export const safeSiteService = {
    /**
     * Fetches safe sites for a specific district.
     */
    async getSitesByDistrict(district = "Sitamarhi") {
        const response = await apiClient.get(API_CONFIG.ENDPOINTS.SAFE_SITES.DISTRICT(district));
        return normalizeSafeSiteResponse(response);
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
        const response = await apiClient.get(`${API_CONFIG.ENDPOINTS.SAFE_SITES.CANDIDATES}?${params.toString()}`);
        return normalizeSafeSiteResponse(response);
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
