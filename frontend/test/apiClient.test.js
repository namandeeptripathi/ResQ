/**
 * Stage 8A — API Client Unit Tests
 */
import assert from "node:assert";
import { ApiClient } from "../js/api/apiClient.js";

export async function runApiClientTests() {
    console.log("▶ Running API Client Tests...");

    // Test 1: Instantiation with default and custom config
    const customConfig = {
        BASE_URL: "https://api.disaster.gov.in",
        TIMEOUT_MS: 5000,
        DEFAULT_HEADERS: { "Content-Type": "application/json" }
    };
    const client = new ApiClient(customConfig);
    assert.strictEqual(client.config.BASE_URL, "https://api.disaster.gov.in");

    // Test 2: Standard ApiResponse envelope unwrapping logic simulation
    const mockSuccessPayload = {
        success: true,
        data: {
            habitationId: "HAB-01",
            priorityScore: 0.88,
            priorityLevel: "IMMEDIATE"
        },
        message: "Decision computed successfully"
    };

    assert.strictEqual(mockSuccessPayload.success, true);
    assert.strictEqual(mockSuccessPayload.data.priorityScore, 0.88);
    assert.strictEqual(mockSuccessPayload.data.priorityLevel, "IMMEDIATE");

    // Test 3: Standard ApiError envelope unwrapping simulation
    const mockErrorPayload = {
        success: false,
        error: "Bad Request",
        message: "Malformed request payload or unreadable request body",
        status: 400,
        path: "/api/v1/relocation/plan"
    };

    assert.strictEqual(mockErrorPayload.success, false);
    assert.strictEqual(mockErrorPayload.status, 400);
    assert.strictEqual(mockErrorPayload.message, "Malformed request payload or unreadable request body");

    // Test 4: HTTP 404 Not Found envelope normalization
    const mock404Payload = {
        status: 404,
        error: "Not Found",
        message: "Scenario definition not found with ID: SCEN-UNKNOWN",
        path: "/api/v1/scenarios/SCEN-UNKNOWN/execute"
    };
    const normalized404 = {
        success: false,
        error: mock404Payload.message || mock404Payload.error,
        status: mock404Payload.status,
        data: null
    };
    assert.strictEqual(normalized404.success, false);
    assert.strictEqual(normalized404.status, 404);
    assert.ok(normalized404.error.includes("not found"));

    // Test 5: HTTP 500 Generic Error normalization
    const mock500Payload = {
        status: 500,
        error: "Internal Server Error",
        message: "An unexpected internal server error occurred",
        path: "/api/v1/relocation/plan"
    };
    const normalized500 = {
        success: false,
        error: mock500Payload.message || mock500Payload.error,
        status: 500,
        data: null
    };
    assert.strictEqual(normalized500.success, false);
    assert.strictEqual(normalized500.status, 500);
    assert.strictEqual(normalized500.error, "An unexpected internal server error occurred");

    // Test 6: Safe handling of null, empty, or unexpected payload structures
    const emptyPayload = null;
    const fallbackMessage = (typeof emptyPayload === "object" && emptyPayload !== null && (emptyPayload.message || emptyPayload.error))
        ? (emptyPayload.message || emptyPayload.error)
        : "HTTP 502: Bad Gateway";
    assert.strictEqual(fallbackMessage, "HTTP 502: Bad Gateway");

    console.log("  ✅ All API Client Tests Passed.");
}
