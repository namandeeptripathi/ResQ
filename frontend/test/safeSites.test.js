/**
 * Stage 8D — Safe Sites Intelligence (SafeSitesView) Unit Tests
 */
import assert from "node:assert";
import { SafeSitesView } from "../js/views/SafeSitesView.js";
import { MOCK_SAFE_SITES } from "../js/api/fixtures/mockData.js";

export async function runSafeSitesTests() {
    console.log("▶ Running Safe Sites Intelligence (SafeSitesView) Tests...");

    const view = new SafeSitesView();
    view.state.sites = MOCK_SAFE_SITES;
    view.state.filteredSites = [...MOCK_SAFE_SITES];

    // Test 1: Render returns skeleton & modal container
    const initialHtml = await view.render();
    assert.ok(initialHtml.includes("safe-sites-root"), "SafeSitesView should render root container");
    assert.ok(initialHtml.includes("safe-site-modal-container"), "SafeSitesView should render modal container");

    // Test 2: Content generation & KPIs
    view.renderContent();
    const dynamicEl = { innerHTML: "" };
    // Verify math derivations
    const totalCapacity = view.state.sites.reduce((acc, s) => acc + (s.totalCapacity || 0), 0);
    const totalAllocated = view.state.sites.reduce((acc, s) => acc + (s.allocatedCapacity || 0), 0);
    const totalAvailable = view.state.sites.reduce((acc, s) => acc + (s.availableCapacity || 0), 0);

    assert.strictEqual(totalCapacity, 18500, "Total safe capacity across Sitamarhi should equal 18,500 beds");
    assert.strictEqual(totalAllocated, 2790, "Total allocated capacity should equal 2,790 beds");
    assert.strictEqual(totalAvailable, 15710, "Total available headroom should equal 15,710 beds");

    // Test 3: Grid rendering
    const gridHtml = view.renderSitesGridHtml();
    assert.ok(gridHtml.includes("Sitamarhi Central Flood Shelter"), "Grid should contain Central Flood Shelter");
    assert.ok(gridHtml.includes("FAC-EMG-003"), "Grid should contain facility ID FAC-EMG-003");
    assert.ok(gridHtml.includes("capacity-utilization-bar"), "Grid should render two-tone capacity bar");
    assert.ok(gridHtml.includes("Pupri Cyclone & Flood Relief Centre"), "Grid should contain Pupri Relief Centre");

    // Test 4: Filters
    view.state.selectedCategory = "EMERGENCY_SHELTER";
    view.applyFilters();
    assert.strictEqual(view.state.filteredSites.length, 2, "Filtering by EMERGENCY_SHELTER should return 2 sites");

    view.state.selectedCategory = "ALL";
    view.state.searchTerm = "Dumra";
    view.applyFilters();
    assert.strictEqual(view.state.filteredSites.length, 1, "Searching 'Dumra' should return 1 site");
    assert.strictEqual(view.state.filteredSites[0].siteId, "FAC-EMG-008");

    console.log("  ✅ All Safe Sites Intelligence Tests Passed.");
}
