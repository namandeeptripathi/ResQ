/**
 * Stage 8B — Command Center (OverviewView) Unit Tests
 */
import assert from "node:assert";
import { OverviewView } from "../js/views/OverviewView.js";
import { MOCK_SETTLEMENTS, MOCK_SAFE_SITES, MOCK_DISTRICT_SUMMARY } from "../js/api/fixtures/mockData.js";

export async function runOverviewTests() {
    console.log("▶ Running Command Center (OverviewView) Tests...");

    const view = new OverviewView();
    view.state.settlements = MOCK_SETTLEMENTS;
    view.state.safeSites = MOCK_SAFE_SITES;
    view.state.summary = MOCK_DISTRICT_SUMMARY;
    view.state.isLoading = false;

    // Test 1: Render returns basic page shell
    const html = await view.render();
    assert.ok(html.includes("Decision Command Center"), "Overview should render Command Center title");
    assert.ok(html.includes("overview-dynamic-content"), "Overview should render dynamic content container");

    // Test 2: KPI derivations
    const highRiskCount = MOCK_SETTLEMENTS.filter(s => s.priorityLevel === "IMMEDIATE" || s.priorityLevel === "SHORT_TERM").length;
    const redZoneCount = MOCK_SETTLEMENTS.filter(s => s.isRedZone).length;
    const totalEvacuees = MOCK_SETTLEMENTS.reduce((sum, s) => sum + s.population, 0);
    const totalShelterCapacity = MOCK_SAFE_SITES.reduce((sum, s) => sum + s.totalCapacity, 0);
    const availableCapacity = MOCK_SAFE_SITES.reduce((sum, s) => sum + s.availableCapacity, 0);

    assert.strictEqual(highRiskCount, 4, "Should count 4 high-risk/immediate/short-term settlements");
    assert.strictEqual(redZoneCount, 4, "Should count 4 red-zone settlements");
    assert.strictEqual(totalEvacuees, 19400, "Total evacuee population should match 19,400");
    assert.strictEqual(totalShelterCapacity, 18500, "Total shelter capacity should match 18,500");
    assert.strictEqual(availableCapacity, 15710, "Available capacity should match 15,710");

    // Test 3: Dashboard content rendering
    const dashboardHtml = view.renderDashboardContent();
    assert.ok(dashboardHtml.includes("HIGH-RISK SETTLEMENTS"), "Should render High-Risk Settlements KPI");
    assert.ok(dashboardHtml.includes("RED-ZONE SETTLEMENTS"), "Should render Red-Zone Settlements KPI");
    assert.ok(dashboardHtml.includes("POPULATION EXPOSED"), "Should render Population Exposed KPI");
    assert.ok(dashboardHtml.includes("AVAILABLE SAFE CAPACITY"), "Should render Safe Capacity KPI");

    // Test 4: Priority settlement rows & navigation links
    assert.ok(dashboardHtml.includes("Sonbarsa Flood Inundation Area"), "Should list Sonbarsa settlement");
    assert.ok(dashboardHtml.includes("Bairgania Embankment Buffer"), "Should list Bairgania settlement");
    assert.ok(dashboardHtml.includes("#/settlements/HAB-SIT-001"), "Should include link to settlement details");
    assert.ok(dashboardHtml.includes("Sitamarhi Central Flood Shelter"), "Should show recommended destination");

    // Test 5: Operational summary grid
    assert.ok(dashboardHtml.includes("Multi-Hazard Risk Situation"), "Should render Risk Situation card");
    assert.ok(dashboardHtml.includes("Evacuation Readiness"), "Should render Evacuation Readiness card");
    assert.ok(dashboardHtml.includes("Shelter Logistics Balance"), "Should render Shelter Logistics Balance card");

    console.log("  ✅ All Command Center Tests Passed.");
}
