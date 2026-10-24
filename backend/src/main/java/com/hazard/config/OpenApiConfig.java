package com.hazard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI configuration for ResQ's hazard intelligence APIs.
 * Defines API metadata, contact information, license, and logical API groupings.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ResQ — Geospatial Disaster Intelligence & Response Platform (Hazard Intelligence API)")
                        .description("ResQ REST APIs for multi-source hazard observations, data cleaning and temporal aggregation, " +
                                "indicator normalization, configurable hazard-risk scoring, multi-hazard spatial-temporal analysis, " +
                                "and map-ready RFC 7946 GeoJSON GIS layers.")
                        .version("1.0.0 (Stage 3.7)")
                        .contact(new Contact()
                                .name("ResQ Project")
                                .url("https://github.com/namandeeptripathi/ResQ"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(List.of(
                        new Server().url("/").description("Local / Embedded Application Server")
                ));
    }

    @Bean
    public GroupedOpenApi hazardAllApi() {
        return GroupedOpenApi.builder()
                .group("1. All ResQ Hazard APIs")
                .pathsToMatch("/api/v1/hazards/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardLayersApi() {
        return GroupedOpenApi.builder()
                .group("2. Map Layers (GeoJSON)")
                .pathsToMatch("/api/v1/hazards/layers/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardMultiHazardApi() {
        return GroupedOpenApi.builder()
                .group("3. Multi-Hazard Intelligence")
                .pathsToMatch("/api/v1/hazards/multi-hazard/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardScoringApi() {
        return GroupedOpenApi.builder()
                .group("4. Hazard Scoring")
                .pathsToMatch("/api/v1/hazards/scores/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardNormalizationApi() {
        return GroupedOpenApi.builder()
                .group("5. Hazard Normalization")
                .pathsToMatch("/api/v1/hazards/normalized/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardProcessingApi() {
        return GroupedOpenApi.builder()
                .group("6. Hazard Processing")
                .pathsToMatch("/api/v1/hazards/processing/**")
                .build();
    }

    @Bean
    public GroupedOpenApi hazardIntegrationApi() {
        return GroupedOpenApi.builder()
                .group("7. Hazard Integration")
                .pathsToMatch("/api/v1/hazards", "/api/v1/hazards/{id}", "/api/v1/hazards/type/**",
                        "/api/v1/hazards/district/**", "/api/v1/hazards/spatial/**", "/api/v1/hazards/summary", "/api/v1/hazards/geojson")
                .build();
    }
}
