/**
 * ResQ — Main Application Bootstrap
 *
 * Initializes the AppShell, registers client routes, and starts navigation.
 */
import { Header } from "./components/Header.js";
import { Navigation } from "./components/Navigation.js";
import { Router } from "./router/router.js";

// View Imports
import { OverviewView } from "./views/OverviewView.js";
import { MapView } from "./views/MapView.js";
import { SafeSitesView } from "./views/SafeSitesView.js";
import { RelocationView } from "./views/RelocationView.js";
import { SettlementDetailView } from "./views/SettlementDetailView.js";
import { SimulationModal } from "./components/SimulationModal.js";

// Global What-If Simulation Trigger
if (typeof window !== "undefined") {
    window.__openSimulationModal = (district = "Sitamarhi") => {
        SimulationModal.open({ district });
    };
}

export class App {
    constructor() {
        this.router = new Router({}, { containerId: "mainContent" });
    }

    /**
     * Initializes the entire application.
     */
    init() {
        console.info("[ResQ] Initializing geospatial disaster decision-support platform...");

        // 1. Render AppShell Markup
        this.renderShell();

        // 2. Register Client Routes
        this.router
            .addRoute("/", OverviewView)
            .addRoute("/overview", OverviewView)
            .addRoute("/map", MapView)
            .addRoute("/safe-sites", SafeSitesView)
            .addRoute("/relocation", RelocationView)
            .addRoute("/settlements/:id", SettlementDetailView);

        // 3. Initialize Clock & Listeners
        Header.initClock();

        // 4. Start Router
        this.router.init();

        console.info("[ResQ] Application mounted and routing active.");
    }

    /**
     * Renders the persistent AppShell layout into the document body.
     */
    renderShell() {
        const root = document.getElementById("app");
        if (!root) {
            console.error("[ResQ] Root element #app not found in index.html.");
            return;
        }

        root.innerHTML = `
            <div class="app-shell">
                ${Header.render({
                    platformTitle: "ResQ",
                    platformSubtitle: "Geospatial Disaster Intelligence & Relocation Planning",
                    operationalMode: "PLANNING MODE"
                })}
                <div class="app-body">
                    ${Navigation.render({
                        currentPath: window.location.hash.slice(1) || "/overview",
                        currentRegion: "Sitamarhi, Bihar"
                    })}
                    <main class="app-main" id="mainScrollArea" role="main">
                        <div class="content-container" id="mainContent">
                            <!-- View dynamic content renders here -->
                        </div>
                    </main>
                </div>
            </div>
        `;
    }
}

// Auto-bootstrap when DOM is ready
document.addEventListener("DOMContentLoaded", () => {
    const app = new App();
    app.init();
    window.__APP__ = app;
});
