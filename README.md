# ResQ
### Geospatial Disaster Intelligence & Response Platform

ResQ is a disaster decision-support platform designed to help analyze hazard exposure, assess district-level risk, explore geospatial data, and plan capacity-aware relocation to candidate safe sites.

> **Purpose:** support analysis and planning. ResQ is a prototype and should not be used as the sole basis for real-world emergency decisions.

## Key capabilities

- **Hazard intelligence:** integrate and normalize hazard-related datasets for analysis.
- **Risk and vulnerability analysis:** calculate configurable risk indicators and provide explanatory context for risk contributors.
- **Geospatial exploration:** serve GIS-ready layers and GeoJSON for map-based investigation.
- **Safe-site assessment:** evaluate and rank candidate sites using available capacity and relevant criteria.
- **Relocation planning:** model population allocation to candidate sites while considering capacity constraints.
- **Scenario comparison:** compare planning scenarios to understand how assumptions affect outcomes.
- **API documentation:** explore backend endpoints through Swagger / OpenAPI.

## Technology stack

- **Backend:** Java 21, Spring Boot
- **Geospatial/data:** PostgreSQL, PostGIS, Hibernate Spatial, JTS, GeoJSON
- **Frontend:** JavaScript, HTML, CSS, Leaflet
- **API:** REST, Swagger / OpenAPI

## Quick start

From the project root, run:

```bash
./start.sh
```

Alternatively:

```bash
npm start
```

The startup script is intended to check/start PostgreSQL, launch the Spring Boot backend on port `8080`, start the frontend server on port `3000`, and open the dashboard in a browser. Press `Ctrl+C` to stop the services.

## Local endpoints

| Service | URL |
|---|---|
| Web dashboard | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Swagger / OpenAPI | http://localhost:8080/swagger-ui/index.html |
| Hazard service health check | http://localhost:8080/api/v1/hazards/health |

## Tests

Run the frontend test suite from the repository root:

```bash
npm test
```

## Important note

Risk scores, safe-site rankings, and relocation suggestions are decision-support outputs that depend on data quality, coverage, assumptions, and model configuration. Verify inputs and validate results with qualified local authorities before using them in operational settings.
