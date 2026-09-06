package com.hazard.service.risk;

import com.hazard.domain.boundaries.DistrictBoundary;
import com.hazard.domain.historical.HistoricalTimeWindow;
import com.hazard.domain.risk.RiskComponentType;
import com.hazard.domain.risk.config.RiskConfigurationProfile;
import com.hazard.dto.exposure.DistrictSettlementExposureSummaryDto;
import com.hazard.dto.exposure.PopulationExposureResultDto;
import com.hazard.dto.exposure.SettlementExposureDto;
import com.hazard.dto.hazard.GeoJsonFeatureCollectionDto;
import com.hazard.dto.hazard.GeoJsonFeatureDto;
import com.hazard.dto.hazard.GeoJsonGeometryDto;
import com.hazard.dto.historical.DistrictHistoricalSummaryDto;
import com.hazard.dto.infrastructure.DistrictInfrastructureExposureSummaryDto;
import com.hazard.dto.infrastructure.InfrastructureAssetDto;
import com.hazard.dto.risk.*;
import com.hazard.dto.risk.config.RiskScenarioAnalysisRequestDto;
import com.hazard.dto.risk.config.RiskScenarioAnalysisResultDto;
import com.hazard.dto.scoring.HazardScoreDto;
import com.hazard.dto.vulnerability.DistrictVulnerabilityScoreDto;
import com.hazard.exception.HazardNotFoundException;
import com.hazard.repository.boundaries.DistrictBoundaryRepository;
import com.hazard.service.exposure.InfrastructureExposureService;
import com.hazard.service.exposure.PopulationExposureService;
import com.hazard.service.exposure.SettlementExposureService;
import com.hazard.service.historical.HistoricalDisasterService;
import com.hazard.service.risk.config.RiskConfigurationService;
import com.hazard.service.scoring.HazardScoringService;
import com.hazard.service.vulnerability.VulnerabilityScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Master Domain Service for Stage 4.7 & Stage 4.8.
 * Orchestrates Hazard, Exposure, Vulnerability, and Historical Evidence,
 * dynamically consuming the active Risk Configuration Profile from Stage 4.8.
 */
@Service
@Transactional(readOnly = true)
public class RiskCalculationService {

    private static final Logger log = LoggerFactory.getLogger(RiskCalculationService.class);

    private final DistrictBoundaryRepository districtBoundaryRepository;
    private final HazardScoringService hazardScoringService;
    private final PopulationExposureService populationExposureService;
    private final SettlementExposureService settlementExposureService;
    private final InfrastructureExposureService infrastructureExposureService;
    private final VulnerabilityScoringService vulnerabilityScoringService;
    private final HistoricalDisasterService historicalDisasterService;
    private final RiskConfigurationService riskConfigurationService;
    private final RiskCalculationEngine engine;

    // Cache for district risk scores and all-districts evaluations (TTL: 5m)
    private final Map<String, DistrictRiskScoreDto> districtScoreCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<DistrictRiskScoreDto> allDistrictsScoreCache = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long lastAllDistrictsCacheClear = 0L;
    private static final long CACHE_TTL_MS = 300_000L;

    public RiskCalculationService(DistrictBoundaryRepository districtBoundaryRepository,
                                  HazardScoringService hazardScoringService,
                                  PopulationExposureService populationExposureService,
                                  SettlementExposureService settlementExposureService,
                                  InfrastructureExposureService infrastructureExposureService,
                                  VulnerabilityScoringService vulnerabilityScoringService,
                                  HistoricalDisasterService historicalDisasterService,
                                  RiskConfigurationService riskConfigurationService,
                                  RiskCalculationEngine engine) {
        this.districtBoundaryRepository = districtBoundaryRepository;
        this.hazardScoringService = hazardScoringService;
        this.populationExposureService = populationExposureService;
        this.settlementExposureService = settlementExposureService;
        this.infrastructureExposureService = infrastructureExposureService;
        this.vulnerabilityScoringService = vulnerabilityScoringService;
        this.historicalDisasterService = historicalDisasterService;
        this.riskConfigurationService = riskConfigurationService;
        this.engine = engine;
    }

    // =========================================================================
    // 1. DISTRICT RISK CALCULATION WITH DYNAMIC ACTIVE CONFIGURATION
    // =========================================================================

    public DistrictRiskScoreDto getDistrictRiskScore(String districtName, Map<RiskComponentType, Double> customWeights) {
        if (districtName == null || districtName.trim().isEmpty()) {
            throw new IllegalArgumentException("District name cannot be null or empty");
        }

        String targetDistrict = districtName.trim();
        if (customWeights == null) {
            DistrictRiskScoreDto cached = districtScoreCache.get(targetDistrict.toUpperCase());
            if (cached != null) {
                return cached;
            }
        }

        DistrictBoundary boundary = districtBoundaryRepository.findByName2IgnoreCase(targetDistrict)
                .orElseThrow(() -> new HazardNotFoundException("Administrative district not found: " + targetDistrict));

        // Dynamically retrieve active configuration profile
        RiskConfigurationProfile activeProfile = riskConfigurationService.getActiveConfiguration();
        Map<RiskComponentType, Double> activeTopWeights = customWeights != null ? customWeights : activeProfile.toTopLevelWeightMap();
        Map<String, Double> activeExpWeights = activeProfile.toExposureSubWeightMap();

        // 1. Stage 3: Hazard Component
        Double hazardScore = null;
        String hazardDesc = "Stage 3 Hazard Score";
        try {
            List<HazardScoreDto> scores = hazardScoringService.getAllHazardScores(null, boundary.getName2(), null, null, 10);
            if (scores != null && !scores.isEmpty()) {
                hazardScore = scores.stream()
                        .filter(s -> s.getHazardScore() != null)
                        .mapToDouble(HazardScoreDto::getHazardScore)
                        .average()
                        .orElse(0.60);
                hazardDesc = String.format("Stage 3 Multi-Hazard Score (avg of %d observations: %.4f)", scores.size(), hazardScore);
            } else {
                hazardScore = 0.55;
            }
        } catch (Exception e) {
            log.debug("Hazard retrieval for district {}: {}", boundary.getName2(), e.getMessage());
            hazardScore = 0.50;
        }

        // 2. Stages 4.1–4.3: Exposure Components
        Double popScore = null;
        Long exposedPop = null;
        Double exposedPopPct = null;
        try {
            PopulationExposureResultDto popResult = populationExposureService.analyzeDistrictPopulationExposure(boundary.getName2());
            if (popResult != null) {
                popScore = popResult.getExposureScore();
                exposedPop = popResult.getExposedPopulation();
                exposedPopPct = popResult.getExposurePercentage();
            }
        } catch (Exception e) {
            log.debug("Population exposure retrieval for district {}: {}", boundary.getName2(), e.getMessage());
        }

        Double settleScore = null;
        Integer settleCount = null;
        try {
            DistrictSettlementExposureSummaryDto setSummary = settlementExposureService.getDistrictSettlementExposure(boundary.getName2());
            if (setSummary != null) {
                settleCount = setSummary.getTotalSettlementsEvaluated();
                if (setSummary.getSettlements() != null && !setSummary.getSettlements().isEmpty()) {
                    settleScore = setSummary.getSettlements().stream()
                            .filter(s -> s.getSettlementExposureScore() != null)
                            .mapToDouble(SettlementExposureDto::getSettlementExposureScore)
                            .average()
                            .orElse(0.50);
                } else if (setSummary.getSettlementExposurePercentage() != null) {
                    settleScore = setSummary.getSettlementExposurePercentage() / 100.0;
                } else {
                    settleScore = 0.40;
                }
            }
        } catch (Exception e) {
            log.debug("Settlement exposure retrieval for district {}: {}", boundary.getName2(), e.getMessage());
        }

        Double infraScore = null;
        Integer infraCount = null;
        try {
            DistrictInfrastructureExposureSummaryDto infSummary = infrastructureExposureService.getDistrictInfrastructureExposure(boundary.getName2());
            if (infSummary != null) {
                infraCount = infSummary.getTotalAssetsEvaluated();
                if (infSummary.getExposedAssets() != null && !infSummary.getExposedAssets().isEmpty()) {
                    infraScore = infSummary.getExposedAssets().stream()
                            .filter(a -> a.getInfrastructureExposureScore() != null)
                            .mapToDouble(InfrastructureAssetDto::getInfrastructureExposureScore)
                            .average()
                            .orElse(0.50);
                } else if (infSummary.getInfrastructureExposurePercentage() != null) {
                    infraScore = infSummary.getInfrastructureExposurePercentage() / 100.0;
                } else {
                    infraScore = 0.45;
                }
            }
        } catch (Exception e) {
            log.debug("Infrastructure exposure retrieval for district {}: {}", boundary.getName2(), e.getMessage());
        }

        // Aggregate Exposure Sub-Breakdown using configured exposure weights
        ExposureSubBreakdownDto exposureBreakdown = engine.calculateCombinedExposure(
                popScore, settleScore, infraScore, exposedPop, exposedPopPct, settleCount, infraCount, activeExpWeights);
        Double combinedExposureScore = exposureBreakdown.getCombinedExposureScore();

        // 3. Stage 4.5: Vulnerability Component
        Double vulnScore = null;
        try {
            DistrictVulnerabilityScoreDto vScore = vulnerabilityScoringService.getDistrictVulnerabilityScore(boundary.getName2());
            if (vScore != null) {
                vulnScore = vScore.getVulnerabilityScore();
            }
        } catch (Exception e) {
            log.debug("Vulnerability retrieval for district {}: {}", boundary.getName2(), e.getMessage());
        }

        // 4. Stage 4.6: Historical Intelligence Component
        Double histScore = null;
        try {
            DistrictHistoricalSummaryDto hSum = historicalDisasterService.getDistrictHistoricalSummary(
                    boundary.getName2(), HistoricalTimeWindow.ALL_HISTORY, null, null, null);
            if (hSum != null) {
                histScore = hSum.getHistoricalHotspotIndex();
            }
        } catch (Exception e) {
            log.debug("Historical intelligence retrieval for district {}: {}", boundary.getName2(), e.getMessage());
        }

        // 5. Engine Calculation
        Map<String, String> sourceDescs = new HashMap<>();
        sourceDescs.put("HAZARD", hazardDesc);

        DistrictRiskScoreDto riskResult = engine.calculateDistrictRisk(
                boundary.getName2(),
                hazardScore,
                combinedExposureScore,
                vulnScore,
                histScore,
                exposureBreakdown,
                activeTopWeights,
                sourceDescs
        );

        riskResult.setDistrictId(boundary.getId());
        riskResult.setGid2(boundary.getGid2());
        riskResult.setState(boundary.getName1() != null ? boundary.getName1() : "Bihar");
        riskResult.setConfigurationId(activeProfile.getConfigId());
        riskResult.setConfigurationVersion(activeProfile.getVersion());
        if (customWeights == null) {
            districtScoreCache.put(targetDistrict.toUpperCase(), riskResult);
        }

        return riskResult;
    }

    // =========================================================================
    // 2. EXPLAINABLE RISK CONTRIBUTORS
    // =========================================================================

    public RiskContributorsSummaryDto getDistrictRiskContributors(String districtName) {
        DistrictRiskScoreDto riskDto = getDistrictRiskScore(districtName, null);

        RiskContributorsSummaryDto summary = new RiskContributorsSummaryDto();
        summary.setGeographicId(riskDto.getDistrictName());
        summary.setRiskScore(riskDto.getRiskScore());
        summary.setRiskScore100(riskDto.getRiskScore100());
        summary.setRiskTier(riskDto.getRiskTier());
        summary.setTopDrivers(riskDto.getTopContributors());
        summary.setExposureBreakdown(riskDto.getExposureSubBreakdown());

        String dominant = "HAZARD";
        if (!riskDto.getTopContributors().isEmpty()) {
            dominant = riskDto.getTopContributors().get(0).getPillar();
        }
        summary.setDominantPillar(dominant);

        try {
            var vDto = vulnerabilityScoringService.getDistrictVulnerabilityScore(districtName);
            if (vDto != null && vDto.getTopContributors() != null && !vDto.getTopContributors().isEmpty()) {
                summary.setPrimaryVulnerabilityDriver(vDto.getTopContributors().get(0).getIndicatorName());
            }
        } catch (Exception e) {
            summary.setPrimaryVulnerabilityDriver("Infrastructure Service & Drainage Deficit");
        }

        try {
            var hDto = historicalDisasterService.getDistrictHistoricalSummary(districtName, HistoricalTimeWindow.ALL_HISTORY, null, null, null);
            if (hDto != null) {
                summary.setHistoricalEvidenceSummary(hDto.getSummaryExplanation());
            }
        } catch (Exception e) {
            summary.setHistoricalEvidenceSummary("Empirical disaster history from DFO archive.");
        }

        return summary;
    }

    // =========================================================================
    // 3. ALL DISTRICTS RISK SCORES
    // =========================================================================

    public List<DistrictRiskScoreDto> getAllDistrictsRiskScores() {
        long now = System.currentTimeMillis();
        if (now - lastAllDistrictsCacheClear <= CACHE_TTL_MS && !allDistrictsScoreCache.isEmpty()) {
            return new ArrayList<>(allDistrictsScoreCache);
        }

        List<DistrictBoundary> districts = districtBoundaryRepository.findAllByOrderByName2Asc();
        List<DistrictRiskScoreDto> list = new ArrayList<>();

        for (DistrictBoundary db : districts) {
            try {
                DistrictRiskScoreDto dto = getDistrictRiskScore(db.getName2(), null);
                list.add(dto);
            } catch (Exception e) {
                log.warn("Error computing risk for district {}: {}", db.getName2(), e.getMessage());
            }
        }

        allDistrictsScoreCache.clear();
        allDistrictsScoreCache.addAll(list);
        lastAllDistrictsCacheClear = now;
        return list;
    }

    public void clearCache() {
        districtScoreCache.clear();
        allDistrictsScoreCache.clear();
        lastAllDistrictsCacheClear = 0L;
    }

    // =========================================================================
    // 4. GEOJSON RISK CHOROPLETH LAYER
    // =========================================================================

    public GeoJsonFeatureCollectionDto generateRiskGeoJson() {
        List<DistrictBoundary> districts = districtBoundaryRepository.findAllByOrderByName2Asc();
        List<GeoJsonFeatureDto> features = new ArrayList<>();

        for (DistrictBoundary db : districts) {
            try {
                DistrictRiskScoreDto risk = getDistrictRiskScore(db.getName2(), null);
                GeoJsonGeometryDto geom = GeoJsonGeometryDto.fromJtsGeometry(db.getGeom());

                Map<String, Object> props = new LinkedHashMap<>();
                props.put("districtId", db.getId());
                props.put("districtName", db.getName2());
                props.put("gid2", db.getGid2());
                props.put("riskScore", risk.getRiskScore());
                props.put("riskScore100", risk.getRiskScore100());
                props.put("riskTier", risk.getRiskTier().name());
                props.put("colorHex", risk.getRiskTier().getColorHex());

                if (risk.getComponents().containsKey("HAZARD")) {
                    props.put("hazardScore", risk.getComponents().get("HAZARD").getScore());
                }
                if (risk.getComponents().containsKey("EXPOSURE")) {
                    props.put("exposureScore", risk.getComponents().get("EXPOSURE").getScore());
                }
                if (risk.getComponents().containsKey("VULNERABILITY")) {
                    props.put("vulnerabilityScore", risk.getComponents().get("VULNERABILITY").getScore());
                }
                if (risk.getComponents().containsKey("HISTORICAL")) {
                    props.put("historicalScore", risk.getComponents().get("HISTORICAL").getScore());
                }

                props.put("configurationId", risk.getConfigurationId());
                props.put("configurationVersion", risk.getConfigurationVersion());
                props.put("dataQuality", risk.getDataQuality().getStatus().name());
                props.put("completenessPct", risk.getDataQuality().getCompletenessPercentage());
                props.put("layerId", "DISTRICT_FINAL_RISK");

                features.add(new GeoJsonFeatureDto("RISK-DISTRICT-" + db.getId(), geom, props));
            } catch (Exception e) {
                log.warn("Failed GeoJSON generation for district {}: {}", db.getName2(), e.getMessage());
            }
        }

        return new GeoJsonFeatureCollectionDto(features);
    }

    // =========================================================================
    // 5. WHAT-IF / SCENARIO ANALYSIS
    // =========================================================================

    public RiskScenarioAnalysisResultDto runScenarioAnalysis(RiskScenarioAnalysisRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Scenario request cannot be null");
        }

        String district = request.getDistrictName() != null ? request.getDistrictName().trim() : "Sitamarhi";

        // 1. Calculate baseline risk
        DistrictRiskScoreDto baseline = getDistrictRiskScore(district, null);

        // 2. Prepare scenario weights
        Map<RiskComponentType, Double> scenarioWeights = new LinkedHashMap<>();
        RiskConfigurationProfile baseProfile = (request.getBaseConfigurationId() != null)
                ? riskConfigurationService.getConfigurationById(request.getBaseConfigurationId())
                : riskConfigurationService.getActiveConfiguration();

        scenarioWeights.putAll(baseProfile.toTopLevelWeightMap());

        if (request.getOverrideWeights() != null) {
            request.getOverrideWeights().forEach((k, v) -> {
                if (v != null) {
                    try {
                        RiskComponentType type = RiskComponentType.valueOf(k.trim().toUpperCase());
                        scenarioWeights.put(type, v);
                    } catch (Exception e) {
                        log.debug("Unknown override component key: {}", k);
                    }
                }
            });
        }

        // 3. Calculate scenario risk
        DistrictRiskScoreDto scenario = getDistrictRiskScore(district, scenarioWeights);
        scenario.setConfigurationName(request.getScenarioName() != null ? request.getScenarioName() : "Custom What-If Scenario");

        double deltaRisk = round4(scenario.getRiskScore() - baseline.getRiskScore());
        double deltaRisk100 = round1(scenario.getRiskScore100() - baseline.getRiskScore100());

        String direction = (Math.abs(deltaRisk) < 0.001) ? "UNCHANGED" : (deltaRisk > 0 ? "INCREASED" : "DECREASED");

        RiskScenarioAnalysisResultDto result = new RiskScenarioAnalysisResultDto();
        result.setDistrictName(district);
        result.setScenarioName(request.getScenarioName());
        result.setBaselineRisk(baseline);
        result.setScenarioRisk(scenario);
        result.setDeltaRiskScore(deltaRisk);
        result.setDeltaRiskScore100(deltaRisk100);
        result.setRiskDirection(direction);
        result.setProductionConfigurationUnchanged(true);
        result.setExplanation(String.format("Scenario '%s' for district %s yielded risk score %.1f/100 (baseline: %.1f/100, delta: %s%.1f pts). Production configuration remains unchanged.",
                result.getScenarioName(), district, scenario.getRiskScore100(), baseline.getRiskScore100(),
                deltaRisk100 >= 0 ? "+" : "", deltaRisk100));

        return result;
    }

    // =========================================================================
    // 6. CONFIGURATION DELEGATION (Stage 4.7 Compatibility)
    // =========================================================================

    public RiskConfigDto getRiskConfig() {
        RiskConfigurationProfile active = riskConfigurationService.getActiveConfiguration();
        RiskConfigDto dto = new RiskConfigDto();

        Map<String, Double> compWeights = new LinkedHashMap<>();
        active.toTopLevelWeightMap().forEach((k, v) -> compWeights.put(k.name(), v));
        dto.setRiskComponentWeights(compWeights);

        dto.setExposureSubWeights(new LinkedHashMap<>(active.toExposureSubWeightMap()));

        Map<String, Double> tierThresh = new LinkedHashMap<>();
        tierThresh.put("LOW_MAX", active.getThresholdLowMax());
        tierThresh.put("MODERATE_MAX", active.getThresholdModerateMax());
        tierThresh.put("HIGH_MAX", active.getThresholdHighMax());
        tierThresh.put("VERY_HIGH_MAX", active.getThresholdVeryHighMax());
        tierThresh.put("CRITICAL_MIN", active.getThresholdCriticalMin());
        dto.setTierThresholds(tierThresh);

        dto.setMinimumDataCompleteness(active.getMinimumComponents());
        dto.setCalculationVersion("v1.0");
        return dto;
    }

    public static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }

    public static double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }
}
