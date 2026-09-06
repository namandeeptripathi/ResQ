package com.hazard.service.relocation;

import com.hazard.domain.relocation.RelocationStatus;
import com.hazard.dto.relocation.*;
import com.hazard.dto.safesite.CandidateSafeSiteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 6.5 — Capacity-Aware Relocation Allocation Service.
 *
 * Deterministically allocates the vulnerable population of ONE habitation across its ranked feasible
 * relocation safe sites while strictly respecting shelter capacities:
 * - Visited in the exact priority order produced by Stage 6.4 ranking.
 * - In-memory allocation only: DOES NOT modify or mutate shared/persisted site state.
 * - Explicitly records unallocated population deficits when capacity is exhausted.
 */
@Service
public class RelocationAllocationService {

    private static final Logger log = LoggerFactory.getLogger(RelocationAllocationService.class);

    private final RelocationRankingService rankingService;

    @Autowired
    public RelocationAllocationService(RelocationRankingService rankingService) {
        this.rankingService = rankingService;
    }

    /**
     * Default constructor for isolated testing.
     */
    public RelocationAllocationService() {
        this.rankingService = new RelocationRankingService();
    }

    /**
     * Convenience method: runs the full pipeline (feasibility -> ranking -> allocation).
     */
    public RelocationPlanDto planRelocation(VulnerableHabitationDto habitation,
                                          List<CandidateSafeSiteDto> candidateSites,
                                          RelocationRequestDto request) {
        RelocationRankingResultDto rankingResult = rankingService.evaluateAndRank(habitation, candidateSites, request);
        return allocatePopulation(habitation, rankingResult);
    }

    /**
     * Convenience method: allocates population from an existing RelocationRankingResultDto.
     */
    public RelocationPlanDto allocatePopulation(VulnerableHabitationDto habitation,
                                                RelocationRankingResultDto rankingResult) {
        List<RankedRelocationSiteDto> rankedSites = (rankingResult != null && rankingResult.getRankedSites() != null)
                ? rankingResult.getRankedSites()
                : Collections.emptyList();
        return allocatePopulation(habitation, rankedSites);
    }

    /**
     * Core allocation method: allocates vulnerable population across ranked feasible sites.
     *
     * @param habitation the vulnerable origin habitation
     * @param rankedSites the feasible candidate safe sites ordered from best to worst by Stage 6.4
     * @return RelocationPlanDto representing in-memory allocation assignments, population KPIs, and status
     */
    public RelocationPlanDto allocatePopulation(VulnerableHabitationDto habitation,
                                                List<RankedRelocationSiteDto> rankedSites) {
        if (habitation == null) {
            log.warn("Cannot allocate population for null habitation");
            RelocationPlanDto empty = new RelocationPlanDto();
            empty.setPlanSummary("Null habitation provided; allocation skipped.");
            empty.setOverallStatus(RelocationStatus.PENDING);
            return empty;
        }

        long initialPopulation = resolveVulnerablePopulation(habitation);
        long remainingPopulation = initialPopulation;

        RelocationPlanDto plan = new RelocationPlanDto();
        plan.setPlanId("PLAN-" + (habitation.getHabitationId() != null ? habitation.getHabitationId() : "HAB") + "-" + System.currentTimeMillis());
        plan.setDistrict(habitation.getDistrict());
        plan.setState(habitation.getState() != null ? habitation.getState() : "Bihar");
        plan.setHazardIdentifier(habitation.getHazardIdentifier());
        plan.setHazardType(habitation.getHazardType());
        plan.setTotalHabitations(1);
        plan.setTotalVulnerablePopulation(initialPopulation);
        plan.setStrategy("RANKED_GREEDY_CAPACITY_ALLOCATION");
        plan.setGenerationTimestamp(LocalDateTime.now());

        // Edge Case: Zero or negative population
        if (initialPopulation <= 0) {
            plan.setTotalAllocatedPopulation(0L);
            plan.setTotalUnallocatedPopulation(0L);
            plan.setAllocationRatePercentage(100.0);
            plan.setOverallStatus(RelocationStatus.ALLOCATED);
            plan.setDeficitReasonCode("FULLY_ALLOCATED");
            plan.setDeficitExplanation("Zero vulnerable population requiring relocation.");
            plan.setPlanSummary(String.format("Habitation %s requires no relocation (population: %d).", habitation.getHabitationName(), initialPopulation));
            return plan;
        }

        // Edge Case: No feasible candidate sites
        if (rankedSites == null || rankedSites.isEmpty()) {
            plan.setTotalAllocatedPopulation(0L);
            plan.setTotalUnallocatedPopulation(initialPopulation);
            plan.setAllocationRatePercentage(0.0);
            plan.setOverallStatus(RelocationStatus.UNALLOCATED_NO_SAFE_SITE);
            plan.setDeficitReasonCode("NO_FEASIBLE_SITE");
            String noSiteExplanation = String.format("No feasible safe sites available within proximity for %s (%d people unallocated).",
                    habitation.getHabitationName(), initialPopulation);
            plan.setDeficitExplanation(noSiteExplanation);

            VulnerableHabitationDto unalloc = createUnallocatedDeficit(habitation, initialPopulation, RelocationStatus.UNALLOCATED_NO_SAFE_SITE,
                    "No feasible safe sites available within acceptable transit distance and suitability gates.");
            plan.getUnallocatedHabitations().add(unalloc);

            plan.setPlanSummary(String.format("Unallocated: %d people in %s could not be relocated (no feasible safe sites).",
                    initialPopulation, habitation.getHabitationName()));
            return plan;
        }

        long totalAvailableCapacity = 0;
        long totalCapacityUtilized = 0;
        int sitesUtilized = 0;
        List<RelocationAssignmentDto> assignments = new ArrayList<>();

        // Deterministic Allocation Loop: Visit ranked sites best-to-worst
        for (RankedRelocationSiteDto site : rankedSites) {
            if (site == null) continue;

            Integer siteAvail = site.getAvailableCapacity();
            if (siteAvail != null && siteAvail > 0) {
                totalAvailableCapacity += siteAvail;
            }

            // If remaining population is already zero, continue tracking available capacity
            if (remainingPopulation <= 0) {
                continue;
            }

            // Determine allocation amount
            long toAllocate = 0;
            if (siteAvail != null) {
                if (siteAvail > 0) {
                    toAllocate = Math.min(remainingPopulation, (long) siteAvail);
                }
            } else {
                // Unbounded capacity (null in source data): accommodates all remaining population
                toAllocate = remainingPopulation;
            }

            if (toAllocate > 0) {
                remainingPopulation -= toAllocate;
                totalCapacityUtilized += toAllocate;
                sitesUtilized++;

                RelocationStatus assignmentStatus = (remainingPopulation == 0 && (initialPopulation - remainingPopulation) == initialPopulation)
                        ? RelocationStatus.ALLOCATED
                        : RelocationStatus.PARTIALLY_ALLOCATED;

                String reason = String.format(
                        "Allocated %d people to Rank %d safe site '%s' [%s] (Site Available Cap: %s, Transit Dist: %s)",
                        toAllocate,
                        site.getRank(),
                        site.getSiteName(),
                        site.getSiteId(),
                        site.getAvailableCapacity() != null ? site.getAvailableCapacity().toString() : "Unbounded",
                        site.getDistanceKilometers() != null ? String.format("%.2f km", site.getDistanceKilometers()) : "N/A"
                );

                RelocationAssignmentDto assignment = new RelocationAssignmentDto(
                        habitation, site, toAllocate, site.getDistanceMeters(), assignmentStatus, reason
                );
                assignments.add(assignment);
            }
        }

        long totalAllocated = initialPopulation - remainingPopulation;
        plan.setTotalAllocatedPopulation(totalAllocated);
        plan.setTotalUnallocatedPopulation(remainingPopulation);
        plan.setTotalCandidateSitesEvaluated(rankedSites.size());
        plan.setTotalCandidateSitesUtilized(sitesUtilized);
        plan.setTotalCapacityAvailable((int) Math.min((long) Integer.MAX_VALUE, totalAvailableCapacity));
        plan.setTotalCapacityUtilized((int) Math.min((long) Integer.MAX_VALUE, totalCapacityUtilized));
        plan.setAssignments(assignments);

        if (initialPopulation > 0) {
            double allocRate = ((double) totalAllocated / initialPopulation) * 100.0;
            plan.setAllocationRatePercentage(Math.round(allocRate * 100.0) / 100.0);
        } else {
            plan.setAllocationRatePercentage(100.0);
        }

        if (totalAvailableCapacity > 0) {
            double capUtil = ((double) totalCapacityUtilized / totalAvailableCapacity) * 100.0;
            plan.setCapacityUtilizationPercentage(Math.round(capUtil * 100.0) / 100.0);
        } else {
            plan.setCapacityUtilizationPercentage(0.0);
        }

        // Finalize Status & Unallocated Deficit Records
        if (remainingPopulation == 0) {
            plan.setOverallStatus(RelocationStatus.ALLOCATED);
            plan.setDeficitReasonCode("FULLY_ALLOCATED");
            plan.setDeficitExplanation(null);
            plan.setPlanSummary(String.format(
                    "Fully Allocated: All %d vulnerable people from %s accommodated across %d safe site(s).",
                    totalAllocated, habitation.getHabitationName(), sitesUtilized
            ));
        } else if (totalAllocated > 0) {
            plan.setOverallStatus(RelocationStatus.PARTIALLY_ALLOCATED);
            plan.setDeficitReasonCode("PARTIAL_CAPACITY");
            String deficitReason = String.format(
                    "Capacity deficit: %d of %d vulnerable people accommodated; %d unallocated due to exhausted shelter capacity across %d site(s).",
                    totalAllocated, initialPopulation, remainingPopulation, rankedSites.size()
            );
            plan.setDeficitExplanation(deficitReason);
            VulnerableHabitationDto unalloc = createUnallocatedDeficit(habitation, remainingPopulation, RelocationStatus.PARTIALLY_ALLOCATED, deficitReason);
            plan.getUnallocatedHabitations().add(unalloc);
            plan.setPlanSummary(deficitReason);
        } else {
            plan.setOverallStatus(RelocationStatus.UNALLOCATED_CAPACITY_EXCEEDED);
            plan.setDeficitReasonCode("CAPACITY_EXHAUSTED");
            String deficitReason = String.format(
                    "Capacity exceeded: 0 of %d people allocated because all %d candidate safe sites have zero available shelter capacity.",
                    initialPopulation, rankedSites.size()
            );
            plan.setDeficitExplanation(deficitReason);
            VulnerableHabitationDto unalloc = createUnallocatedDeficit(habitation, remainingPopulation, RelocationStatus.UNALLOCATED_CAPACITY_EXCEEDED, deficitReason);
            plan.getUnallocatedHabitations().add(unalloc);
            plan.setPlanSummary(deficitReason);
        }

        log.info(plan.getPlanSummary());
        return plan;
    }

    private VulnerableHabitationDto createUnallocatedDeficit(VulnerableHabitationDto origin, long deficitCount, RelocationStatus status, String reason) {
        VulnerableHabitationDto deficit = new VulnerableHabitationDto();
        deficit.setHabitationId(origin.getHabitationId());
        deficit.setHabitationName(origin.getHabitationName());
        deficit.setHabitationType(origin.getHabitationType());
        deficit.setDistrict(origin.getDistrict());
        deficit.setState(origin.getState());
        deficit.setLatitude(origin.getLatitude());
        deficit.setLongitude(origin.getLongitude());
        deficit.setTotalPopulation(origin.getTotalPopulation());
        deficit.setVulnerablePopulation(deficitCount);
        deficit.setUrgency(origin.getUrgency());
        deficit.setHazardIdentifier(origin.getHazardIdentifier());
        deficit.setHazardType(origin.getHazardType());
        deficit.setRelocationStatus(status);
        deficit.setStatusReason(reason);
        return deficit;
    }

    private long resolveVulnerablePopulation(VulnerableHabitationDto habitation) {
        if (habitation.getVulnerablePopulation() != null && habitation.getVulnerablePopulation() > 0) {
            return habitation.getVulnerablePopulation();
        }
        if (habitation.getTotalPopulation() != null && habitation.getTotalPopulation() > 0) {
            return habitation.getTotalPopulation();
        }
        return 0L;
    }
}
