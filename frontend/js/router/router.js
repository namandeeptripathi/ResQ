/**
 * ResQ — Client-Side Hash Router
 *
 * Lightweight, robust routing engine supporting:
 * - Hash-based navigation (#/overview, #/map, #/safe-sites, #/relocation, #/settlements/:id)
 * - Dynamic route parameters (e.g. :id)
 * - Query parameter parsing
 * - Active view rendering lifecycle (mount, destroy)
 * - Document title management
 * - 404 fallback
 */
import { Navigation } from "../components/Navigation.js";

export class Router {
    constructor(routes = {}, options = {}) {
        this.routes = routes;
        this.containerId = options.containerId || "mainContent";
        this.currentRoute = null;
        this.currentParams = {};
        this.currentQuery = {};
        this.currentViewInstance = null;

        // Bind handler
        this.handleHashChange = this.handleHashChange.bind(this);
    }

    /**
     * Registers a route path and view handler class.
     *
     * @param {string} pathPattern - e.g. "/overview" or "/settlements/:id"
     * @param {class|Function} viewClass - View component with render() and optional mount()
     */
    addRoute(pathPattern, viewClass) {
        this.routes[pathPattern] = viewClass;
        return this;
    }

    /**
     * Starts listening to hashchange events and triggers initial navigation.
     */
    init() {
        window.addEventListener("hashchange", this.handleHashChange);
        this.handleHashChange();
    }

    /**
     * Destroys listeners.
     */
    destroy() {
        window.removeEventListener("hashchange", this.handleHashChange);
    }

    /**
     * Programmatically navigates to a route.
     *
     * @param {string} path - Target path (e.g. "/map")
     */
    navigate(path) {
        if (typeof window !== "undefined" && window.location) {
            window.location.hash = path.startsWith("#") ? path : `#${path}`;
        }
    }

    /**
     * Parses the current window hash into route path, params, and query.
     */
    parseHash() {
        const rawHash = (typeof window !== "undefined" && window.location && window.location.hash)
            ? window.location.hash.slice(1) || "/overview"
            : "/overview";
        const [pathWithParams, queryString] = rawHash.split("?");

        // Parse query params
        const query = {};
        if (queryString) {
            const searchParams = new URLSearchParams(queryString);
            for (const [key, val] of searchParams.entries()) {
                query[key] = val;
            }
        }

        const path = pathWithParams.startsWith("/") ? pathWithParams : `/${pathWithParams}`;
        return { path, query };
    }

    /**
     * Finds a matching route for the current path.
     */
    matchRoute(path) {
        // 1. Direct exact match
        if (this.routes[path]) {
            return {
                pattern: path,
                viewClass: this.routes[path],
                params: {}
            };
        }

        // 2. Parameterized pattern matching (e.g. /settlements/:id)
        const pathSegments = path.split("/").filter(Boolean);

        for (const [pattern, viewClass] of Object.entries(this.routes)) {
            const patternSegments = pattern.split("/").filter(Boolean);

            if (patternSegments.length !== pathSegments.length) continue;

            const params = {};
            let isMatch = true;

            for (let i = 0; i < patternSegments.length; i++) {
                const pSeg = patternSegments[i];
                const uSeg = pathSegments[i];

                if (pSeg.startsWith(":")) {
                    const paramName = pSeg.slice(1);
                    params[paramName] = decodeURIComponent(uSeg);
                } else if (pSeg !== uSeg) {
                    isMatch = false;
                    break;
                }
            }

            if (isMatch) {
                return { pattern, viewClass, params };
            }
        }

        // 3. Fallback to /overview or first registered route
        return null;
    }

    /**
     * Main hash change handler.
     */
    async handleHashChange() {
        const { path, query } = this.parseHash();
        const matched = this.matchRoute(path);

        const container = document.getElementById(this.containerId);
        if (!container) {
            console.error(`[Router] Container element #${this.containerId} not found in DOM.`);
            return;
        }

        // Cleanup previous view if needed
        if (this.currentViewInstance && typeof this.currentViewInstance.destroy === "function") {
            try {
                this.currentViewInstance.destroy();
            } catch (err) {
                console.warn("[Router] Error destroying previous view:", err);
            }
        }

        if (!matched) {
            console.warn(`[Router] Route not found: ${path}. Redirecting to /overview`);
            this.navigate("/overview");
            return;
        }

        this.currentRoute = matched.pattern;
        this.currentParams = matched.params;
        this.currentQuery = query;

        // Update active class in the sidebar
        Navigation.updateActiveLink(matched.pattern);

        // Render matched view
        try {
            const ViewClass = matched.viewClass;
            const viewInstance = new ViewClass({
                params: matched.params,
                query: query,
                router: this
            });

            this.currentViewInstance = viewInstance;

            // Render HTML
            const html = await viewInstance.render();
            container.innerHTML = html;

            // Trigger mount lifecycle
            if (typeof viewInstance.mount === "function") {
                await viewInstance.mount(container);
            }

            // Keep browser tab title aligned with the ResQ brand
            const titleSuffix = viewInstance.title ? ` | ${viewInstance.title}` : "";
            document.title = `ResQ${titleSuffix}`;

        } catch (err) {
            console.error(`[Router] Error rendering route ${path}:`, err);
            container.innerHTML = `
                <div class="view-container">
                    <div class="error-state" role="alert">
                        <div class="error-state-icon">⚠️</div>
                        <div class="error-state-content">
                            <div class="error-state-title">View Render Error</div>
                            <div class="error-state-message">${err.message || "Failed to render view."}</div>
                        </div>
                    </div>
                </div>
            `;
        }
    }
}
