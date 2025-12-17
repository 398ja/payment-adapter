#!/bin/bash
# Restart script for dev.398ja.xyz
# Connects as nostr user, pulls new images, and restarts containers
#
# Usage: ./scripts/restart-dev.sh [service_name]
# Examples:
#   ./scripts/restart-dev.sh                    # Restart all cashu services
#   ./scripts/restart-dev.sh cashu-mint-rest    # Restart specific service
#   ./scripts/restart-dev.sh cashu-gateway-rest # Restart gateway only

set -euo pipefail

# Configuration
DEV_HOST="dev.398ja.xyz"
DEV_USER="nostr"
REMOTE_DIR="/opt/cashu-staging"
REGISTRY="docker.398ja.xyz"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Service to restart (optional, defaults to all)
SERVICE="${1:-all}"

log_info "Restart script for ${DEV_USER}@${DEV_HOST}"
log_info "Service: ${SERVICE}"
echo ""

# Prompt for password
read -s -p "Enter password for ${DEV_USER}@${DEV_HOST}: " PASSWORD
echo ""

if [[ -z "$PASSWORD" ]]; then
    log_error "Password cannot be empty"
    exit 1
fi

# Check if sshpass is available
if ! command -v sshpass &> /dev/null; then
    log_error "sshpass is required but not installed."
    log_info "Install with: sudo apt install sshpass"
    exit 1
fi

# Function to run remote commands
run_remote() {
    sshpass -p "$PASSWORD" ssh -o StrictHostKeyChecking=accept-new "${DEV_USER}@${DEV_HOST}" "$@"
}

# Step 1: Test connection
log_info "Testing SSH connection..."
if ! run_remote "echo 'Connection successful'"; then
    log_error "Failed to connect to ${DEV_HOST}"
    exit 1
fi

# Step 2: Pull latest images
log_info "Pulling latest images..."

if [[ "$SERVICE" == "all" ]]; then
    run_remote << 'REMOTE_PULL'
cd /opt/cashu-staging
docker compose -f docker-compose.staging.yml pull
REMOTE_PULL
else
    run_remote << REMOTE_PULL_SERVICE
cd /opt/cashu-staging
docker compose -f docker-compose.staging.yml pull ${SERVICE}
REMOTE_PULL_SERVICE
fi

# Step 3: Restart services
log_info "Restarting services..."

if [[ "$SERVICE" == "all" ]]; then
    run_remote << 'REMOTE_RESTART'
cd /opt/cashu-staging
docker compose -f docker-compose.staging.yml up -d --force-recreate
REMOTE_RESTART
else
    run_remote << REMOTE_RESTART_SERVICE
cd /opt/cashu-staging
docker compose -f docker-compose.staging.yml up -d --force-recreate ${SERVICE}
REMOTE_RESTART_SERVICE
fi

# Step 4: Wait and verify
log_info "Waiting for services to start..."
sleep 5

log_info "Checking service status..."
run_remote << 'REMOTE_STATUS'
cd /opt/cashu-staging
echo ""
echo "=== Service Status ==="
docker compose -f docker-compose.staging.yml ps
echo ""
echo "=== Recent Logs (last 20 lines) ==="
docker compose -f docker-compose.staging.yml logs --tail=20 2>/dev/null || true
REMOTE_STATUS

log_info "Restart complete!"
echo ""
echo "=== Access Points ==="
echo "  Dev Server:   https://${DEV_HOST}"
echo ""
echo "To check logs:"
echo "  sshpass -p 'PASSWORD' ssh ${DEV_USER}@${DEV_HOST} 'docker compose -f /opt/cashu-staging/docker-compose.staging.yml logs -f'"
