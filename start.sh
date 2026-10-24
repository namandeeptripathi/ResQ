#!/usr/bin/env bash

# ==============================================================================
# ResQ — One-Command Local Launcher
# Starts PostgreSQL check, Spring Boot backend (port 8080), and frontend (port 3000)
# ==============================================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIR="$PROJECT_ROOT/frontend"

BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
    echo ""
    echo "🛑 Shutting down ResQ services..."
    if [ -n "$FRONTEND_PID" ]; then
        kill "$FRONTEND_PID" 2>/dev/null || true
    fi
    if [ -n "$BACKEND_PID" ]; then
        kill "$BACKEND_PID" 2>/dev/null || true
    fi
    echo "✅ All services stopped."
    exit 0
}

trap cleanup SIGINT SIGTERM EXIT

echo "======================================================="
echo " 🚀 Starting ResQ Local Environment"
echo "======================================================="

# 1. Check PostgreSQL
echo "🔍 [1/3] Checking PostgreSQL service..."
if ! pg_isready -q 2>/dev/null; then
    echo "⚡ PostgreSQL is not running. Attempting to start via Homebrew..."
    brew services start postgresql@17 || brew services start postgresql || true
    sleep 2
fi

if pg_isready -q 2>/dev/null; then
    echo "   ✅ PostgreSQL is active and ready."
else
    echo "   ⚠️  Could not verify PostgreSQL with pg_isready. Proceeding with backend launch..."
fi

# 2. Start Spring Boot Backend
echo "⚙️  [2/3] Starting Spring Boot Backend (Port 8080)..."
(cd "$BACKEND_DIR" && mvn spring-boot:run -Dspring-boot.run.profiles=default > "$PROJECT_ROOT/backend.log" 2>&1) &
BACKEND_PID=$!

echo "   ⏳ Waiting for Backend to be healthy on http://localhost:8080..."
MAX_RETRIES=40
RETRIES=0
BACKEND_READY=false

while [ $RETRIES -lt $MAX_RETRIES ]; do
    if curl -s http://localhost:8080/actuator/health >/dev/null 2>&1 || curl -s http://localhost:8080/api/v1/hazards/health >/dev/null 2>&1; then
        BACKEND_READY=true
        break
    fi
    sleep 1
    RETRIES=$((RETRIES + 1))
    printf "."
done
echo ""

if [ "$BACKEND_READY" = true ]; then
    echo "   ✅ Backend is UP and listening on http://localhost:8080"
else
    echo "   ⚠️ Backend is still initializing or logged a notice (logs at backend.log)."
fi

# 3. Start Frontend Server
echo "🌐 [3/3] Starting Frontend Web Server on http://localhost:3000..."
(cd "$FRONTEND_DIR" && python3 -m http.server 3000 > "$PROJECT_ROOT/frontend.log" 2>&1) &
FRONTEND_PID=$!

sleep 1
echo "   ✅ Frontend is UP on http://localhost:3000"

echo ""
echo "======================================================="
echo " 🎉 ResQ is running!"
echo " 👉 Web UI:       http://localhost:3000"
echo " 👉 Backend API:  http://localhost:8080"
echo " 👉 Swagger Docs: http://localhost:8080/swagger-ui/index.html"
echo "======================================================="
echo "💡 Press Ctrl+C at any time to stop all services."
echo ""

# Automatically open browser on macOS
if command -v open >/dev/null 2>&1; then
    open "http://localhost:3000"
fi

# Keep script running and wait for background jobs
wait "$BACKEND_PID" "$FRONTEND_PID"
