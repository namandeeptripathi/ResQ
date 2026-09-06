package com.hazard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazard.domain.relocation.PriorityLevel;
import com.hazard.domain.relocation.RelocationStatus;
import com.hazard.domain.relocation.RelocationUrgency;
import com.hazard.domain.safesite.CandidateSiteCategory;
import com.hazard.domain.safesite.HazardSafetyStatus;
import com.hazard.domain.safesite.SuitabilityClass;
import com.hazard.domain.scenario.ScenarioDefinition;
import com.hazard.dto.relocation.*;
import com.hazard.dto.safesite.CandidateSafeSiteDto;
import com.hazard.dto.scenario.ScenarioExecutionRequestDto;
import com.hazard.service.relocation.PriorityClassificationEngine;
import com.hazard.service.relocation.PriorityScoringEngine;
import com.hazard.service.relocation.RelocationAllocationService;
import com.hazard.service.relocation.RelocationFeasibilityService;
import com.hazard.service.relocation.RelocationRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 10 — Batch 3: Reliability & Edge Case Automated Verification Suite.
 *
 * Covers:
 * 1. Capacity Testing (Cases A-F + Multi-Settlement Destination Competition)
 * 2. Failure Handling & HTTP Error Envelopes (Malformed JSON, Missing Body, Unsupported Methods)
 * 3. Missing-Data & Degradation Handling (Null Coordinates, Null Capacities, Missing Risk Scores)
 * 4. Numeric & Boundary Edge Cases (0, Negative, NaN, Extreme Multipliers)
 * 5. State Consistency & Repeatability Under Failure
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional(readOnly = true)
public class HazardReliabilityAndEdgeCaseTests {

    private static final Logger log = LoggerFactory.getLogger(HazardReliabilityAndEdgeCaseTests.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private RelocationAllocationService allocationService;
    private PriorityScoringEngine priorityScoringEngine;
    private PriorityClassificationEngine classificationEngine;

    @BeforeEach
    void setUp() {
        RelocationFeasibilityService feasibilityService = new RelocationFeasibilityService();
        RelocationRankingService rankingService = new RelocationRankingService(feasibilityService);
        allocationService = new RelocationAllocationService(rankingService);
        classificationEngine = new PriorityClassificationEngine();
        priorityScoringEngine = new PriorityScoringEngine();
    }

    private VulnerableHabitationDto createHabitation(String id, String name, double lat, double lon, long pop) {
        VulnerableHabitationDto hab = new VulnerableHabitationDto();
        hab.setHabitationId(id);
        hab.setHabitationName(name);
        hab.setDistrict("Sitamarhi");
        hab.setState("Bihar");
        hab.setLatitude(lat);
        hab.setLongitude(lon);
        hab.setVulnerablePopulation(pop);
        hab.setTotalPopulation(pop);
        hab.setUrgency(RelocationUrgency.CRITICAL);
        return hab;
    }

    private RankedRelocationSiteDto createRankedSite(int rank, String id, String name,
                                                     SuitabilityClass suitability, double score,
                                                     double distanceMeters, Integer capacity, Integer occupied) {
        SiteFeasibilityEvaluationDto eval = new SiteFeasibilityEvaluationDto();
        eval.setSiteId(id);
        eval.setSiteName(name);
        eval.setCategory("EMERGENCY_SHELTER");
        eval.setDistrict("Sitamarhi");
        eval.setDestinationLatitude(26.6500);
        eval.setDestinationLongitude(85.5200);
        eval.setSuitabilityClass(suitability);
        eval.setSuitabilityScore(score);
        eval.setTransitDistanceMeters(distanceMeters);
        eval.setDistanceAvailable(true);
        eval.setTotalCapacity(capacity);
        eval.setAllocatedOccupancy(occupied != null ? occupied : 0);
        eval.setAvailableCapacity(capacity != null ? Math.max(0, capacity - (occupied != null ? occupied : 0)) : null);

        RankedRelocationSiteDto ranked = new RankedRelocationSiteDto(rank, eval, 10);
        ranked.setRank(rank);
        return ranked;
    }

    // =========================================================================
    // STEP 1: CAPACITY TESTING (CASES A - F + MULTI-SETTLEMENT COMPETITION)
    // =========================================================================

    @Nested
    @DisplayName("Step 1: Capacity & Allocation Stress Tests")
    class CapacityTests {

        @Test
        @DisplayName("Case A — Plenty of capacity (Population << Available Capacity)")
        void testCaseAPlentyCapacity() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Small Hamlet", 26.59, 85.50, 50);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Large Stadium",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 3000.0, 5000, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1));

            assertEquals(50L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(100.0, plan.getAllocationRatePercentage());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertEquals(1, plan.getAssignments().size());
            assertEquals(50L, plan.getAssignments().get(0).getAllocatedPopulation());
            assertEquals(0L, plan.getDeficitPopulation());
            assertTrue(plan.isFullyAllocated());
        }

        @Test
        @DisplayName("Case B — Exact capacity (Population == Available Capacity)")
        void testCaseBExactCapacity() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Standard Village", 26.59, 85.50, 250);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Community Hall",
                    SuitabilityClass.HIGHLY_SUITABLE, 90.0, 4000.0, 250, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1));

            assertEquals(250L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(100.0, plan.getAllocationRatePercentage());
            assertEquals(100.0, plan.getCapacityUtilizationPercentage());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertTrue(plan.isFullyAllocated());
        }

        @Test
        @DisplayName("Case C — Insufficient capacity (Population > Total Available Capacity)")
        void testCaseCInsufficientCapacity() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Dense Basti", 26.59, 85.50, 500);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Shelter A",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 2000.0, 200, 0);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S-2", "Shelter B",
                    SuitabilityClass.SUITABLE, 80.0, 4000.0, 150, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1, s2));

            assertEquals(350L, plan.getTotalAllocatedPopulation());
            assertEquals(150L, plan.getTotalUnallocatedPopulation());
            assertEquals(70.0, plan.getAllocationRatePercentage(), 0.01);
            assertEquals(RelocationStatus.PARTIALLY_ALLOCATED, plan.getOverallStatus());
            assertEquals("PARTIAL_CAPACITY", plan.getDeficitReasonCode());
            assertEquals(150L, plan.getDeficitPopulation());
            assertEquals(1, plan.getUnallocatedHabitations().size());
            assertEquals(150L, plan.getUnallocatedHabitations().get(0).getVulnerablePopulation());
            assertTrue(plan.hasDeficit());
        }

        @Test
        @DisplayName("Case D — Zero capacity (All candidate destinations have zero available capacity)")
        void testCaseDZeroCapacity() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Evacuee Group", 26.59, 85.50, 300);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Full School",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 2000.0, 100, 100);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S-2", "Full Hall",
                    SuitabilityClass.SUITABLE, 85.0, 3000.0, 200, 200);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1, s2));

            assertEquals(0L, plan.getTotalAllocatedPopulation());
            assertEquals(300L, plan.getTotalUnallocatedPopulation());
            assertEquals(0.0, plan.getAllocationRatePercentage());
            assertEquals(RelocationStatus.UNALLOCATED_CAPACITY_EXCEEDED, plan.getOverallStatus());
            assertEquals("CAPACITY_EXHAUSTED", plan.getDeficitReasonCode());
            assertTrue(plan.getAssignments().isEmpty());
            assertEquals(1, plan.getUnallocatedHabitations().size());
            assertEquals(300L, plan.getUnallocatedHabitations().get(0).getVulnerablePopulation());
        }

        @Test
        @DisplayName("Case E — Multiple destinations (Population distributed across 4 ranked safe sites)")
        void testCaseEMultipleDestinations() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Displaced Ward", 26.59, 85.50, 450);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Site 1", SuitabilityClass.HIGHLY_SUITABLE, 95.0, 2000.0, 150, 0);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S-2", "Site 2", SuitabilityClass.SUITABLE, 85.0, 3000.0, 150, 0);
            RankedRelocationSiteDto s3 = createRankedSite(3, "S-3", "Site 3", SuitabilityClass.SUITABLE, 80.0, 4000.0, 100, 0);
            RankedRelocationSiteDto s4 = createRankedSite(4, "S-4", "Site 4", SuitabilityClass.MARGINAL, 65.0, 5000.0, 200, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1, s2, s3, s4));

            assertEquals(450L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertEquals(4, plan.getAssignments().size());

            assertEquals("S-1", plan.getAssignments().get(0).getDestinationSiteId());
            assertEquals(150L, plan.getAssignments().get(0).getAllocatedPopulation());

            assertEquals("S-2", plan.getAssignments().get(1).getDestinationSiteId());
            assertEquals(150L, plan.getAssignments().get(1).getAllocatedPopulation());

            assertEquals("S-3", plan.getAssignments().get(2).getDestinationSiteId());
            assertEquals(100L, plan.getAssignments().get(2).getAllocatedPopulation());

            assertEquals("S-4", plan.getAssignments().get(3).getDestinationSiteId());
            assertEquals(50L, plan.getAssignments().get(3).getAllocatedPopulation());
        }

        @Test
        @DisplayName("Case F — Large input & integer overflow resilience (Population = 5,000,000)")
        void testCaseFLargeInputAndOverflowResilience() {
            VulnerableHabitationDto hab = createHabitation("H-MEGA", "Megacity Evacuation", 26.59, 85.50, 5_000_000L);

            // Large capacities that exceed 32-bit signed int when summed
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-MEGA-1", "Mega Base 1",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 10000.0, 2_000_000, 0);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S-MEGA-2", "Mega Base 2",
                    SuitabilityClass.SUITABLE, 85.0, 15000.0, 2_000_000, 0);
            RankedRelocationSiteDto s3 = createRankedSite(3, "S-MEGA-3", "Mega Base 3",
                    SuitabilityClass.SUITABLE, 80.0, 20000.0, 2_000_000, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(s1, s2, s3));

            assertNotNull(plan);
            assertEquals(5_000_000L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(100.0, plan.getAllocationRatePercentage());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertTrue(plan.getTotalCapacityAvailable() > 0, "Total available capacity must be positive (no overflow)");
            assertTrue(plan.getTotalCapacityUtilized() > 0, "Total capacity utilized must be positive (no overflow)");
            assertTrue(plan.getCapacityUtilizationPercentage() > 0.0);
            assertEquals(3, plan.getAssignments().size());
            assertEquals(2_000_000L, plan.getAssignments().get(0).getAllocatedPopulation());
            assertEquals(2_000_000L, plan.getAssignments().get(1).getAllocatedPopulation());
            assertEquals(1_000_000L, plan.getAssignments().get(2).getAllocatedPopulation());
        }

        @Test
        @DisplayName("Case G — Multi-settlement competition for shared destination preserves capacity")
        void testMultiSettlementCompetitionForSharedDestination() {
            // Destination S1 has capacity 100
            RankedRelocationSiteDto s1 = createRankedSite(1, "S1", "Shared Shelter",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 3000.0, 100, 0);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S2", "Spillover Shelter",
                    SuitabilityClass.SUITABLE, 85.0, 5000.0, 80, 0);

            // Settlement 1 (pop 70) arrives first
            VulnerableHabitationDto hab1 = createHabitation("H1", "Settlement A", 26.59, 85.50, 70);
            RelocationPlanDto plan1 = allocationService.allocatePopulation(hab1, List.of(s1, s2));

            assertEquals(70L, plan1.getTotalAllocatedPopulation());
            assertEquals(RelocationStatus.ALLOCATED, plan1.getOverallStatus());
            assertEquals(1, plan1.getAssignments().size());
            assertEquals(70L, plan1.getAssignments().get(0).getAllocatedPopulation());

            // Update remaining capacity on shared site for Settlement 2 (occupied = 70, remaining = 30)
            RankedRelocationSiteDto s1Remaining = createRankedSite(1, "S1", "Shared Shelter",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 3000.0, 100, 70);
            assertEquals(30, s1Remaining.getAvailableCapacity());

            // Settlement 2 (pop 60) arrives next -> allocates remaining 30 from S1, 30 from S2
            VulnerableHabitationDto hab2 = createHabitation("H2", "Settlement B", 26.58, 85.51, 60);
            RelocationPlanDto plan2 = allocationService.allocatePopulation(hab2, List.of(s1Remaining, s2));

            assertEquals(60L, plan2.getTotalAllocatedPopulation());
            assertEquals(0L, plan2.getTotalUnallocatedPopulation());
            assertEquals(RelocationStatus.ALLOCATED, plan2.getOverallStatus());
            assertEquals(2, plan2.getAssignments().size());
            assertEquals("S1", plan2.getAssignments().get(0).getDestinationSiteId());
            assertEquals(30L, plan2.getAssignments().get(0).getAllocatedPopulation());
            assertEquals("S2", plan2.getAssignments().get(1).getDestinationSiteId());
            assertEquals(30L, plan2.getAssignments().get(1).getAllocatedPopulation());

            // Verify total allocated to S1 across both settlements strictly equals S1 total capacity
            long totalAllocatedToS1 = plan1.getAssignments().get(0).getAllocatedPopulation()
                    + plan2.getAssignments().get(0).getAllocatedPopulation();
            assertEquals(100L, totalAllocatedToS1);
        }
    }

    // =========================================================================
    // STEP 2: FAILURE HANDLING & HTTP ERROR RESPONSES
    // =========================================================================

    @Nested
    @DisplayName("Step 2: Failure Handling & HTTP Status Envelopes")
    class FailureHandlingTests {

        @Test
        @DisplayName("Malformed JSON payload returns HTTP 400 Bad Request (not 500)")
        void testMalformedJsonPayloadReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/relocation/plan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"district\": \"Sitamarhi\", \"broken\": }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message", containsString("Malformed request payload")))
                    .andExpect(jsonPath("$.path").value("/api/v1/relocation/plan"));
        }

        @Test
        @DisplayName("Empty request body returns HTTP 400 Bad Request (not 500)")
        void testEmptyRequestBodyReturns400() throws Exception {
            mockMvc.perform(post("/api/v1/relocation/plan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message", containsString("unreadable request body")));
        }

        @Test
        @DisplayName("Non-existent scenario ID returns HTTP 404 Not Found")
        void testNonExistentScenarioReturns404() throws Exception {
            ScenarioExecutionRequestDto req = new ScenarioExecutionRequestDto("Sitamarhi");
            mockMvc.perform(post("/api/v1/scenarios/NON-EXISTENT-XYZ-9999/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message", containsString("not found")));
        }

        @Test
        @DisplayName("Non-existent district in scenario execution returns HTTP 404 Not Found")
        void testNonExistentDistrictReturns404() throws Exception {
            ScenarioExecutionRequestDto req = new ScenarioExecutionRequestDto("ImaginaryDistrict99");
            mockMvc.perform(post("/api/v1/scenarios/" + ScenarioDefinition.BASELINE_SCENARIO_ID + "/execute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Unsupported HTTP method returns HTTP 405 Method Not Allowed")
        void testUnsupportedMethodReturns405() throws Exception {
            mockMvc.perform(delete("/api/v1/relocation/plan"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                    .andExpect(jsonPath("$.message", containsString("not supported")));
        }

        @Test
        @DisplayName("Unsupported Content-Type returns HTTP 415 Unsupported Media Type")
        void testUnsupportedContentTypeReturns415() throws Exception {
            mockMvc.perform(post("/api/v1/relocation/plan")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Sitamarhi 200"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.status").value(415))
                    .andExpect(jsonPath("$.error").value("Unsupported Media Type"));
        }

        @Test
        @DisplayName("Invalid parameter value returns HTTP 400 Bad Request")
        void testInvalidParameterReturns400() throws Exception {
            mockMvc.perform(get("/api/v1/safe-sites").param("hazardSafety", "INVALID_SAFETY_VALUE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message", containsString("Allowed values")));
        }
    }

    // =========================================================================
    // STEP 3: MISSING-DATA HANDLING
    // =========================================================================

    @Nested
    @DisplayName("Step 3: Missing-Data & Degradation Tests")
    class MissingDataTests {

        @Test
        @DisplayName("Unbounded safe-site capacity (null) accommodates entire remaining population")
        void testUnboundedCapacityAccommodatesAll() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Village", 26.59, 85.50, 1500);
            RankedRelocationSiteDto unbounded = createRankedSite(1, "S-OPEN", "Open Public Grounds",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 3000.0, null, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(unbounded));

            assertNotNull(plan);
            assertEquals(1500L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertEquals(1, plan.getAssignments().size());
            assertEquals(1500L, plan.getAssignments().get(0).getAllocatedPopulation());
        }

        @Test
        @DisplayName("Null habitation to allocation service returns pending empty plan safely")
        void testNullHabitationAllocationSafelyHandled() {
            RankedRelocationSiteDto site = createRankedSite(1, "S-1", "Site", SuitabilityClass.SUITABLE, 80.0, 3000.0, 100, 0);
            RelocationPlanDto plan = allocationService.allocatePopulation(null, List.of(site));

            assertNotNull(plan);
            assertEquals(RelocationStatus.PENDING, plan.getOverallStatus());
            assertTrue(plan.getPlanSummary().contains("Null habitation"));
        }

        @Test
        @DisplayName("Empty candidate sites list returns UNALLOCATED_NO_SAFE_SITE with complete deficit")
        void testEmptyCandidateListHandledSafely() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Remote Hamlet", 26.59, 85.50, 200);
            RelocationPlanDto plan = allocationService.allocatePopulation(hab, Collections.emptyList());

            assertNotNull(plan);
            assertEquals(0L, plan.getTotalAllocatedPopulation());
            assertEquals(200L, plan.getTotalUnallocatedPopulation());
            assertEquals(RelocationStatus.UNALLOCATED_NO_SAFE_SITE, plan.getOverallStatus());
            assertEquals("NO_FEASIBLE_SITE", plan.getDeficitReasonCode());
            assertTrue(plan.getAssignments().isEmpty());
            assertEquals(1, plan.getUnallocatedHabitations().size());
        }

        @Test
        @DisplayName("Missing risk severity and hazard severity scores default to 0.0 in PriorityScoringEngine")
        void testMissingScoresHandledConservatively() {
            VulnerableHabitationDto hab = new VulnerableHabitationDto();
            hab.setHabitationId("H-NULL-SCORES");
            hab.setHabitationName("Unassessed Habitation");
            hab.setRiskScore(null);
            hab.setHazardSeverityScore(null);
            hab.setVulnerablePopulation(100L);
            hab.setUrgency(RelocationUrgency.LOW);

            double riskNorm = priorityScoringEngine.normalizeRiskSeverity(hab);
            double hazardNorm = priorityScoringEngine.normalizeHazardSeverity(hab);

            assertEquals(0.0, riskNorm, "Missing risk score must default to 0.0");
            assertEquals(0.0, hazardNorm, "Missing hazard score must default to 0.0");

            RelocationPriorityResultDto result = priorityScoringEngine.score(null, hab);
            assertNotNull(result);
            assertFalse(Double.isNaN(result.getPriorityScore()));
            assertTrue(result.getPriorityScore() >= 0.0 && result.getPriorityScore() <= 1.0);
        }

        @Test
        @DisplayName("Candidate safe site with negative available capacity in input is ignored safely")
        void testNegativeCapacityInInputIgnored() {
            VulnerableHabitationDto hab = createHabitation("H-1", "Village", 26.59, 85.50, 100);

            // Faulty input site with negative available capacity (-20)
            RankedRelocationSiteDto faultySite = createRankedSite(1, "S-NEG", "Faulty Site",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 2000.0, 50, 70); // 50 - 70 = -20

            // Normal site with capacity 100
            RankedRelocationSiteDto goodSite = createRankedSite(2, "S-GOOD", "Good Site",
                    SuitabilityClass.SUITABLE, 80.0, 4000.0, 100, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(faultySite, goodSite));

            assertEquals(100L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertEquals(1, plan.getAssignments().size());
            assertEquals("S-GOOD", plan.getAssignments().get(0).getDestinationSiteId());
            assertTrue(plan.getTotalCapacityAvailable() >= 0, "Total available capacity must never be negative");
        }
    }

    // =========================================================================
    // STEP 4: EDGE CASE TESTING (NUMERIC BOUNDARIES & COLLECTIONS)
    // =========================================================================

    @Nested
    @DisplayName("Step 4: Numeric & Boundary Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Zero population requires 0 allocation and returns ALLOCATED cleanly")
        void testZeroPopulationHandledCleanly() {
            VulnerableHabitationDto hab = createHabitation("H-ZERO", "Zero Habitation", 26.59, 85.50, 0L);
            RankedRelocationSiteDto site = createRankedSite(1, "S-1", "Site", SuitabilityClass.SUITABLE, 80.0, 3000.0, 100, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(site));

            assertEquals(0L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(100.0, plan.getAllocationRatePercentage());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
            assertTrue(plan.getAssignments().isEmpty());
        }

        @Test
        @DisplayName("Negative population requires 0 allocation and returns ALLOCATED cleanly")
        void testNegativePopulationHandledCleanly() {
            VulnerableHabitationDto hab = createHabitation("H-NEG", "Negative Pop Habitation", 26.59, 85.50, -50L);
            RankedRelocationSiteDto site = createRankedSite(1, "S-1", "Site", SuitabilityClass.SUITABLE, 80.0, 3000.0, 100, 0);

            RelocationPlanDto plan = allocationService.allocatePopulation(hab, List.of(site));

            assertEquals(0L, plan.getTotalAllocatedPopulation());
            assertEquals(0L, plan.getTotalUnallocatedPopulation());
            assertEquals(100.0, plan.getAllocationRatePercentage());
            assertEquals(RelocationStatus.ALLOCATED, plan.getOverallStatus());
        }

        @Test
        @DisplayName("PriorityClassificationEngine: NaN and negative scores classify to MONITORING")
        void testPriorityClassificationBoundaryEdgeCases() {
            assertEquals(PriorityLevel.MONITORING, classificationEngine.classify(Double.NaN));
            assertEquals(PriorityLevel.MONITORING, classificationEngine.classify(-0.5));
            assertEquals(PriorityLevel.MONITORING, classificationEngine.classify(-0.0001));
            assertEquals(PriorityLevel.MONITORING, classificationEngine.classify(0.0));
        }

        @Test
        @DisplayName("PriorityClassificationEngine: Strict boundary threshold tests")
        void testPriorityClassificationThresholdBoundaries() {
            // Immediate threshold is 0.70
            assertEquals(PriorityLevel.IMMEDIATE, classificationEngine.classify(0.70));
            assertEquals(PriorityLevel.IMMEDIATE, classificationEngine.classify(0.7001));
            assertEquals(PriorityLevel.SHORT_TERM, classificationEngine.classify(0.6999));

            // Short term threshold is 0.40
            assertEquals(PriorityLevel.SHORT_TERM, classificationEngine.classify(0.40));
            assertEquals(PriorityLevel.SHORT_TERM, classificationEngine.classify(0.4001));
            assertEquals(PriorityLevel.MEDIUM_TERM, classificationEngine.classify(0.3999));

            // Medium term threshold is 0.15
            assertEquals(PriorityLevel.MEDIUM_TERM, classificationEngine.classify(0.15));
            assertEquals(PriorityLevel.MEDIUM_TERM, classificationEngine.classify(0.1501));
            assertEquals(PriorityLevel.MONITORING, classificationEngine.classify(0.1499));
        }
    }

    // =========================================================================
    // STEP 6 & 7: STATE CONSISTENCY & REPEATABILITY UNDER FAILURE
    // =========================================================================

    @Nested
    @DisplayName("Step 6 & 7: State Consistency & Deterministic Repeatability")
    class StateAndRepeatabilityTests {

        @Test
        @DisplayName("Repeatability: Running allocation 10 consecutive times produces identical results")
        void testAllocationDeterministicRepeatability() {
            VulnerableHabitationDto hab = createHabitation("H-REPEAT", "Repeat Village", 26.59, 85.50, 175);
            RankedRelocationSiteDto s1 = createRankedSite(1, "S-1", "Site 1", SuitabilityClass.HIGHLY_SUITABLE, 95.0, 2000.0, 100, 0);
            RankedRelocationSiteDto s2 = createRankedSite(2, "S-2", "Site 2", SuitabilityClass.SUITABLE, 80.0, 4000.0, 100, 0);

            RelocationPlanDto baseline = allocationService.allocatePopulation(hab, List.of(s1, s2));

            for (int i = 0; i < 10; i++) {
                RelocationPlanDto run = allocationService.allocatePopulation(hab, List.of(s1, s2));

                assertEquals(baseline.getTotalAllocatedPopulation(), run.getTotalAllocatedPopulation());
                assertEquals(baseline.getTotalUnallocatedPopulation(), run.getTotalUnallocatedPopulation());
                assertEquals(baseline.getOverallStatus(), run.getOverallStatus());
                assertEquals(baseline.getAllocationRatePercentage(), run.getAllocationRatePercentage());
                assertEquals(baseline.getCapacityUtilizationPercentage(), run.getCapacityUtilizationPercentage());
                assertEquals(baseline.getAssignments().size(), run.getAssignments().size());
                assertEquals(baseline.getAssignments().get(0).getAllocatedPopulation(),
                        run.getAssignments().get(0).getAllocatedPopulation());
                assertEquals(baseline.getAssignments().get(1).getAllocatedPopulation(),
                        run.getAssignments().get(1).getAllocatedPopulation());
            }
        }

        @Test
        @DisplayName("Zero mutation: Source safe sites and habitations remain unmutated across allocations")
        void testSourceDataZeroMutation() {
            VulnerableHabitationDto hab = createHabitation("H-MUT", "Mutation Test Village", 26.59, 85.50, 150);
            RankedRelocationSiteDto site = createRankedSite(1, "S-MUT", "Original Site",
                    SuitabilityClass.HIGHLY_SUITABLE, 95.0, 3000.0, 300, 50);

            allocationService.allocatePopulation(hab, List.of(site));

            // Verify site state was untouched
            assertEquals(300, site.getTotalCapacity());
            assertEquals(50, site.getAllocatedOccupancy());
            assertEquals(250, site.getAvailableCapacity());

            // Verify habitation state was untouched
            assertEquals(150L, hab.getVulnerablePopulation());
            assertEquals(150L, hab.getTotalPopulation());
            assertEquals("Mutation Test Village", hab.getHabitationName());
        }
    }
}
