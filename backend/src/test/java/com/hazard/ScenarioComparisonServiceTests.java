package com.hazard;

import com.hazard.domain.relocation.PriorityLevel;
import com.hazard.domain.scenario.RedZoneTransitionType;
import com.hazard.domain.scenario.ScenarioDefinition;
import com.hazard.dto.risk.DistrictRiskScoreDto;
import com.hazard.dto.scenario.DistrictScenarioComparisonDto;
import com.hazard.dto.scenario.ScenarioComparisonResultDto;
import com.hazard.dto.scenario.ScenarioCreateRequestDto;
import com.hazard.dto.scenario.ScenarioDto;
import com.hazard.exception.HazardNotFoundException;
import com.hazard.exception.InvalidHazardParameterException;
import com.hazard.repository.boundaries.DistrictBoundaryRepository;
import com.hazard.repository.scenario.ScenarioRepository;
import com.hazard.service.risk.RiskCalculationService;
import com.hazard.service.scenario.ScenarioComparisonService;
import com.hazard.service.scenario.ScenarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9E — Unit and Integration Service Tests for Before/After Scenario Comparison.
 */
@SpringBootTest
class ScenarioComparisonServiceTests {

    @Autowired
    private ScenarioRepository scenarioRepository;

    @Autowired
    private ScenarioService scenarioService;

    @Autowired
    private ScenarioComparisonService scenarioComparisonService;

    @Autowired
    private RiskCalculationService riskCalculationService;

    @Autowired
    private DistrictBoundaryRepository districtBoundaryRepository;

    @BeforeEach
    void setUp() {
        scenarioRepository.resetToBaselineOnly();
    }

    // =========================================================================
    // 1. BASELINE SCENARIO COMPARISON TESTS
    // =========================================================================

    @Nested
    @DisplayName("1. Baseline Scenario Comparison")
    class BaselineComparisonTests {

        @Test
        @DisplayName("Comparing SCEN-BASELINE produces zero deltas across Risk, Red-Zone, Priority, and Relocation")
        void testBaselineScenarioComparison() {
            DistrictScenarioComparisonDto result = scenarioComparisonService.compareDistrictScenario(
                    ScenarioDefinition.BASELINE_SCENARIO_ID, "Sitamarhi"
            );

            assertNotNull(result);
            assertEquals("Sitamarhi", result.getDistrictName());

            // 1. Risk
            assertEquals(0.0, result.getDeltaRiskScore(), 0.0001);
            assertEquals(0.0, result.getDeltaRiskScore100(), 0.0001);
            assertEquals(result.getBaselineRiskScore(), result.getSimulatedRiskScore(), 0.0001);
            assertEquals(result.getBaselineRiskTier(), result.getSimulatedRiskTier());
            assertEquals("UNCHANGED", result.getRiskDirection());

            // 2. Red-Zone
            assertEquals(result.isBaselineRedZone(), result.isSimulatedRedZone());
            assertEquals(RedZoneTransitionType.UNCHANGED_NON_RED_ZONE, result.getRedZoneTransitionType());
            assertFalse(result.isRedZoneChanged());

            // 3. Priority
            assertEquals(0.0, result.getDeltaPriorityScore(), 0.0001);
            assertEquals(result.getBaselinePriorityScore(), result.getSimulatedPriorityScore(), 0.0001);
            assertEquals(result.getBaselinePriorityLevel(), result.getSimulatedPriorityLevel());
            assertEquals("UNCHANGED", result.getPriorityShiftDirection());
            assertFalse(result.isPriorityEscalated());

            // 4. Relocation
            assertEquals(0L, result.getDeltaVulnerablePopulation());
            assertEquals(result.getBaselineVulnerablePopulation(), result.getSimulatedVulnerablePopulation());
            assertEquals(result.getBaselineAllocatedPopulation(), result.getSimulatedAllocatedPopulation());
            assertEquals(result.getBaselineUnallocatedPopulation(), result.getSimulatedUnallocatedPopulation());
            assertEquals(result.getBaselineRelocationStatus(), result.getSimulatedRelocationStatus());
            assertEquals("UNCHANGED", result.getRelocationDemandDirection());
        }
    }

    // =========================================================================
    // 2. RISK SHIFT COMPARISON TESTS
    // =========================================================================

    @Nested
    @DisplayName("2. Risk Increase & Decrease Comparisons")
    class RiskShiftComparisonTests {

        @Test
        @DisplayName("Rainfall surge scenario (+30%) compares before vs after with increased risk")
        void testRiskIncreaseComparison() {
            ScenarioDto rainScenario = scenarioService.createScenario(new ScenarioCreateRequestDto(
                    "Monsoon Surge +30%", "RAINFALL_CHANGE", "Testing risk increase", 30.0, 0.0, 0.0
            ));

            DistrictScenarioComparisonDto result = scenarioComparisonService.compareDistrictScenario(
                    rainScenario.getScenarioId(), "Sitamarhi"
            );

            assertNotNull(result);
            assertTrue(result.getSimulatedRiskScore() > result.getBaselineRiskScore());
            assertTrue(result.getDeltaRiskScore() > 0.0);
            assertEquals("INCREASED", result.getRiskDirection());
        }

        @Test
        @DisplayName("Mitigation scenario (-40% rain, -40% hazard) compares before vs after with decreased risk")
        void testRiskDecreaseComparison() {
            ScenarioDto mitigationScenario = scenarioService.createScenario(new ScenarioCreateRequestDto(
                    "Mitigation -40%", "MULTI_FACTOR", "Testing risk reduction", -40.0, -40.0, 0.0
            ));

            DistrictScenarioComparisonDto result = scenarioComparisonService.compareDistrictScenario(
                    mitigationScenario.getScenarioId(), "Sitamarhi"
            );

            assertNotNull(result);
            assertTrue(result.getSimulatedRiskScore() < result.getBaselineRiskScore());
            assertTrue(result.getDeltaRiskScore() < 0.0);
            assertEquals("DECREASED", result.getRiskDirection());
        }
    }

    // =========================================================================
    // 3. PRIORITY & RELOCATION SHIFT COMPARISONS
    // =========================================================================

    @Nested
    @DisplayName("3. Priority Escalation & Relocation Impact Comparisons")
    class PriorityAndRelocationComparisonTests {

        @Test
        @DisplayName("Catastrophe scenario (+60% rain, +40% hazard) reflects escalated priority and shelter deficits")
        void testCatastrophePriorityAndRelocationComparison() {
            ScenarioDto catastrophe = scenarioService.createScenario(new ScenarioCreateRequestDto(
                    "Extreme Catastrophe", "MULTI_FACTOR", "Testing escalation", 60.0, 40.0, 30.0
            ));

            DistrictScenarioComparisonDto result = scenarioComparisonService.compareDistrictScenario(
                    catastrophe.getScenarioId(), "Patna"
            );

            assertNotNull(result);
            assertTrue(result.getSimulatedPriorityScore() >= result.getBaselinePriorityScore());
            assertEquals("INCREASED", result.getPriorityShiftDirection());
            assertTrue(result.isPriorityEscalated());

            assertTrue(result.getSimulatedVulnerablePopulation() >= result.getBaselineVulnerablePopulation());
            assertNotNull(result.getSimulatedUnallocatedPopulation());
        }
    }

    // =========================================================================
    // 4. ZERO MUTATION & DETERMINISM TESTS
    // =========================================================================

    @Nested
    @DisplayName("4. Zero Mutation & Determinism")
    class ZeroMutationAndDeterminismTests {

        @Test
        @DisplayName("CRITICAL: Before vs After comparison does NOT mutate stored baseline or database state")
        void testZeroMutationOfBaselineAndDatabase() {
            DistrictRiskScoreDto baselineRiskBefore = riskCalculationService.getDistrictRiskScore("Sitamarhi", null);
            long districtCountBefore = districtBoundaryRepository.count();

            ScenarioDto extremeScenario = scenarioService.createScenario(new ScenarioCreateRequestDto(
                    "Extreme Catastrophe", "MULTI_FACTOR", "Testing zero mutation", 50.0, 50.0, 50.0
            ));

            DistrictScenarioComparisonDto comparison = scenarioComparisonService.compareDistrictScenario(
                    extremeScenario.getScenarioId(), "Sitamarhi"
            );

            assertNotNull(comparison);

            DistrictRiskScoreDto baselineRiskAfter = riskCalculationService.getDistrictRiskScore("Sitamarhi", null);
            long districtCountAfter = districtBoundaryRepository.count();

            assertEquals(baselineRiskBefore.getRiskScore(), baselineRiskAfter.getRiskScore(), 0.00001);
            assertEquals(baselineRiskBefore.getRiskScore100(), baselineRiskAfter.getRiskScore100(), 0.00001);
            assertEquals(districtCountBefore, districtCountAfter);
        }

        @Test
        @DisplayName("Repeated comparison executions are deterministic and independent")
        void testRepeatedComparisonDeterminism() {
            ScenarioDto scenA = scenarioService.createScenario(new ScenarioCreateRequestDto("A", "RAINFALL_CHANGE", "Desc", 20.0, 0.0, 0.0));
            ScenarioDto scenB = scenarioService.createScenario(new ScenarioCreateRequestDto("B", "POPULATION_EXPOSURE", "Desc", 0.0, 0.0, 30.0));

            DistrictScenarioComparisonDto resA1 = scenarioComparisonService.compareDistrictScenario(scenA.getScenarioId(), "Sitamarhi");
            DistrictScenarioComparisonDto resB = scenarioComparisonService.compareDistrictScenario(scenB.getScenarioId(), "Sitamarhi");
            DistrictScenarioComparisonDto resA2 = scenarioComparisonService.compareDistrictScenario(scenA.getScenarioId(), "Sitamarhi");

            assertEquals(resA1.getSimulatedRiskScore(), resA2.getSimulatedRiskScore(), 0.00001);
            assertEquals(resA1.getDeltaRiskScore(), resA2.getDeltaRiskScore(), 0.00001);
            assertEquals(resA1.getSimulatedPriorityScore(), resA2.getSimulatedPriorityScore(), 0.00001);
            assertEquals(resA1.getSimulatedAllocatedPopulation(), resA2.getSimulatedAllocatedPopulation());
        }
    }

    // =========================================================================
    // 5. VALIDATION & ERROR HANDLING TESTS
    // =========================================================================

    @Nested
    @DisplayName("5. Validation & Error Handling")
    class ValidationAndErrorHandlingTests {

        @Test
        @DisplayName("Comparing non-existent scenario throws HazardNotFoundException (404)")
        void testMissingScenarioThrows() {
            assertThrows(HazardNotFoundException.class, () ->
                    scenarioComparisonService.compareDistrictScenario("NON-EXISTENT-SCEN-999", "Sitamarhi")
            );
        }

        @Test
        @DisplayName("Comparing non-existent district throws HazardNotFoundException (404)")
        void testMissingDistrictThrows() {
            assertThrows(HazardNotFoundException.class, () ->
                    scenarioComparisonService.compareDistrictScenario(ScenarioDefinition.BASELINE_SCENARIO_ID, "AtlantisDistrict")
            );
        }

        @Test
        @DisplayName("Comparing with null or blank scenarioId throws InvalidHazardParameterException (400)")
        void testBlankScenarioIdThrows() {
            assertThrows(InvalidHazardParameterException.class, () ->
                    scenarioComparisonService.compareDistrictScenario(null, "Sitamarhi")
            );
            assertThrows(InvalidHazardParameterException.class, () ->
                    scenarioComparisonService.compareDistrictScenario("   ", "Sitamarhi")
            );
        }
    }
}
