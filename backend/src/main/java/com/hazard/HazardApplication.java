package com.hazard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ResQ — Geospatial Disaster Intelligence & Response Platform
 * SIH 2026 - Problem Statement ID: SIH26191
 *
 * Backend application entry point (modular monolith).
 */
@SpringBootApplication
public class HazardApplication {

    public static void main(String[] args) {
        SpringApplication.run(HazardApplication.class, args);
    }
}
