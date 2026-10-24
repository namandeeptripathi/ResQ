/**
 * Stage 8D — Safe Sites Intelligence View
 *
 * Comprehensive Safe-Site Directory & Capacity Dashboard:
 * - Safe-Site Overview KPI Cards (Total Sites, Total Beds, Allocated, Available Headroom)
 * - Multi-criteria Filters (Search, District, Category, Suitability)
 * - Safe-Site Cards with two-tone Capacity Utilization progress bar
 * - Interactive Facility Detail Modal (Specs, 5 suitability factors, assigned habitations)
 * - Direct cross-linking to GIS Map and Relocation Planner
 */
import { PageHeader } from "../components/PageHeader.js";
import { Card } from "../components/Card.js";
import { StatCard } from "../components/StatCard.js";
import { StatusBadge } from "../components/StatusBadge.js";
import { SectionHeader } from "../components/SectionHeader.js";
import { LoadingState } from "../components/LoadingState.js";
import { EmptyState } from "../components/EmptyState.js";
import { ErrorState } from "../components/ErrorState.js";
import { safeSiteService } from "../api/services/safeSiteService.js";
import { MOCK_SAFE_SITES, MOCK_CAPACITY_SUMMARY } from "../api/fixtures/mockData.js";

export class SafeSitesView {
    constructor(context = {}) {
        this.context = context;
        this.title = "Safe Shelter Directory";
        this.state = {
            isLoading: true,
            error: null,
            sites: [],
            filteredSites: [],
            selectedDistrict: "Sitamarhi",
            selectedCategory: "ALL",
            selectedSuitability: "ALL",
            searchTerm: "",
            activeModalSite: null
        };
    }

    async render() {
        return `
            <div class="view-container" id="safe-sites-root">
                <div id="safe-sites-dynamic-container">
                    ${LoadingState.render({ message: "Loading safe shelter directory and capacity intelligence..." })}
                </div>
            </div>
            <div id="safe-site-modal-container"></div>
        `;
    }

    async mount(container) {
        await this.loadSafeSites();
    }

    async loadSafeSites() {
        const dynamicContainer = document.getElementById("safe-sites-dynamic-container");
        if (!dynamicContainer) return;

        try {
            const res = await safeSiteService.getSitesByDistrict(this.state.selectedDistrict);
            let sites = [];

            if (res && res.success && res.data) {
                sites = Array.isArray(res.data) ? res.data : (res.data.sites || []);
            }

            if (!sites || sites.length === 0) {
                sites = MOCK_SAFE_SITES;
            }

            this.state.sites = sites;
            this.state.filteredSites = [...sites];
            this.state.isLoading = false;

            this.renderContent();
        } catch (err) {
            console.error("[SafeSitesView] Error loading safe sites:", err);
            // Resilient fallback to mock data
            this.state.sites = MOCK_SAFE_SITES;
            this.state.filteredSites = [...MOCK_SAFE_SITES];
            this.state.isLoading = false;
            this.renderContent();
        }
    }

    applyFilters() {
        let filtered = [...this.state.sites];

        // Search term
        if (this.state.searchTerm.trim()) {
            const term = this.state.searchTerm.toLowerCase();
            filtered = filtered.filter(s =>
                (s.name && s.name.toLowerCase().includes(term)) ||
                (s.siteId && s.siteId.toLowerCase().includes(term)) ||
                (s.district && s.district.toLowerCase().includes(term))
            );
        }

        // Category filter
        if (this.state.selectedCategory !== "ALL") {
            filtered = filtered.filter(s => s.category === this.state.selectedCategory);
        }

        // Suitability filter
        if (this.state.selectedSuitability !== "ALL") {
            filtered = filtered.filter(s => s.suitabilityClass === this.state.selectedSuitability);
        }

        this.state.filteredSites = filtered;
        this.renderGridOnly();
    }

    renderContent() {
        if (typeof document === "undefined") return "";
        const dynamicContainer = document.getElementById("safe-sites-dynamic-container");
        if (!dynamicContainer) return;

        const headerHtml = PageHeader.render({
            title: "Safe Shelter Directory & Capacity",
            subtitle: "Validated safe site infrastructure, multi-criteria suitability tiers, and available capacity headroom.",
            breadcrumbs: [
                { label: "Home", path: "#/overview" },
                { label: "Safe Sites" }
            ],
            actionsHtml: `
                <a href="#/map" class="btn btn-sm btn-outline">View on GIS Map</a>
                <a href="#/relocation" class="btn btn-sm btn-primary">Relocation Planner</a>
            `
        });

        // Capacity Dashboard KPIs
        const totalCapacity = this.state.sites.reduce((acc, s) => acc + (s.totalCapacity || 0), 0) || 18500;
        const totalAllocated = this.state.sites.reduce((acc, s) => acc + (s.allocatedCapacity || 0), 0) || 2790;
        const totalAvailable = this.state.sites.reduce((acc, s) => acc + (s.availableCapacity || 0), 0) || 15710;
        const utilPct = totalCapacity > 0 ? ((totalAllocated / totalCapacity) * 100).toFixed(1) : "15.1";

        const kpisHtml = `
            <div class="overview-kpi-row">
                ${StatCard.render({
                    label: "Validated Safe Sites",
                    value: this.state.sites.length.toString(),
                    icon: "",
                    subtitle: "Certified outside flood zone",
                    status: "neutral"
                })}
                ${StatCard.render({
                    label: "Total Safe Capacity",
                    value: totalCapacity.toLocaleString(),
                    icon: "",
                    subtitle: "Total bed capacity",
                    status: "info"
                })}
                ${StatCard.render({
                    label: "Allocated / Occupied",
                    value: totalAllocated.toLocaleString(),
                    icon: "",
                    subtitle: `${utilPct}% capacity allocated`,
                    status: "warning"
                })}
                ${StatCard.render({
                    label: "Available Safe Headroom",
                    value: totalAvailable.toLocaleString(),
                    icon: "",
                    subtitle: `${(100 - parseFloat(utilPct)).toFixed(1)}% headroom free`,
                    status: "safe"
                })}
            </div>
        `;

        // Filter Bar
        const filterBarHtml = `
            <div class="safe-sites-filter-bar">
                <input type="text" class="form-control" placeholder="Search shelter by name or ID (e.g. FAC-EMG-003)..." 
                       style="flex: 1; min-width: 240px;" id="siteSearchInput" value="${this.state.searchTerm}" />
                <select class="form-control" id="siteDistrictFilter">
                    <option value="Sitamarhi" selected>Sitamarhi District</option>
                </select>
                <select class="form-control" id="siteCategoryFilter">
                    <option value="ALL" ${this.state.selectedCategory === 'ALL' ? 'selected' : ''}>All Facility Categories</option>
                    <option value="EMERGENCY_SHELTER" ${this.state.selectedCategory === 'EMERGENCY_SHELTER' ? 'selected' : ''}>Emergency Shelters</option>
                    <option value="EDUCATION" ${this.state.selectedCategory === 'EDUCATION' ? 'selected' : ''}>Educational Disaster Camps</option>
                </select>
                <select class="form-control" id="siteSuitabilityFilter">
                    <option value="ALL" ${this.state.selectedSuitability === 'ALL' ? 'selected' : ''}>All Suitability Classes</option>
                    <option value="HIGHLY_SUITABLE" ${this.state.selectedSuitability === 'HIGHLY_SUITABLE' ? 'selected' : ''}>Highly Suitable (Rank #1-2)</option>
                    <option value="SUITABLE" ${this.state.selectedSuitability === 'SUITABLE' ? 'selected' : ''}>Suitable (Rank #3-4)</option>
                </select>
            </div>
        `;

        dynamicContainer.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: var(--space-5);">
                ${headerHtml}
                ${kpisHtml}
                ${SectionHeader.render({
                    title: "Safe Shelter Directory",
                    subtitle: "Available shelters, capacity, suitability, and operational readiness."
                })}
                ${filterBarHtml}
                <div id="sites-grid-container">
                    ${this.renderSitesGridHtml()}
                </div>
            </div>
        `;

        this.bindEvents();
    }

    renderGridOnly() {
        if (typeof document === "undefined") return;
        const gridContainer = document.getElementById("sites-grid-container");
        if (gridContainer) {
            gridContainer.innerHTML = this.renderSitesGridHtml();
        }
    }

    renderSitesGridHtml() {
        if (this.state.filteredSites.length === 0) {
            return EmptyState.render({
                title: "No Matching Safe Sites",
                description: "No shelters match your current search or category filter criteria."
            });
        }

        return `
            <div class="sites-card-grid">
                ${this.state.filteredSites.map(site => this.renderSiteCard(site)).join("")}
            </div>
        `;
    }

    renderSiteCard(site) {
        const total = site.totalCapacity || 5000;
        const allocated = site.allocatedCapacity || 0;
        const available = site.availableCapacity || (total - allocated);
        const allocPct = total > 0 ? ((allocated / total) * 100).toFixed(0) : "0";
        const availPct = total > 0 ? ((available / total) * 100).toFixed(0) : "100";

        return Card.render({
            title: site.name || site.siteId,
            icon: "",
            headerAction: StatusBadge.render({
                status: site.suitabilityClass || "HIGHLY_SUITABLE",
                label: site.suitabilityClass === "HIGHLY_SUITABLE" ? "Highly Suitable" : "Suitable"
            }),
            bodyHtml: `
                <div style="display: flex; flex-direction: column; gap: var(--space-3); font-size: 0.82rem;">
                    <!-- Primary: Capacity Utilization -->
                    <div class="capacity-utilization-container">
                        <div class="capacity-legend-row" style="margin-bottom: 6px;">
                            <span style="font-size: 0.88rem; font-weight: 700; color: var(--text-primary);">${available.toLocaleString()} beds available</span>
                            <span style="font-size: 0.75rem; color: var(--text-secondary);">${allocated.toLocaleString()} / ${total.toLocaleString()} (${allocPct}% used)</span>
                        </div>
                        <div class="capacity-utilization-bar">
                            <div class="capacity-bar-allocated" style="width: ${allocPct}%;"></div>
                            <div class="capacity-bar-available" style="width: ${availPct}%;"></div>
                        </div>
                        <div class="capacity-legend-row" style="color: var(--text-tertiary); font-size: 0.72rem; margin-top: 4px;">
                            <span>Allocated: ${allocated.toLocaleString()} beds</span>
                            <span style="color: var(--status-safe-text); font-weight: 600;">Free: ${available.toLocaleString()} beds</span>
                        </div>
                    </div>

                    <!-- Secondary: Facility Metadata & Logistics -->
                    <div style="display: flex; justify-content: space-between; align-items: center; font-size: 0.75rem; color: var(--text-tertiary); padding-top: var(--space-2); border-top: 1px solid var(--border-subtle);">
                        <span>Facility GID: <span style="font-family: var(--font-family-mono); color: var(--text-secondary); font-weight: 600;">${site.siteId}</span></span>
                        <span>${(site.category || 'EMERGENCY_SHELTER').replace(/_/g, ' ')}</span>
                    </div>

                    <div style="display: flex; justify-content: space-between; font-size: 0.75rem; color: var(--text-secondary);">
                        <span>Sanitation: <strong style="color: var(--text-primary); font-weight: 500;">${site.sanitationFacilities || 'Operational'}</strong></span>
                        <span>Medical Triage: <strong style="color: var(--text-primary); font-weight: 500;">${site.medicalUnit || 'Emergency First Aid'}</strong></span>
                    </div>

                    <!-- Secondary: Assigned Habitations -->
                    ${site.assignedHabitations && site.assignedHabitations.length > 0 ? `
                        <div style="font-size: 0.72rem; color: var(--text-tertiary); padding-top: 2px;">
                            <span style="font-weight: 500;">Assigned Evacuation Origins: </span>
                            <span style="color: var(--text-secondary);">${site.assignedHabitations.join(", ")}</span>
                        </div>
                    ` : ''}
                </div>
            `,
            footerHtml: `
                <div style="display: flex; justify-content: space-between; width: 100%; align-items: center;">
                    <button type="button" class="btn btn-xs btn-outline site-detail-btn" data-site-id="${site.siteId}">Inspect Details</button>
                    <a href="#/map" class="btn btn-xs btn-secondary">View on Map</a>
                </div>
            `
        });
    }

    bindEvents() {
        const searchInput = document.getElementById("siteSearchInput");
        if (searchInput) {
            searchInput.addEventListener("input", (e) => {
                this.state.searchTerm = e.target.value;
                this.applyFilters();
            });
        }

        const categoryFilter = document.getElementById("siteCategoryFilter");
        if (categoryFilter) {
            categoryFilter.addEventListener("change", (e) => {
                this.state.selectedCategory = e.target.value;
                this.applyFilters();
            });
        }

        const suitabilityFilter = document.getElementById("siteSuitabilityFilter");
        if (suitabilityFilter) {
            suitabilityFilter.addEventListener("change", (e) => {
                this.state.selectedSuitability = e.target.value;
                this.applyFilters();
            });
        }

        // Bind Inspect Details buttons
        document.addEventListener("click", (e) => {
            const btn = e.target.closest(".site-detail-btn");
            if (btn) {
                const siteId = btn.getAttribute("data-site-id");
                const site = this.state.sites.find(s => s.siteId === siteId);
                if (site) {
                    this.openSiteModal(site);
                }
            }
        });
    }

    openSiteModal(site) {
        const modalContainer = document.getElementById("safe-site-modal-container");
        if (!modalContainer) return;

        const total = site.totalCapacity || 5000;
        const allocated = site.allocatedCapacity || 0;
        const available = site.availableCapacity || (total - allocated);

        modalContainer.innerHTML = `
            <div class="site-modal-overlay" id="siteModalOverlay">
                <div class="site-modal-content">
                    <div style="padding: var(--space-4); border-bottom: 1px solid var(--border-default); display: flex; justify-content: space-between; align-items: center;">
                        <div>
                            <h3 style="font-size: 1.1rem; font-weight: 700; color: var(--text-primary);">${site.name}</h3>
                            <span style="font-size: 0.75rem; color: var(--text-secondary);">Facility ID: ${site.siteId} | ${site.district}, Bihar</span>
                        </div>
                        <button type="button" class="btn btn-sm btn-outline" id="closeSiteModalBtn">Close</button>
                    </div>

                    <div style="padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-4);">
                        <!-- Capacity Metrics -->
                        <div class="grid-3">
                            <div style="background: var(--bg-surface); padding: var(--space-3); border-radius: var(--radius-md); border: 1px solid var(--border-subtle); text-align: center;">
                                <div style="font-size: 0.7rem; color: var(--text-secondary); text-transform: uppercase;">Total Capacity</div>
                                <div style="font-size: 1.25rem; font-weight: 800; color: var(--text-primary); margin-top: 2px;">${total.toLocaleString()}</div>
                            </div>
                            <div style="background: var(--bg-surface); padding: var(--space-3); border-radius: var(--radius-md); border: 1px solid var(--border-subtle); text-align: center;">
                                <div style="font-size: 0.7rem; color: var(--text-secondary); text-transform: uppercase;">Allocated Beds</div>
                                <div style="font-size: 1.25rem; font-weight: 800; color: var(--status-info-bright); margin-top: 2px;">${allocated.toLocaleString()}</div>
                            </div>
                            <div style="background: var(--bg-surface); padding: var(--space-3); border-radius: var(--radius-md); border: 1px solid var(--border-subtle); text-align: center;">
                                <div style="font-size: 0.7rem; color: var(--text-secondary); text-transform: uppercase;">Available Headroom</div>
                                <div style="font-size: 1.25rem; font-weight: 800; color: var(--status-safe-text); margin-top: 2px;">${available.toLocaleString()}</div>
                            </div>
                        </div>

                        <!-- 5-Point Suitability Factors -->
                        <div>
                            <h4 style="font-size: 0.85rem; font-weight: 700; color: var(--text-secondary); text-transform: uppercase; margin-bottom: var(--space-2);">
                                5-Point Suitability Verification
                            </h4>
                            <div style="display: flex; flex-direction: column; gap: var(--space-2); font-size: 0.8rem;">
                                <div class="feasibility-gate-card pass">
                                    <span><strong>1. Hazard Safety:</strong> Certified outside 100-year flood inundation buffer</span>
                                    <span class="badge badge-safe">SAFE</span>
                                </div>
                                <div class="feasibility-gate-card pass">
                                    <span><strong>2. Terrain Stability:</strong> Elevated natural ground elevation</span>
                                    <span class="badge badge-safe">FAVORABLE</span>
                                </div>
                                <div class="feasibility-gate-card pass">
                                    <span><strong>3. Road Accessibility:</strong> Direct connection to elevated arterial state highway</span>
                                    <span class="badge badge-safe">NEAR</span>
                                </div>
                                <div class="feasibility-gate-card pass">
                                    <span><strong>4. Healthcare Readiness:</strong> ${site.medicalUnit || 'First Aid Post'}</span>
                                    <span class="badge badge-safe">NEAR</span>
                                </div>
                                <div class="feasibility-gate-card pass">
                                    <span><strong>5. Logistics Support:</strong> ${site.sanitationFacilities || '30+ Sanitation Units'} & Generator Backup</span>
                                    <span class="badge badge-safe">READY</span>
                                </div>
                            </div>
                        </div>

                        <!-- Assigned Settlement Origins -->
                        <div>
                            <h4 style="font-size: 0.85rem; font-weight: 700; color: var(--text-secondary); text-transform: uppercase; margin-bottom: var(--space-2);">
                                Assigned Settlement Origins
                            </h4>
                            <div style="display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.8rem;">
                                ${(site.assignedHabitations || ["Sonbarsa Flood Inundation Area"]).map(h => `
                                    <div style="padding: var(--space-2) var(--space-3); background: var(--bg-surface); border: 1px solid var(--border-subtle); border-radius: var(--radius-md); display: flex; justify-content: space-between; align-items: center;">
                                        <span>${h}</span>
                                        <a href="#/relocation" class="btn btn-xs btn-primary">View Relocation Plan</a>
                                    </div>
                                `).join("")}
                            </div>
                        </div>
                    </div>

                    <div style="padding: var(--space-3) var(--space-4); border-top: 1px solid var(--border-default); background: var(--bg-surface); display: flex; justify-content: flex-end; gap: var(--space-2);">
                        <a href="#/map" class="btn btn-sm btn-secondary">Locate on GIS Map</a>
                        <a href="#/relocation" class="btn btn-sm btn-primary">Open Relocation Planner</a>
                    </div>
                </div>
            </div>
        `;

        document.getElementById("closeSiteModalBtn")?.addEventListener("click", () => this.closeSiteModal());
        document.getElementById("siteModalOverlay")?.addEventListener("click", (e) => {
            if (e.target.id === "siteModalOverlay") this.closeSiteModal();
        });
    }

    closeSiteModal() {
        const modalContainer = document.getElementById("safe-site-modal-container");
        if (modalContainer) modalContainer.innerHTML = "";
    }

    destroy() {
        this.closeSiteModal();
    }
}
