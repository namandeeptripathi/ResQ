/**
 * ResQ — Header Component
 *
 * Top navigation bar featuring the product mark, platform title, status badge, and clock.
 */
export class Header {
    /**
     * Renders the Header HTML string.
     *
     * @param {object} props
     * @param {string} props.platformTitle - Main platform title
     * @param {string} props.platformSubtitle - Subtitle / platform descriptor
     * @param {string} props.operationalMode - Current application mode
     * @returns {string} HTML markup
     */
    static render(props = {}) {
        const title = props.platformTitle || "ResQ";
        const subtitle = props.platformSubtitle || "Geospatial Disaster Intelligence & Relocation Planning";
        const mode = props.operationalMode || "PLANNING MODE";

        return `
            <header class="app-header" role="banner">
                <div class="header-brand">
                    <div class="header-emblem" aria-hidden="true">RQ</div>
                    <div class="header-title-group">
                        <div class="header-platform-title">${title}</div>
                        <div class="header-platform-subtitle">${subtitle}</div>
                    </div>
                </div>

                <div class="header-actions">
                    <div class="header-status-pill" title="Current Application Mode">
                        <span class="header-pulse-dot" aria-hidden="true"></span>
                        <span>${mode}</span>
                    </div>
                    <div id="liveClock" class="header-clock" aria-label="System Time">--:--:-- IST</div>
                </div>
            </header>
        `;
    }

    /**
     * Initializes live clock updates.
     */
    static initClock() {
        const clockEl = document.getElementById("liveClock");
        if (!clockEl) return;

        const updateTime = () => {
            const now = new Date();
            const timeStr = now.toTimeString().split(" ")[0] + " IST";
            clockEl.textContent = timeStr;
        };

        updateTime();
        setInterval(updateTime, 1000);
    }
}
