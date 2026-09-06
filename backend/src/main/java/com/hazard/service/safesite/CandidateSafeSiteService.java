package com.hazard.service.safesite;

import com.hazard.domain.boundaries.DistrictBoundary;
import com.hazard.domain.infrastructure.InfrastructureCategory;
import com.hazard.domain.risk.RiskTier;
import com.hazard.domain.risk.ZoneLevel;
import com.hazard.domain.safesite.*;
import com.hazard.domain.terrain.DemTile;
import com.hazard.dto.hazard.GeoJsonFeatureCollectionDto;
import com.hazard.dto.hazard.GeoJsonFeatureDto;
import com.hazard.dto.hazard.GeoJsonGeometryDto;
import com.hazard.dto.infrastructure.InfrastructureAssetDto;
import com.hazard.dto.risk.DistrictRiskScoreDto;
import com.hazard.dto.risk.RedZoneDto;
import com.hazard.dto.safesite.CandidateSafeSiteDto;
import com.hazard.exception.HazardNotFoundException;
import com.hazard.exception.InvalidHazardParameterException;
import com.hazard.repository.boundaries.DistrictBoundaryRepository;
import com.hazard.repository.boundaries.DistrictDistanceProjection;
import com.hazard.service.exposure.InfrastructureDataProvider;
import com.hazard.service.exposure.SettlementExposureService;
import com.hazard.service.risk.RedZoneService;
import com.hazard.service.risk.RiskCalculationService;
import com.hazard.service.terrain.TerrainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Consolidated Core Service for Stages 5.2 - 5.11:
 * - Candidate Safe-Site Discovery
 * - 7-Dimension Spatial Intelligence Evaluation (Hazard Safety, Terrain, Distance, Roads, Healthcare, Water, Infrastructure)
 * - Multi-Criteria Suitability Intelligence & Hard AT_RISK Safety Gating
 * - Deterministic Hierarchical Ranking & Explainability
 * - Multi-Filter Querying & RFC 7946 GeoJSON Generation
 */
@Service
public class CandidateSafeSiteService {

    private static final Logger log = LoggerFactory.getLogger(CandidateSafeSiteService.class);

    private final InfrastructureDataProvider dataProvider;
    private final RedZoneService redZoneService;
    private final DistrictBoundaryRepository districtBoundaryRepository;
    private final TerrainService terrainService;
    private final RiskCalculationService riskCalculationService;
    private final SafeSiteThresholds thresholds;

    // Cache for district risk lookups (TTL: 5m)
    private final Map<String, DistrictRiskScoreDto> districtScoreCache = new ConcurrentHashMap<>();
    private volatile long lastCacheClear = System.currentTimeMillis();
    private static final long CACHE_TTL_MS = 300_000L;

    // Cache for evaluated candidate safe sites (TTL: 5m)
    private final List<CandidateSafeSiteDto> evaluatedSitesCache = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long lastSitesCacheClear = 0L;
    private static final long SITES_CACHE_TTL_MS = 300_000L;

    // Cache for active high-risk districts (TTL: 5m)
    private final List<String> cachedHighRiskDistricts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long lastHighRiskCacheClear = 0L;
    private static final long HIGH_RISK_CACHE_TTL_MS = 300_000L;

    /**
     * Deterministic hierarchical comparator for candidate safe-site ranking:
     * 1. SuitabilityClass tier (HIGHLY_SUITABLE -> SUITABLE -> MARGINAL -> UNSUITABLE -> UNKNOWN)
     * 2. SuitabilityScore DESC (nulls last)
     * 3. DataCompletenessPercentage DESC (nulls last)
     * 4. SiteId ASC (lexicographical deterministic tie-breaker)
     */
    public static final Comparator<CandidateSafeSiteDto> RANKING_COMPARATOR = Comparator
            .comparing(CandidateSafeSiteDto::getSuitabilityClass, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CandidateSafeSiteDto::getSuitabilityScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CandidateSafeSiteDto::getDataCompletenessPercentage, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CandidateSafeSiteDto::getSiteId, Comparator.nullsLast(Comparator.naturalOrder()));

    public CandidateSafeSiteService(InfrastructureDataProvider dataProvider,
                                    RedZoneService redZoneService,
                                    DistrictBoundaryRepository districtBoundaryRepository,
                                    TerrainService terrainService,
                                    RiskCalculationService riskCalculationService,
                                    SafeSiteThresholds thresholds) {
        this.dataProvider = dataProvider;
        this.redZoneService = redZoneService;
        this.districtBoundaryRepository = districtBoundaryRepository;
        this.terrainService = terrainService;
        this.riskCalculationService = riskCalculationService;
        this.thresholds = thresholds != null ? thresholds : new SafeSiteThresholds();
    }

    public SafeSiteThresholds getThresholds() {
        return thresholds;
    }

    // =========================================================================
    // 1. CANDIDATE DISCOVERY & EVALUATION PIPELINE
    // =========================================================================

    public List<CandidateSafeSiteDto> getAllCandidateSites() {
        long now = System.currentTimeMillis();
        if (now - lastSitesCacheClear <= SITES_CACHE_TTL_MS && !evaluatedSitesCache.isEmpty()) {
            return new ArrayList<>(evaluatedSitesCache);
        }

        List<InfrastructureAssetDto> allFacilities = dataProvider != null
                ? dataProvider.getAllRegionalFacilities()
                : Collections.emptyList();

        if (allFacilities == null || allFacilities.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> activeHighRiskDistricts = resolveActiveHighRiskDistricts();
        List<CandidateSafeSiteDto> ranked = evaluateFacilities(allFacilities, activeHighRiskDistricts);
        evaluatedSitesCache.clear();
        evaluatedSitesCache.addAll(ranked);
        lastSitesCacheClear = now;
        return new ArrayList<>(ranked);
    }

    public List<CandidateSafeSiteDto> evaluateFacilities(List<InfrastructureAssetDto> facilitiesToEvaluate) {
        return evaluateFacilities(facilitiesToEvaluate, null);
    }

    public List<CandidateSafeSiteDto> evaluateFacilities(List<InfrastructureAssetDto> facilitiesToEvaluate, List<String> highRiskDistricts) {
        if (facilitiesToEvaluate == null || facilitiesToEvaluate.isEmpty()) {
            return Collections.emptyList();
        }

        List<InfrastructureAssetDto> allFacilities = dataProvider != null
                ? dataProvider.getAllRegionalFacilities()
                : Collections.emptyList();

        List<String> activeHighRiskDistricts = highRiskDistricts != null
                ? highRiskDistricts
                : resolveActiveHighRiskDistricts();

        List<InfrastructureAssetDto> healthcareFacilities = allFacilities.stream()
                .filter(f -> f.getCategory() == InfrastructureCategory.HEALTHCARE)
                .collect(Collectors.toList());
        List<InfrastructureAssetDto> usefulWaterFacilities = allFacilities.stream()
                .filter(CandidateSafeSiteService::isUsefulWaterSupplyFacility)
                .collect(Collectors.toList());
        List<InfrastructureAssetDto> usefulSupportingFacilities = allFacilities.stream()
                .filter(CandidateSafeSiteService::isUsefulSupportingInfrastructure)
                .collect(Collectors.toList());

        List<CandidateSafeSiteDto> evaluated = facilitiesToEvaluate.stream()
                .map(CandidateSafeSiteDto::fromInfrastructureAsset)
                .filter(Objects::nonNull)
                .peek(site -> {
                    evaluateHazardSafety(site);
                    evaluateTerrain(site);
                    evaluateDistance(site, activeHighRiskDistricts);
                    evaluateRoadAccessibility(site);
                    evaluateHealthcareAccess(site, healthcareFacilities);
                    evaluateWaterAccess(site, usefulWaterFacilities);
                    evaluateInfrastructureAccess(site, usefulSupportingFacilities);
                    evaluateSuitability(site);
                })
                .collect(Collectors.toList());

        return rankCandidateSites(evaluated);
    }

    // =========================================================================
    // 2. DIMENSION 1: HAZARD SAFETY EVALUATION
    // =========================================================================

    public void evaluateHazardSafety(CandidateSafeSiteDto site) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setHazardSafetyStatus(HazardSafetyStatus.UNKNOWN);
            site.setHazardSafetyReason("Missing or invalid geographic coordinates; spatial hazard exposure cannot be evaluated.");
            site.setRiskZone("UNKNOWN");
            site.setRiskScore(null);
            return;
        }

        Optional<DistrictBoundary> boundaryOpt = Optional.empty();
        if (districtBoundaryRepository != null) {
            try {
                boundaryOpt = districtBoundaryRepository.findDistrictContainingPoint(lon, lat);
            } catch (Exception e) {
                log.warn("Spatial point-in-polygon lookup failed for site {}: {}", site.getSiteId(), e.getMessage());
            }
        }

        String targetDistrict = boundaryOpt.map(DistrictBoundary::getName2)
                .orElse(site.getDistrict() != null && !site.getDistrict().trim().isEmpty() ? site.getDistrict().trim() : null);

        if (targetDistrict == null || targetDistrict.trim().isEmpty()) {
            site.setHazardSafetyStatus(HazardSafetyStatus.UNKNOWN);
            site.setHazardSafetyReason("Candidate coordinates (" + lon + ", " + lat + ") fall outside mapped administrative district boundaries; spatial hazard exposure undetermined.");
            site.setRiskZone("UNKNOWN");
            site.setRiskScore(null);
            return;
        }

        final String finalDistrictName = targetDistrict;
        final String lookupKey = targetDistrict.trim().toUpperCase();
        long now = System.currentTimeMillis();
        if (now - lastCacheClear > CACHE_TTL_MS) {
            districtScoreCache.clear();
            lastCacheClear = now;
        }

        DistrictRiskScoreDto riskScoreDto = districtScoreCache.computeIfAbsent(lookupKey, k -> {
            try {
                return riskCalculationService != null ? riskCalculationService.getDistrictRiskScore(finalDistrictName, null) : null;
            } catch (Exception e) {
                log.warn("Failed to retrieve disaster risk profile for district {}: {}", finalDistrictName, e.getMessage());
                return null;
            }
        });

        if (riskScoreDto == null || riskScoreDto.getRiskScore() == null) {
            site.setHazardSafetyStatus(HazardSafetyStatus.UNKNOWN);
            site.setHazardSafetyReason("Spatial disaster risk assessment data is unavailable for area: " + targetDistrict + ".");
            site.setRiskZone("UNKNOWN");
            site.setRiskScore(null);
            return;
        }

        RiskTier riskTier = riskScoreDto.getRiskTier();
        ZoneLevel zoneLevel = ZoneLevel.fromRiskTier(riskTier);
        Double score100 = riskScoreDto.getRiskScore100();
        if (score100 == null && riskScoreDto.getRiskScore() != null) {
            score100 = Math.round(riskScoreDto.getRiskScore() * 1000.0) / 10.0;
        } else if (score100 != null) {
            score100 = Math.round(score100 * 10.0) / 10.0;
        }

        site.setRiskZone(zoneLevel != null ? zoneLevel.name() : (riskTier != null ? riskTier.name() : "UNKNOWN"));
        site.setRiskScore(score100);

        if (zoneLevel == null || zoneLevel == ZoneLevel.UNKNOWN) {
            site.setHazardSafetyStatus(HazardSafetyStatus.UNKNOWN);
            site.setHazardSafetyReason("Spatial risk evaluation returned unclassified hazard risk level for area: " + targetDistrict + ".");
            site.setRiskScore(null);
            return;
        }

        if (zoneLevel == ZoneLevel.CRITICAL) {
            site.setHazardSafetyStatus(HazardSafetyStatus.AT_RISK);
            site.setHazardSafetyReason("Candidate location falls within a Critical Red Zone (" + targetDistrict +
                    ", Risk Score: " + score100 + "/100, Tier: " + (riskTier != null ? riskTier.name() : "CRITICAL") +
                    ") with severe disaster risk.");
        } else if (zoneLevel == ZoneLevel.HIGH) {
            site.setHazardSafetyStatus(HazardSafetyStatus.AT_RISK);
            site.setHazardSafetyReason("Candidate location falls within a High Risk Zone (" + targetDistrict +
                    ", Risk Score: " + score100 + "/100, Tier: " + (riskTier != null ? riskTier.name() : "HIGH") +
                    ") with elevated disaster exposure.");
        } else if (zoneLevel == ZoneLevel.MODERATE) {
            site.setHazardSafetyStatus(HazardSafetyStatus.SAFE);
            site.setHazardSafetyReason("Candidate location is within a Moderate Risk area (" + targetDistrict +
                    ", Risk Score: " + score100 + "/100, Tier: " + (riskTier != null ? riskTier.name() : "MODERATE") +
                    "), outside high-risk/red zones.");
        } else {
            site.setHazardSafetyStatus(HazardSafetyStatus.SAFE);
            site.setHazardSafetyReason("Candidate location is within a Low Risk area (" + targetDistrict +
                    ", Risk Score: " + score100 + "/100, Tier: " + (riskTier != null ? riskTier.name() : "LOW") +
                    "), outside high-risk/red zones.");
        }
    }

    // =========================================================================
    // 3. DIMENSION 2: TERRAIN / SLOPE EVALUATION
    // =========================================================================

    public void evaluateTerrain(CandidateSafeSiteDto site) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setTerrainStatus(TerrainStatus.UNKNOWN);
            site.setTerrainReason("Missing or invalid geographic coordinates; terrain evaluation cannot be performed.");
            return;
        }

        if (site.getSlopeDegrees() != null) {
            double slope = site.getSlopeDegrees();
            if (slope <= thresholds.getMaxFavorableSlopeDegrees()) {
                site.setTerrainStatus(TerrainStatus.FAVORABLE);
                site.setTerrainReason(String.format(
                        "Site terrain slope is favorable (%.1f° <= %.1f° threshold) for emergency shelter operations.",
                        slope, thresholds.getMaxFavorableSlopeDegrees()));
            } else if (slope >= thresholds.getMinUnfavorableSlopeDegrees()) {
                site.setTerrainStatus(TerrainStatus.UNFAVORABLE);
                site.setTerrainReason(String.format(
                        "Site terrain slope is unfavorable (%.1f° >= %.1f° threshold); steep terrain presents stability or accessibility risks.",
                        slope, thresholds.getMinUnfavorableSlopeDegrees()));
            } else {
                site.setTerrainStatus(TerrainStatus.UNKNOWN);
                site.setTerrainReason(String.format(
                        "Site terrain slope is intermediate (%.2f°), falling between configured favorable (%.1f°) and unfavorable (%.1f°) thresholds; terrain suitability is indeterminate.",
                        slope, thresholds.getMaxFavorableSlopeDegrees(), thresholds.getMinUnfavorableSlopeDegrees()));
            }
            return;
        }

        if (site.getElevationMeters() != null) {
            site.setSlopeDegrees(null);
            site.setTerrainStatus(TerrainStatus.UNKNOWN);
            site.setTerrainReason(String.format(
                    "Site elevation is %.1f meters, but site-level slope data is not available to determine terrain feasibility.",
                    site.getElevationMeters()));
            return;
        }

        Optional<DemTile> tileOpt = Optional.empty();
        if (terrainService != null) {
            try {
                tileOpt = terrainService.getDemTileForCoordinate(lon, lat);
            } catch (Exception e) {
                log.warn("DEM tile lookup failed for site {} at ({}, {}): {}", site.getSiteId(), lon, lat, e.getMessage());
            }
        }

        site.setElevationMeters(null);
        site.setSlopeDegrees(null);
        site.setTerrainStatus(TerrainStatus.UNKNOWN);

        if (tileOpt.isPresent()) {
            DemTile tile = tileOpt.get();
            site.setTerrainReason(String.format(
                    "DEM tile footprint '%s' covers location (tile bounds: %.1fm - %.1fm elevation, %.1fm resolution), but point-level elevation/slope raster sampling is not currently ingested.",
                    tile.getTileName(),
                    tile.getMinElevationM() != null ? tile.getMinElevationM() : 0.0,
                    tile.getMaxElevationM() != null ? tile.getMaxElevationM() : 0.0,
                    tile.getResolutionMeters() != null ? tile.getResolutionMeters() : 30.0));
        } else {
            site.setTerrainReason("Terrain elevation and slope data are not currently available for this location.");
        }
    }

    // =========================================================================
    // 4. DIMENSION 3: HIGH-RISK ZONE DISTANCE EVALUATION
    // =========================================================================

    public void evaluateDistance(CandidateSafeSiteDto site) {
        evaluateDistance(site, (List<String>) null);
    }

    public void evaluateDistance(CandidateSafeSiteDto site, List<String> explicitTargetDistricts) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setDistanceMeters(null);
            site.setDistanceKilometers(null);
            site.setDistanceStatus(DistanceStatus.UNKNOWN);
            site.setDistanceReason("Missing or invalid geographic coordinates; distance evaluation cannot be performed.");
            return;
        }

        if (site.getDistanceMeters() != null) {
            applyDistanceClassification(site, site.getDistanceMeters(), "the relevant high-risk area");
            return;
        }

        List<String> targetDistricts = explicitTargetDistricts;
        if (targetDistricts == null || targetDistricts.isEmpty()) {
            targetDistricts = resolveActiveHighRiskDistricts();
        }

        List<String> upperTargetDistricts = targetDistricts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toList());

        if (upperTargetDistricts.isEmpty()) {
            site.setDistanceMeters(null);
            site.setDistanceKilometers(null);
            site.setDistanceStatus(DistanceStatus.UNKNOWN);
            site.setDistanceReason("No active high-risk or red-zone disaster areas identified to measure proximity.");
            return;
        }

        if (districtBoundaryRepository != null) {
            try {
                Optional<DistrictDistanceProjection> projectionOpt = districtBoundaryRepository
                        .findNearestDistrictDistance(lon, lat, upperTargetDistricts);

                if (projectionOpt.isPresent() && projectionOpt.get().getDistanceMeters() != null) {
                    DistrictDistanceProjection proj = projectionOpt.get();
                    applyDistanceClassification(site, proj.getDistanceMeters(), proj.getDistrictName());
                    return;
                }
            } catch (Exception e) {
                log.warn("PostGIS distance calculation failed for site {} at ({}, {}): {}",
                        site.getSiteId(), lon, lat, e.getMessage());
            }
        }

        if (site.getDistrict() != null && upperTargetDistricts.contains(site.getDistrict().trim().toUpperCase())) {
            applyDistanceClassification(site, 0.0, site.getDistrict().trim());
            return;
        }

        site.setDistanceMeters(null);
        site.setDistanceKilometers(null);
        site.setDistanceStatus(DistanceStatus.UNKNOWN);
        site.setDistanceReason("Distance calculation could not be completed for the candidate location.");
    }

    public List<String> resolveActiveHighRiskDistricts() {
        long now = System.currentTimeMillis();
        if (lastHighRiskCacheClear > 0 && now - lastHighRiskCacheClear <= HIGH_RISK_CACHE_TTL_MS) {
            return new ArrayList<>(cachedHighRiskDistricts);
        }

        if (redZoneService == null) return Collections.emptyList();

        List<RedZoneDto> redZonesList = null;
        try {
            redZonesList = redZoneService.getRedZonesOnly();
        } catch (Exception e) {
            log.warn("Failed to retrieve red zones: {}", e.getMessage());
        }

        List<String> result = new ArrayList<>();
        if (redZonesList != null && !redZonesList.isEmpty()) {
            result = redZonesList.stream()
                    .map(RedZoneDto::getDistrictName)
                    .filter(name -> name != null && !name.trim().isEmpty())
                    .collect(Collectors.toList());
        } else {
            List<RedZoneDto> highRiskList = null;
            try {
                highRiskList = redZoneService.getZonesByMinimumLevel(ZoneLevel.HIGH);
            } catch (Exception e) {
                log.warn("Failed to retrieve high risk zones: {}", e.getMessage());
            }

            if (highRiskList != null && !highRiskList.isEmpty()) {
                result = highRiskList.stream()
                        .map(RedZoneDto::getDistrictName)
                        .filter(name -> name != null && !name.trim().isEmpty())
                        .collect(Collectors.toList());
            }
        }

        cachedHighRiskDistricts.clear();
        cachedHighRiskDistricts.addAll(result);
        lastHighRiskCacheClear = now;
        return result;
    }

    public void applyDistanceClassification(CandidateSafeSiteDto site, double distanceMeters, String referenceAreaName) {
        double roundedMeters = Math.round(distanceMeters * 10.0) / 10.0;
        double distanceKm = Math.round((distanceMeters / 1000.0) * 100.0) / 100.0;

        site.setDistanceMeters(roundedMeters);
        site.setDistanceKilometers(distanceKm);

        if (roundedMeters <= 0.0) {
            site.setDistanceStatus(DistanceStatus.NEAR);
            site.setDistanceReason(String.format(
                    "Candidate site is located inside the high-risk disaster area (%s). Distance: 0.00 km.",
                    referenceAreaName));
        } else if (distanceKm <= thresholds.getNearDistanceKm()) {
            site.setDistanceStatus(DistanceStatus.NEAR);
            site.setDistanceReason(String.format(
                    "Candidate site is near the high-risk disaster area (%s), approximately %.2f km (<= %.1f km threshold).",
                    referenceAreaName, distanceKm, thresholds.getNearDistanceKm()));
        } else if (distanceKm >= thresholds.getFarDistanceKm()) {
            site.setDistanceStatus(DistanceStatus.FAR);
            site.setDistanceReason(String.format(
                    "Candidate site is far from the high-risk disaster area (%s), approximately %.2f km (>= %.1f km threshold).",
                    referenceAreaName, distanceKm, thresholds.getFarDistanceKm()));
        } else {
            site.setDistanceStatus(DistanceStatus.MODERATE);
            site.setDistanceReason(String.format(
                    "Candidate site is at a moderate distance from the high-risk disaster area (%s), approximately %.2f km.",
                    referenceAreaName, distanceKm));
        }
    }

    // =========================================================================
    // 5. DIMENSION 4: ROAD ACCESSIBILITY EVALUATION
    // =========================================================================

    public void evaluateRoadAccessibility(CandidateSafeSiteDto site) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setRoadDistanceMeters(null);
            site.setRoadDistanceKilometers(null);
            site.setRoadAccessStatus(RoadAccessStatus.UNKNOWN);
            site.setRoadAccessReason("Missing or invalid geographic coordinates; road accessibility cannot be evaluated.");
            return;
        }

        if (site.getRoadDistanceMeters() != null) {
            applyRoadDistanceClassification(site, site.getRoadDistanceMeters());
            return;
        }

        site.setRoadDistanceMeters(null);
        site.setRoadDistanceKilometers(null);
        site.setRoadAccessStatus(RoadAccessStatus.UNKNOWN);
        site.setRoadAccessReason("Road-network data is not currently available in the project dataset; road accessibility is integrated structurally but returns UNKNOWN.");
    }

    public void evaluateRoadAccessibility(CandidateSafeSiteDto site, Double explicitDistanceMeters) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setRoadDistanceMeters(null);
            site.setRoadDistanceKilometers(null);
            site.setRoadAccessStatus(RoadAccessStatus.UNKNOWN);
            site.setRoadAccessReason("Missing or invalid geographic coordinates; road accessibility cannot be evaluated.");
            return;
        }

        if (explicitDistanceMeters != null) {
            applyRoadDistanceClassification(site, explicitDistanceMeters);
        } else {
            evaluateRoadAccessibility(site);
        }
    }

    public void applyRoadDistanceClassification(CandidateSafeSiteDto site, double distanceMeters) {
        double roundedMeters = Math.round(distanceMeters * 10.0) / 10.0;
        double distanceKm = Math.round((distanceMeters / 1000.0) * 100.0) / 100.0;

        site.setRoadDistanceMeters(roundedMeters);
        site.setRoadDistanceKilometers(distanceKm);

        double nearLimit = thresholds.getNearRoadDistanceMeters();
        double farLimit = thresholds.getFarRoadDistanceMeters();

        if (roundedMeters <= nearLimit) {
            site.setRoadAccessStatus(RoadAccessStatus.NEAR);
            if (roundedMeters == 0.0) {
                site.setRoadAccessReason("Candidate site is located directly on or adjacent to an accessible road (0.0 m).");
            } else {
                site.setRoadAccessReason("Candidate site has close road access, approximately " + roundedMeters
                        + " m (" + String.format("%.2f", distanceKm) + " km <= " + String.format("%.1f", nearLimit / 1000.0) + " km threshold).");
            }
        } else if (roundedMeters >= farLimit) {
            site.setRoadAccessStatus(RoadAccessStatus.FAR);
            site.setRoadAccessReason("Candidate site is relatively distant from the nearest accessible road, approximately "
                    + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km >= " + String.format("%.1f", farLimit / 1000.0) + " km threshold).");
        } else {
            site.setRoadAccessStatus(RoadAccessStatus.MODERATE);
            site.setRoadAccessReason("Candidate site has moderate road proximity, approximately " + roundedMeters
                    + " m (" + String.format("%.2f", distanceKm) + " km), between " + (int) nearLimit + "m and " + (int) farLimit + "m.");
        }
    }

    // =========================================================================
    // 6. DIMENSIONS 5, 6, 7: FACILITY PROXIMITY EVALUATIONS (Healthcare, Water, Infra)
    // =========================================================================

    public void evaluateHealthcareAccess(CandidateSafeSiteDto site) {
        if (site == null) return;
        List<InfrastructureAssetDto> facilities = dataProvider != null
                ? dataProvider.getHealthcareFacilities()
                : Collections.emptyList();
        evaluateHealthcareAccess(site, facilities);
    }

    public void evaluateHealthcareAccess(CandidateSafeSiteDto site, List<InfrastructureAssetDto> healthcareFacilities) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setHealthcareDistanceMeters(null);
            site.setHealthcareDistanceKilometers(null);
            site.setHealthcareAccessStatus(HealthcareAccessStatus.UNKNOWN);
            site.setNearestHealthcareSiteId(null);
            site.setNearestHealthcareSiteName(null);
            site.setHealthcareReason("Missing or invalid geographic coordinates; healthcare accessibility cannot be evaluated.");
            return;
        }

        if (healthcareFacilities == null || healthcareFacilities.isEmpty()) {
            site.setHealthcareDistanceMeters(null);
            site.setHealthcareDistanceKilometers(null);
            site.setHealthcareAccessStatus(HealthcareAccessStatus.UNKNOWN);
            site.setNearestHealthcareSiteId(null);
            site.setNearestHealthcareSiteName(null);
            site.setHealthcareReason("No healthcare facilities found in the regional dataset; healthcare accessibility cannot be evaluated.");
            return;
        }

        InfrastructureAssetDto nearest = findNearestFacility(lat, lon, healthcareFacilities);
        if (nearest == null) {
            site.setHealthcareDistanceMeters(null);
            site.setHealthcareDistanceKilometers(null);
            site.setHealthcareAccessStatus(HealthcareAccessStatus.UNKNOWN);
            site.setNearestHealthcareSiteId(null);
            site.setNearestHealthcareSiteName(null);
            site.setHealthcareReason("No valid healthcare facilities with coordinates found.");
            return;
        }

        double minDistanceMeters = SettlementExposureService.haversineDistanceMeters(lat, lon, nearest.getLatitude(), nearest.getLongitude());
        double roundedMeters = Math.round(minDistanceMeters * 10.0) / 10.0;
        double distanceKm = Math.round((minDistanceMeters / 1000.0) * 100.0) / 100.0;

        site.setHealthcareDistanceMeters(roundedMeters);
        site.setHealthcareDistanceKilometers(distanceKm);
        site.setNearestHealthcareSiteId(nearest.getAssetId());
        site.setNearestHealthcareSiteName(nearest.getAssetName());

        double nearLimit = thresholds.getNearHealthcareDistanceMeters();
        double farLimit = thresholds.getFarHealthcareDistanceMeters();

        if (roundedMeters <= nearLimit) {
            site.setHealthcareAccessStatus(HealthcareAccessStatus.NEAR);
            if (roundedMeters == 0.0) {
                site.setHealthcareReason("Candidate site is itself a configured healthcare facility (" + nearest.getAssetName() + ") (0.0 m).");
            } else {
                site.setHealthcareReason("Candidate site has close healthcare proximity (" + nearest.getAssetName() + "), approximately "
                        + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km <= " + String.format("%.1f", nearLimit / 1000.0) + " km threshold).");
            }
        } else if (roundedMeters >= farLimit) {
            site.setHealthcareAccessStatus(HealthcareAccessStatus.FAR);
            site.setHealthcareReason("Candidate site is relatively distant from healthcare facilities, approximately "
                    + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km >= " + String.format("%.1f", farLimit / 1000.0)
                    + " km threshold) from nearest facility: " + nearest.getAssetName() + ".");
        } else {
            site.setHealthcareAccessStatus(HealthcareAccessStatus.MODERATE);
            site.setHealthcareReason("Candidate site has moderate healthcare proximity, approximately " + roundedMeters
                    + " m (" + String.format("%.2f", distanceKm) + " km) to " + nearest.getAssetName()
                    + ", between " + (int) (nearLimit / 1000.0) + "km and " + (int) (farLimit / 1000.0) + "km.");
        }
    }

    public void evaluateWaterAccess(CandidateSafeSiteDto site) {
        if (site == null) return;
        List<InfrastructureAssetDto> all = dataProvider != null ? dataProvider.getAllRegionalFacilities() : Collections.emptyList();
        List<InfrastructureAssetDto> useful = all.stream()
                .filter(CandidateSafeSiteService::isUsefulWaterSupplyFacility)
                .collect(Collectors.toList());
        evaluateWaterAccess(site, useful);
    }

    public void evaluateWaterAccess(CandidateSafeSiteDto site, List<InfrastructureAssetDto> usefulWaterFacilities) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setWaterDistanceMeters(null);
            site.setWaterDistanceKilometers(null);
            site.setWaterAccessStatus(WaterAccessStatus.UNKNOWN);
            site.setNearestWaterSiteId(null);
            site.setNearestWaterSiteName(null);
            site.setWaterReason("Missing or invalid geographic coordinates; water accessibility cannot be evaluated.");
            return;
        }

        if (usefulWaterFacilities == null || usefulWaterFacilities.isEmpty()) {
            site.setWaterDistanceMeters(null);
            site.setWaterDistanceKilometers(null);
            site.setWaterAccessStatus(WaterAccessStatus.UNKNOWN);
            site.setNearestWaterSiteId(null);
            site.setNearestWaterSiteName(null);
            site.setWaterReason("Useful emergency water supply data is not currently available in the project dataset; natural canals, waterways, and drainage features are excluded from potable safe-site water assessment.");
            return;
        }

        InfrastructureAssetDto nearest = findNearestFacility(lat, lon, usefulWaterFacilities);
        if (nearest == null) {
            site.setWaterDistanceMeters(null);
            site.setWaterDistanceKilometers(null);
            site.setWaterAccessStatus(WaterAccessStatus.UNKNOWN);
            site.setNearestWaterSiteId(null);
            site.setNearestWaterSiteName(null);
            site.setWaterReason("No valid water facilities with coordinates found; water accessibility cannot be evaluated.");
            return;
        }

        double minDistanceMeters = SettlementExposureService.haversineDistanceMeters(lat, lon, nearest.getLatitude(), nearest.getLongitude());
        double roundedMeters = Math.round(minDistanceMeters * 10.0) / 10.0;
        double distanceKm = Math.round((minDistanceMeters / 1000.0) * 100.0) / 100.0;

        site.setWaterDistanceMeters(roundedMeters);
        site.setWaterDistanceKilometers(distanceKm);
        site.setNearestWaterSiteId(nearest.getAssetId());
        site.setNearestWaterSiteName(nearest.getAssetName());

        double nearLimit = thresholds.getNearWaterDistanceMeters();
        double farLimit = thresholds.getFarWaterDistanceMeters();

        if (roundedMeters <= nearLimit) {
            site.setWaterAccessStatus(WaterAccessStatus.NEAR);
            if (roundedMeters == 0.0) {
                site.setWaterReason("Candidate site is located directly on or adjacent to a useful water facility ("
                        + nearest.getAssetName() + ") (0.0 m).");
            } else {
                site.setWaterReason("Candidate site has close access to emergency water facility ("
                        + nearest.getAssetName() + "), approximately " + roundedMeters
                        + " m (" + String.format("%.2f", distanceKm) + " km <= " + String.format("%.1f", nearLimit / 1000.0)
                        + " km threshold).");
            }
        } else if (roundedMeters >= farLimit) {
            site.setWaterAccessStatus(WaterAccessStatus.FAR);
            site.setWaterReason("Candidate site is relatively distant from useful emergency water facilities, approximately "
                    + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km >= " + String.format("%.1f", farLimit / 1000.0)
                    + " km threshold) from nearest facility: " + nearest.getAssetName() + ".");
        } else {
            site.setWaterAccessStatus(WaterAccessStatus.MODERATE);
            site.setWaterReason("Candidate site has moderate water facility proximity, approximately " + roundedMeters
                    + " m (" + String.format("%.2f", distanceKm) + " km) to " + nearest.getAssetName()
                    + ", between " + (int) (nearLimit / 1000.0) + "km and " + (int) (farLimit / 1000.0) + "km.");
        }
    }

    public void evaluateInfrastructureAccess(CandidateSafeSiteDto site) {
        if (site == null) return;
        List<InfrastructureAssetDto> all = dataProvider != null ? dataProvider.getAllRegionalFacilities() : Collections.emptyList();
        List<InfrastructureAssetDto> useful = all.stream()
                .filter(CandidateSafeSiteService::isUsefulSupportingInfrastructure)
                .collect(Collectors.toList());
        evaluateInfrastructureAccess(site, useful);
    }

    public void evaluateInfrastructureAccess(CandidateSafeSiteDto site, List<InfrastructureAssetDto> usefulSupportingFacilities) {
        if (site == null) return;

        Double lat = site.getLatitude();
        Double lon = site.getLongitude();

        if (lat == null || lon == null || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            site.setInfrastructureDistanceMeters(null);
            site.setInfrastructureDistanceKilometers(null);
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.UNKNOWN);
            site.setNearestInfrastructureSiteId(null);
            site.setNearestInfrastructureSiteName(null);
            site.setNearestInfrastructureCategory(null);
            site.setInfrastructureReason("Missing or invalid geographic coordinates; supporting infrastructure proximity cannot be evaluated.");
            return;
        }

        if (usefulSupportingFacilities == null || usefulSupportingFacilities.isEmpty()) {
            site.setInfrastructureDistanceMeters(null);
            site.setInfrastructureDistanceKilometers(null);
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.UNKNOWN);
            site.setNearestInfrastructureSiteId(null);
            site.setNearestInfrastructureSiteName(null);
            site.setNearestInfrastructureCategory(null);
            site.setInfrastructureReason("No useful supporting infrastructure facilities available in the dataset.");
            return;
        }

        InfrastructureAssetDto nearest = findNearestFacility(lat, lon, usefulSupportingFacilities);
        if (nearest == null) {
            site.setInfrastructureDistanceMeters(null);
            site.setInfrastructureDistanceKilometers(null);
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.UNKNOWN);
            site.setNearestInfrastructureSiteId(null);
            site.setNearestInfrastructureSiteName(null);
            site.setNearestInfrastructureCategory(null);
            site.setInfrastructureReason("No valid supporting infrastructure facilities with coordinates found.");
            return;
        }

        double minDistanceMeters = SettlementExposureService.haversineDistanceMeters(lat, lon, nearest.getLatitude(), nearest.getLongitude());
        double roundedMeters = Math.round(minDistanceMeters * 10.0) / 10.0;
        double distanceKm = Math.round((minDistanceMeters / 1000.0) * 100.0) / 100.0;
        String categoryName = nearest.getCategory() != null ? nearest.getCategory().name() : "UNKNOWN";

        site.setInfrastructureDistanceMeters(roundedMeters);
        site.setInfrastructureDistanceKilometers(distanceKm);
        site.setNearestInfrastructureSiteId(nearest.getAssetId());
        site.setNearestInfrastructureSiteName(nearest.getAssetName());
        site.setNearestInfrastructureCategory(categoryName);

        double nearLimit = thresholds.getNearInfrastructureDistanceMeters();
        double farLimit = thresholds.getFarInfrastructureDistanceMeters();

        if (roundedMeters <= nearLimit) {
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.NEAR);
            if (roundedMeters == 0.0) {
                site.setInfrastructureReason("Candidate site is itself a configured supporting facility ("
                        + nearest.getAssetName() + " [" + categoryName + "]) with direct on-site infrastructure access (0.0 m).");
            } else {
                site.setInfrastructureReason("Candidate site has close access to supporting infrastructure ("
                        + nearest.getAssetName() + " [" + categoryName + "]), approximately " + roundedMeters
                        + " m (" + String.format("%.2f", distanceKm) + " km <= " + String.format("%.1f", nearLimit / 1000.0)
                        + " km threshold).");
            }
        } else if (roundedMeters >= farLimit) {
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.FAR);
            site.setInfrastructureReason("Candidate site is relatively distant from useful supporting infrastructure, approximately "
                    + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km >= " + String.format("%.1f", farLimit / 1000.0)
                    + " km threshold) from nearest facility: " + nearest.getAssetName() + " [" + categoryName + "].");
        } else {
            site.setInfrastructureAccessStatus(InfrastructureAccessStatus.MODERATE);
            site.setInfrastructureReason("Candidate site has moderate supporting infrastructure proximity, approximately "
                    + roundedMeters + " m (" + String.format("%.2f", distanceKm) + " km) to " + nearest.getAssetName()
                    + " [" + categoryName + "], between " + (int) (nearLimit / 1000.0) + "km and " + (int) (farLimit / 1000.0) + "km.");
        }
    }

    private InfrastructureAssetDto findNearestFacility(double lat, double lon, List<InfrastructureAssetDto> facilities) {
        InfrastructureAssetDto nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (InfrastructureAssetDto f : facilities) {
            if (f.getLatitude() == null || f.getLongitude() == null) continue;
            double dist = SettlementExposureService.haversineDistanceMeters(lat, lon, f.getLatitude(), f.getLongitude());
            if (dist < minDistance) {
                minDistance = dist;
                nearest = f;
            }
        }
        return nearest;
    }

    public static boolean isUsefulWaterSupplyFacility(InfrastructureAssetDto f) {
        if (f == null || f.getCategory() != InfrastructureCategory.WATER) return false;
        String subType = f.getSubType() != null ? f.getSubType().trim().toLowerCase() : "";
        if (subType.contains("canal") || subType.contains("drain") || subType.contains("river") ||
            subType.contains("stream") || subType.contains("weir") || subType.contains("dam") ||
            subType.contains("ditch") || subType.contains("retention") || subType.contains("lock")) {
            return false;
        }
        return subType.contains("treatment") || subType.contains("supply") || subType.contains("potable") ||
               subType.contains("purification") || subType.contains("drinking") || subType.contains("depot") ||
               subType.contains("tower") || subType.contains("reservoir_potable");
    }

    public static boolean isUsefulSupportingInfrastructure(InfrastructureAssetDto f) {
        if (f == null || f.getCategory() == null) return false;
        InfrastructureCategory cat = f.getCategory();
        return cat == InfrastructureCategory.EDUCATION
                || cat == InfrastructureCategory.GOVERNMENT
                || cat == InfrastructureCategory.EMERGENCY_SERVICES
                || cat == InfrastructureCategory.HEALTHCARE
                || cat == InfrastructureCategory.COMMUNICATION;
    }

    // =========================================================================
    // 7. DIMENSION 8: MULTI-CRITERIA SUITABILITY EVALUATION & SAFETY GATING
    // =========================================================================

    public void evaluateSuitability(CandidateSafeSiteDto site) {
        if (site == null) return;

        Map<String, Object> factors = new LinkedHashMap<>();

        // 1. Hazard Safety (Weight: 30%)
        HazardSafetyStatus hazardStatus = site.getHazardSafetyStatus();
        boolean hazardKnown = hazardStatus != null && hazardStatus != HazardSafetyStatus.UNKNOWN;
        Double hazardScore = hazardKnown ? (hazardStatus == HazardSafetyStatus.SAFE ? thresholds.getOptimalScore() : thresholds.getPoorScore()) : null;
        factors.put("hazardSafety", buildFactorDetail(hazardStatus != null ? hazardStatus.name() : "UNKNOWN", hazardScore, thresholds.getHazardSafetyWeight(), hazardKnown));

        // 2. Terrain / Slope (Weight: 15%)
        TerrainStatus terrainStatus = site.getTerrainStatus();
        boolean terrainKnown = terrainStatus != null && terrainStatus != TerrainStatus.UNKNOWN;
        Double terrainScore = terrainKnown ? (terrainStatus == TerrainStatus.FAVORABLE ? thresholds.getOptimalScore() : thresholds.getPoorScore()) : null;
        factors.put("terrain", buildFactorDetail(terrainStatus != null ? terrainStatus.name() : "UNKNOWN", terrainScore, thresholds.getTerrainWeight(), terrainKnown));

        // 3. Geographic Distance (Weight: 15%)
        DistanceStatus distanceStatus = site.getDistanceStatus();
        boolean distanceKnown = distanceStatus != null && distanceStatus != DistanceStatus.UNKNOWN;
        Double distanceScore = distanceKnown ? scoreForStatus(distanceStatus != null ? distanceStatus.name() : null) : null;
        factors.put("distance", buildFactorDetail(distanceStatus != null ? distanceStatus.name() : "UNKNOWN", distanceScore, thresholds.getDistanceWeight(), distanceKnown));

        // 4. Road Accessibility (Weight: 10%)
        RoadAccessStatus roadStatus = site.getRoadAccessStatus();
        boolean roadKnown = roadStatus != null && roadStatus != RoadAccessStatus.UNKNOWN;
        Double roadScore = roadKnown ? scoreForStatus(roadStatus != null ? roadStatus.name() : null) : null;
        factors.put("roads", buildFactorDetail(roadStatus != null ? roadStatus.name() : "UNKNOWN", roadScore, thresholds.getRoadsWeight(), roadKnown));

        // 5. Healthcare Support (Weight: 10%)
        HealthcareAccessStatus healthcareStatus = site.getHealthcareAccessStatus();
        boolean healthcareKnown = healthcareStatus != null && healthcareStatus != HealthcareAccessStatus.UNKNOWN;
        Double healthcareScore = healthcareKnown ? scoreForStatus(healthcareStatus != null ? healthcareStatus.name() : null) : null;
        factors.put("healthcare", buildFactorDetail(healthcareStatus != null ? healthcareStatus.name() : "UNKNOWN", healthcareScore, thresholds.getHealthcareWeight(), healthcareKnown));

        // 6. Water Accessibility (Weight: 10%)
        WaterAccessStatus waterStatus = site.getWaterAccessStatus();
        boolean waterKnown = waterStatus != null && waterStatus != WaterAccessStatus.UNKNOWN;
        Double waterScore = waterKnown ? scoreForStatus(waterStatus != null ? waterStatus.name() : null) : null;
        factors.put("water", buildFactorDetail(waterStatus != null ? waterStatus.name() : "UNKNOWN", waterScore, thresholds.getWaterWeight(), waterKnown));

        // 7. Supporting Infrastructure (Weight: 10%)
        InfrastructureAccessStatus infraStatus = site.getInfrastructureAccessStatus();
        boolean infraKnown = infraStatus != null && infraStatus != InfrastructureAccessStatus.UNKNOWN;
        Double infraScore = infraKnown ? scoreForStatus(infraStatus != null ? infraStatus.name() : null) : null;
        factors.put("infrastructure", buildFactorDetail(infraStatus != null ? infraStatus.name() : "UNKNOWN", infraScore, thresholds.getInfrastructureWeight(), infraKnown));

        int knownCount = (hazardKnown ? 1 : 0) + (terrainKnown ? 1 : 0) + (distanceKnown ? 1 : 0)
                + (roadKnown ? 1 : 0) + (healthcareKnown ? 1 : 0) + (waterKnown ? 1 : 0) + (infraKnown ? 1 : 0);
        int unknownCount = 7 - knownCount;
        double completeness = Math.round((knownCount / 7.0) * 1000.0) / 10.0;

        site.setKnownFactorCount(knownCount);
        site.setUnknownFactorCount(unknownCount);
        site.setDataCompletenessPercentage(completeness);
        site.setSuitabilityFactors(factors);

        if (knownCount == 0) {
            site.setSuitabilityScore(null);
            site.setSuitabilityClass(SuitabilityClass.UNKNOWN);
            site.setSuitabilityReason("Insufficient spatial dimension data: All 7 evaluation dimensions are UNKNOWN; site suitability undetermined.");
            return;
        }

        // HARD SAFETY GATE: If hazard safety is AT_RISK, calculate diagnostic score and classify as UNSUITABLE
        if (hazardStatus == HazardSafetyStatus.AT_RISK) {
            double nonHazardWeightedSum = 0.0;
            double nonHazardKnownWeightSum = 0.0;

            if (terrainKnown && terrainScore != null) {
                nonHazardWeightedSum += terrainScore * thresholds.getTerrainWeight();
                nonHazardKnownWeightSum += thresholds.getTerrainWeight();
            }
            if (distanceKnown && distanceScore != null) {
                nonHazardWeightedSum += distanceScore * thresholds.getDistanceWeight();
                nonHazardKnownWeightSum += thresholds.getDistanceWeight();
            }
            if (roadKnown && roadScore != null) {
                nonHazardWeightedSum += roadScore * thresholds.getRoadsWeight();
                nonHazardKnownWeightSum += thresholds.getRoadsWeight();
            }
            if (healthcareKnown && healthcareScore != null) {
                nonHazardWeightedSum += healthcareScore * thresholds.getHealthcareWeight();
                nonHazardKnownWeightSum += thresholds.getHealthcareWeight();
            }
            if (waterKnown && waterScore != null) {
                nonHazardWeightedSum += waterScore * thresholds.getWaterWeight();
                nonHazardKnownWeightSum += thresholds.getWaterWeight();
            }
            if (infraKnown && infraScore != null) {
                nonHazardWeightedSum += infraScore * thresholds.getInfrastructureWeight();
                nonHazardKnownWeightSum += thresholds.getInfrastructureWeight();
            }

            Double diagnosticScore = null;
            if (nonHazardKnownWeightSum > 0) {
                double normalized = nonHazardWeightedSum / nonHazardKnownWeightSum;
                diagnosticScore = Math.round(normalized * 100.0) / 100.0;
            }

            site.setSuitabilityScore(diagnosticScore);
            site.setSuitabilityClass(SuitabilityClass.UNSUITABLE);
            site.setSuitabilityReason("Site is classified as UNSUITABLE because it is currently AT_RISK; hazard exposure overrides other suitability factors.");
            return;
        }

        // Weighted normalization over known dimensions
        double weightedSum = 0.0;
        double knownWeightSum = 0.0;

        if (hazardKnown && hazardScore != null) {
            weightedSum += hazardScore * thresholds.getHazardSafetyWeight();
            knownWeightSum += thresholds.getHazardSafetyWeight();
        }
        if (terrainKnown && terrainScore != null) {
            weightedSum += terrainScore * thresholds.getTerrainWeight();
            knownWeightSum += thresholds.getTerrainWeight();
        }
        if (distanceKnown && distanceScore != null) {
            weightedSum += distanceScore * thresholds.getDistanceWeight();
            knownWeightSum += thresholds.getDistanceWeight();
        }
        if (roadKnown && roadScore != null) {
            weightedSum += roadScore * thresholds.getRoadsWeight();
            knownWeightSum += thresholds.getRoadsWeight();
        }
        if (healthcareKnown && healthcareScore != null) {
            weightedSum += healthcareScore * thresholds.getHealthcareWeight();
            knownWeightSum += thresholds.getHealthcareWeight();
        }
        if (waterKnown && waterScore != null) {
            weightedSum += waterScore * thresholds.getWaterWeight();
            knownWeightSum += thresholds.getWaterWeight();
        }
        if (infraKnown && infraScore != null) {
            weightedSum += infraScore * thresholds.getInfrastructureWeight();
            knownWeightSum += thresholds.getInfrastructureWeight();
        }

        double normalizedScore = knownWeightSum > 0 ? (weightedSum / knownWeightSum) : 0.0;
        double roundedScore = Math.round(normalizedScore * 100.0) / 100.0;
        site.setSuitabilityScore(roundedScore);

        SuitabilityClass suitabilityClass;
        if (roundedScore >= thresholds.getHighlySuitableMinScore()) {
            suitabilityClass = SuitabilityClass.HIGHLY_SUITABLE;
        } else if (roundedScore >= thresholds.getSuitableMinScore()) {
            suitabilityClass = SuitabilityClass.SUITABLE;
        } else if (roundedScore >= thresholds.getMarginalMinScore()) {
            suitabilityClass = SuitabilityClass.MARGINAL;
        } else {
            suitabilityClass = SuitabilityClass.UNSUITABLE;
        }
        site.setSuitabilityClass(suitabilityClass);

        StringBuilder reason = new StringBuilder();
        reason.append("Candidate site evaluated as ").append(suitabilityClass.name())
                .append(" with suitability score ").append(String.format("%.1f", roundedScore))
                .append("/100 (").append(knownCount).append("/7 dimensions evaluated, ")
                .append(String.format("%.1f", completeness)).append("% data completeness).");

        if (unknownCount > 0) {
            reason.append(" Score normalized over ").append(knownCount).append(" known dimensions; ")
                    .append(unknownCount).append(" dimension(s) had UNKNOWN data.");
        }

        site.setSuitabilityReason(reason.toString());
    }

    private double scoreForStatus(String statusName) {
        if (statusName == null) return thresholds.getPoorScore();
        switch (statusName.toUpperCase()) {
            case "NEAR":
            case "SAFE":
            case "FAVORABLE":
                return thresholds.getOptimalScore();
            case "MODERATE":
                return thresholds.getModerateScore();
            case "FAR":
                return thresholds.getFarScore();
            default:
                return thresholds.getPoorScore();
        }
    }

    private Map<String, Object> buildFactorDetail(String status, Double score, double weight, boolean isKnown) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        detail.put("score", score);
        detail.put("weight", weight);
        detail.put("isKnown", isKnown);
        return detail;
    }

    // =========================================================================
    // 8. DIMENSION 9: DETERMINISTIC HIERARCHICAL RANKING
    // =========================================================================

    public List<CandidateSafeSiteDto> rankCandidateSites(List<CandidateSafeSiteDto> sites) {
        if (sites == null || sites.isEmpty()) {
            return Collections.emptyList();
        }

        List<CandidateSafeSiteDto> sortedSites = new ArrayList<>(sites);
        sortedSites.sort(RANKING_COMPARATOR);

        int totalCount = sortedSites.size();
        for (int i = 0; i < totalCount; i++) {
            CandidateSafeSiteDto site = sortedSites.get(i);
            int rank = i + 1;
            site.setRank(rank);
            site.setRankingReason(generateRankingReason(site, rank, totalCount));
        }

        log.debug("Ranked {} candidate safe sites for Stage 5.11", totalCount);
        return sortedSites;
    }

    public String generateRankingReason(CandidateSafeSiteDto site, int rank, int totalCandidates) {
        if (site == null) {
            return "Rank #" + rank + " of " + totalCandidates;
        }

        SuitabilityClass suitabilityClass = site.getSuitabilityClass();
        Double score = site.getSuitabilityScore();
        Double completeness = site.getDataCompletenessPercentage();
        HazardSafetyStatus hazardStatus = site.getHazardSafetyStatus();

        String scoreStr = score != null ? String.format("%.1f", score) : "N/A";
        String completenessStr = completeness != null ? String.format("%.1f", completeness) + "%" : "N/A";

        if (suitabilityClass == null || suitabilityClass == SuitabilityClass.UNKNOWN) {
            return String.format("Rank #%d of %d: Suitability undetermined due to insufficient spatial dimension data.",
                    rank, totalCandidates);
        }

        switch (suitabilityClass) {
            case HIGHLY_SUITABLE:
                return String.format("Rank #%d of %d: Highly suitable safe site with top-tier suitability score (%s/100) and %s data completeness.",
                        rank, totalCandidates, scoreStr, completenessStr);

            case SUITABLE:
                return String.format("Rank #%d of %d: Suitable candidate safe site with suitability score %s/100 and %s data completeness.",
                        rank, totalCandidates, scoreStr, completenessStr);

            case MARGINAL:
                return String.format("Rank #%d of %d: Marginal candidate safe site with suitability score %s/100 and %s data completeness.",
                        rank, totalCandidates, scoreStr, completenessStr);

            case UNSUITABLE:
                if (hazardStatus == HazardSafetyStatus.AT_RISK) {
                    return String.format("Rank #%d of %d: Unsuitable safe site due to active hazard exposure override (AT_RISK); diagnostic non-hazard score is %s/100.",
                            rank, totalCandidates, scoreStr);
                } else {
                    return String.format("Rank #%d of %d: Unsuitable candidate safe site due to low multi-criteria suitability score (%s/100).",
                            rank, totalCandidates, scoreStr);
                }

            default:
                return String.format("Rank #%d of %d: Candidate classified as %s with score %s/100.",
                        rank, totalCandidates, suitabilityClass.name(), scoreStr);
        }
    }

    // =========================================================================
    // 9. QUERY FILTERING & RETRIEVAL
    // =========================================================================

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly) {
        return getCandidateSites(district, category, redZoneOnly, null, null, null, null, null, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly, String hazardSafety) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, null, null, null, null, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly, String hazardSafety, String terrainStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, null, null, null, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, null, null, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, null, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus, String healthcareAccessStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, null, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus, String healthcareAccessStatus,
                                                        String waterAccessStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, null, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus, String healthcareAccessStatus,
                                                        String waterAccessStatus, String infrastructureAccessStatus) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, infrastructureAccessStatus, null, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus, String healthcareAccessStatus,
                                                        String waterAccessStatus, String infrastructureAccessStatus,
                                                        String suitabilityClass) {
        return getCandidateSites(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, infrastructureAccessStatus, suitabilityClass, null);
    }

    public List<CandidateSafeSiteDto> getCandidateSites(String district, String category, boolean redZoneOnly,
                                                        String hazardSafety, String terrainStatus, String distanceStatus,
                                                        String roadAccessStatus, String healthcareAccessStatus,
                                                        String waterAccessStatus, String infrastructureAccessStatus,
                                                        String suitabilityClass, Integer top) {
        if (top != null && top <= 0) {
            throw new InvalidHazardParameterException("Parameter 'top' must be a positive integer greater than 0.");
        }

        List<CandidateSafeSiteDto> candidates;
        if (district != null && !district.trim().isEmpty()) {
            String targetDistrict = district.trim();
            long now = System.currentTimeMillis();
            if (now - lastSitesCacheClear <= SITES_CACHE_TTL_MS && !evaluatedSitesCache.isEmpty()) {
                candidates = evaluatedSitesCache.stream()
                        .filter(c -> c.getDistrict() != null && c.getDistrict().equalsIgnoreCase(targetDistrict))
                        .collect(Collectors.toList());
            } else {
                List<InfrastructureAssetDto> allFacilities = dataProvider != null
                        ? dataProvider.getAllRegionalFacilities()
                        : Collections.emptyList();
                List<InfrastructureAssetDto> districtFacilities = allFacilities.stream()
                        .filter(f -> f.getDistrictName() != null && f.getDistrictName().equalsIgnoreCase(targetDistrict))
                        .collect(Collectors.toList());

                if (!districtFacilities.isEmpty()) {
                    candidates = evaluateFacilities(districtFacilities);
                } else {
                    candidates = getAllCandidateSites().stream()
                            .filter(c -> c.getDistrict() != null && c.getDistrict().equalsIgnoreCase(targetDistrict))
                            .collect(Collectors.toList());
                }
            }
        } else {
            candidates = getAllCandidateSites();
        }

        // 2. Filter by category
        if (category != null && !category.trim().isEmpty()) {
            CandidateSiteCategory targetCategory = CandidateSiteCategory.fromString(category);
            if (targetCategory == null) {
                throw new InvalidHazardParameterException(
                        "Invalid safe-site category: '" + category + "'. Allowed categories: EDUCATION, GOVERNMENT, EMERGENCY_SHELTER, HEALTHCARE");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getCategory() == targetCategory)
                    .collect(Collectors.toList());
        }

        // 3. Filter by Red-Zone districts
        if (redZoneOnly && redZoneService != null) {
            Set<String> redZoneDistricts = redZoneService.getRedZonesOnly().stream()
                    .map(RedZoneDto::getDistrictName)
                    .filter(Objects::nonNull)
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());

            candidates = candidates.stream()
                    .filter(c -> c.getDistrict() != null && redZoneDistricts.contains(c.getDistrict().toUpperCase()))
                    .collect(Collectors.toList());
        }

        // 4. Filter by Hazard Safety Status
        if (hazardSafety != null && !hazardSafety.trim().isEmpty()) {
            HazardSafetyStatus targetStatus = HazardSafetyStatus.fromString(hazardSafety);
            if (targetStatus == null) {
                throw new InvalidHazardParameterException(
                        "Invalid hazardSafety filter: '" + hazardSafety + "'. Allowed values: SAFE, AT_RISK, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getHazardSafetyStatus() == targetStatus)
                    .collect(Collectors.toList());
        }

        // 5. Filter by Terrain Status
        if (terrainStatus != null && !terrainStatus.trim().isEmpty()) {
            TerrainStatus targetTerrain = TerrainStatus.fromString(terrainStatus);
            if (targetTerrain == null) {
                throw new InvalidHazardParameterException(
                        "Invalid terrainStatus filter: '" + terrainStatus + "'. Allowed values: FAVORABLE, UNFAVORABLE, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getTerrainStatus() == targetTerrain)
                    .collect(Collectors.toList());
        }

        // 6. Filter by Distance Status
        if (distanceStatus != null && !distanceStatus.trim().isEmpty()) {
            DistanceStatus targetDistance = DistanceStatus.fromString(distanceStatus);
            if (targetDistance == null) {
                throw new InvalidHazardParameterException(
                        "Invalid distanceStatus filter: '" + distanceStatus + "'. Allowed values: NEAR, MODERATE, FAR, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getDistanceStatus() == targetDistance)
                    .collect(Collectors.toList());
        }

        // 7. Filter by Road Access Status
        if (roadAccessStatus != null && !roadAccessStatus.trim().isEmpty()) {
            RoadAccessStatus targetRoad = RoadAccessStatus.fromString(roadAccessStatus);
            if (targetRoad == null) {
                throw new InvalidHazardParameterException(
                        "Invalid roadAccessStatus filter: '" + roadAccessStatus + "'. Allowed values: NEAR, MODERATE, FAR, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getRoadAccessStatus() == targetRoad)
                    .collect(Collectors.toList());
        }

        // 8. Filter by Healthcare Access Status
        if (healthcareAccessStatus != null && !healthcareAccessStatus.trim().isEmpty()) {
            HealthcareAccessStatus targetHealthcare = HealthcareAccessStatus.fromString(healthcareAccessStatus);
            if (targetHealthcare == null) {
                throw new InvalidHazardParameterException(
                        "Invalid healthcareAccessStatus filter: '" + healthcareAccessStatus + "'. Allowed values: NEAR, MODERATE, FAR, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getHealthcareAccessStatus() == targetHealthcare)
                    .collect(Collectors.toList());
        }

        // 9. Filter by Water Access Status
        if (waterAccessStatus != null && !waterAccessStatus.trim().isEmpty()) {
            WaterAccessStatus targetWater = WaterAccessStatus.fromString(waterAccessStatus);
            if (targetWater == null) {
                throw new InvalidHazardParameterException(
                        "Invalid waterAccessStatus filter: '" + waterAccessStatus + "'. Allowed values: NEAR, MODERATE, FAR, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getWaterAccessStatus() == targetWater)
                    .collect(Collectors.toList());
        }

        // 10. Filter by Supporting Infrastructure Access Status
        if (infrastructureAccessStatus != null && !infrastructureAccessStatus.trim().isEmpty()) {
            InfrastructureAccessStatus targetInfra = InfrastructureAccessStatus.fromString(infrastructureAccessStatus);
            if (targetInfra == null) {
                throw new InvalidHazardParameterException(
                        "Invalid infrastructureAccessStatus filter: '" + infrastructureAccessStatus + "'. Allowed values: NEAR, MODERATE, FAR, UNKNOWN");
            }
            candidates = candidates.stream()
                    .filter(c -> c.getInfrastructureAccessStatus() == targetInfra)
                    .collect(Collectors.toList());
        }

        // 11. Filter by Suitability Class
        if (suitabilityClass != null && !suitabilityClass.trim().isEmpty()) {
            SuitabilityClass targetSuitability = SuitabilityClass.fromString(suitabilityClass);
            candidates = candidates.stream()
                    .filter(c -> c.getSuitabilityClass() == targetSuitability)
                    .collect(Collectors.toList());
        }

        if (top != null && top < candidates.size()) {
            return new ArrayList<>(candidates.subList(0, top));
        }
        return candidates;
    }

    public CandidateSafeSiteDto getCandidateSiteById(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) {
            throw new IllegalArgumentException("Site identifier cannot be null or empty");
        }

        String targetId = siteId.trim();
        return getAllCandidateSites().stream()
                .filter(c -> c.getSiteId() != null && c.getSiteId().equalsIgnoreCase(targetId))
                .findFirst()
                .orElseThrow(() -> new HazardNotFoundException("Candidate safe site not found: " + targetId));
    }

    // =========================================================================
    // 10. RFC 7946 GEOJSON FEATURE COLLECTION GENERATION
    // =========================================================================

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, null, null, null, null, null, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly, String hazardSafety) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, null, null, null, null, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly, String hazardSafety, String terrainStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, null, null, null, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, null, null, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, null, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus, String healthcareAccessStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, null, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus, String healthcareAccessStatus,
                                                                     String waterAccessStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, null, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus, String healthcareAccessStatus,
                                                                     String waterAccessStatus, String infrastructureAccessStatus) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, infrastructureAccessStatus, null, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus, String healthcareAccessStatus,
                                                                     String waterAccessStatus, String infrastructureAccessStatus,
                                                                     String suitabilityClass) {
        return generateCandidateSitesGeoJson(district, category, redZoneOnly, hazardSafety, terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus, waterAccessStatus, infrastructureAccessStatus, suitabilityClass, null);
    }

    public GeoJsonFeatureCollectionDto generateCandidateSitesGeoJson(String district, String category, boolean redZoneOnly,
                                                                     String hazardSafety, String terrainStatus, String distanceStatus,
                                                                     String roadAccessStatus, String healthcareAccessStatus,
                                                                     String waterAccessStatus, String infrastructureAccessStatus,
                                                                     String suitabilityClass, Integer top) {
        List<CandidateSafeSiteDto> sites = getCandidateSites(district, category, redZoneOnly, hazardSafety,
                terrainStatus, distanceStatus, roadAccessStatus, healthcareAccessStatus,
                waterAccessStatus, infrastructureAccessStatus, suitabilityClass, top);

        List<GeoJsonFeatureDto> features = sites.stream()
                .map(this::toGeoJsonFeature)
                .collect(Collectors.toList());

        GeoJsonFeatureCollectionDto collection = new GeoJsonFeatureCollectionDto();
        collection.setFeatures(features);
        collection.setCount(features.size());
        return collection;
    }

    private GeoJsonFeatureDto toGeoJsonFeature(CandidateSafeSiteDto site) {
        GeoJsonFeatureDto feature = new GeoJsonFeatureDto();
        feature.setId("SAFE-SITE-" + site.getSiteId());

        if (site.getLongitude() != null && site.getLatitude() != null) {
            GeoJsonGeometryDto geom = new GeoJsonGeometryDto();
            geom.setType("Point");
            geom.setCoordinates(Arrays.asList(site.getLongitude(), site.getLatitude()));
            feature.setGeometry(geom);
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("siteId", site.getSiteId());
        props.put("siteName", site.getSiteName());
        props.put("district", site.getDistrict());
        props.put("state", site.getState());
        props.put("category", site.getCategory() != null ? site.getCategory().name() : null);
        props.put("categoryDisplayName", site.getCategoryDisplayName());
        props.put("subType", site.getSubType());
        props.put("status", "CANDIDATE");
        props.put("layerId", "CANDIDATE_SAFE_SITES");
        props.put("colorHex", site.getColorHex());
        props.put("hazardSafetyStatus", site.getHazardSafetyStatus() != null ? site.getHazardSafetyStatus().name() : "UNKNOWN");
        props.put("riskZone", site.getRiskZone());
        props.put("riskScore", site.getRiskScore());
        props.put("hazardSafetyReason", site.getHazardSafetyReason());
        props.put("elevationMeters", site.getElevationMeters());
        props.put("slopeDegrees", site.getSlopeDegrees());
        props.put("terrainStatus", site.getTerrainStatus() != null ? site.getTerrainStatus().name() : "UNKNOWN");
        props.put("terrainReason", site.getTerrainReason());
        props.put("distanceMeters", site.getDistanceMeters());
        props.put("distanceKilometers", site.getDistanceKilometers());
        props.put("distanceStatus", site.getDistanceStatus() != null ? site.getDistanceStatus().name() : "UNKNOWN");
        props.put("distanceReason", site.getDistanceReason());
        props.put("roadDistanceMeters", site.getRoadDistanceMeters());
        props.put("roadDistanceKilometers", site.getRoadDistanceKilometers());
        props.put("roadAccessStatus", site.getRoadAccessStatus() != null ? site.getRoadAccessStatus().name() : "UNKNOWN");
        props.put("roadAccessReason", site.getRoadAccessReason());
        props.put("healthcareDistanceMeters", site.getHealthcareDistanceMeters());
        props.put("healthcareDistanceKilometers", site.getHealthcareDistanceKilometers());
        props.put("healthcareAccessStatus", site.getHealthcareAccessStatus() != null ? site.getHealthcareAccessStatus().name() : "UNKNOWN");
        props.put("nearestHealthcareSiteId", site.getNearestHealthcareSiteId());
        props.put("nearestHealthcareSiteName", site.getNearestHealthcareSiteName());
        props.put("healthcareReason", site.getHealthcareReason());
        props.put("waterDistanceMeters", site.getWaterDistanceMeters());
        props.put("waterDistanceKilometers", site.getWaterDistanceKilometers());
        props.put("waterAccessStatus", site.getWaterAccessStatus() != null ? site.getWaterAccessStatus().name() : "UNKNOWN");
        props.put("nearestWaterSiteId", site.getNearestWaterSiteId());
        props.put("nearestWaterSiteName", site.getNearestWaterSiteName());
        props.put("waterReason", site.getWaterReason());
        props.put("infrastructureDistanceMeters", site.getInfrastructureDistanceMeters());
        props.put("infrastructureDistanceKilometers", site.getInfrastructureDistanceKilometers());
        props.put("infrastructureAccessStatus", site.getInfrastructureAccessStatus() != null ? site.getInfrastructureAccessStatus().name() : "UNKNOWN");
        props.put("nearestInfrastructureSiteId", site.getNearestInfrastructureSiteId());
        props.put("nearestInfrastructureSiteName", site.getNearestInfrastructureSiteName());
        props.put("nearestInfrastructureCategory", site.getNearestInfrastructureCategory());
        props.put("infrastructureReason", site.getInfrastructureReason());
        props.put("suitabilityScore", site.getSuitabilityScore());
        props.put("suitabilityClass", site.getSuitabilityClass() != null ? site.getSuitabilityClass().name() : "UNKNOWN");
        props.put("knownFactorCount", site.getKnownFactorCount());
        props.put("unknownFactorCount", site.getUnknownFactorCount());
        props.put("dataCompletenessPercentage", site.getDataCompletenessPercentage());
        props.put("suitabilityReason", site.getSuitabilityReason());
        props.put("suitabilityFactors", site.getSuitabilityFactors());
        props.put("rank", site.getRank());
        props.put("rankingReason", site.getRankingReason());
        props.put("source", site.getSource());

        feature.setProperties(props);
        return feature;
    }
}
