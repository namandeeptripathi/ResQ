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
                    platformSubtitle: "Geospatial Disaster Intelligence & Response Platform",
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

// Resilient Auto-bootstrap: runs immediately if DOM is already ready, or on DOMContentLoaded
if (typeof document !== "undefined") {
    const bootstrap = () => {
        try {
            const app = new App();
            app.init();
            if (typeof window !== "undefined") {
                window.__APP__ = app;
            }
        } catch (err) {
            console.error("[ResQ] Fatal bootstrap error:", err);
            const root = document.getElementById("app");
            if (root) {
                root.innerHTML = `
                    <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; min-height:100vh; font-family:var(--font-sans, sans-serif); color:#f8fafc; background:#0b132b; text-align:center; padding:2rem;">
                        <div style="font-size:3rem; margin-bottom:1rem;">⚠️</div>
                        <h1 style="font-size:1.5rem; margin-bottom:0.5rem; color:#f87171;">ResQ Application Startup Error</h1>
                        <p style="color:#94a3b8; max-width:520px; line-height:1.5; margin-bottom:1.5rem;">${err.message || "An unexpected error occurred during platform initialization."}</p>
                        <button onclick="window.location.reload()" style="background:#2563eb; color:white; border:none; padding:0.65rem 1.4rem; border-radius:6px; cursor:pointer; font-weight:600; font-size:0.95rem;">Reload Application</button>
                    </div>
                `;
            }
        }
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bootstrap);
    } else {
        bootstrap();
    }
}
