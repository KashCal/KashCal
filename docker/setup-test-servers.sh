#!/bin/bash
# Setup script for CalDAV test servers
# Usage: ./docker/setup-test-servers.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== KashCal CalDAV Test Server Setup ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Start containers
echo -e "${YELLOW}Starting Docker containers...${NC}"
cd "$SCRIPT_DIR"
docker compose down -v 2>/dev/null || true
docker compose up -d

# Wait for containers to be healthy
echo -e "${YELLOW}Waiting for containers to be ready...${NC}"

wait_for_service() {
    local name=$1
    local url=$2
    local max_attempts=${3:-30}
    local attempt=1

    echo -n "  Waiting for $name"
    while [ $attempt -le $max_attempts ]; do
        if curl -sf "$url" > /dev/null 2>&1; then
            echo -e " ${GREEN}ready${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    echo -e " ${RED}timeout${NC}"
    return 1
}

# Wait for each service
wait_for_service "Nextcloud" "http://localhost:8080/status.php" 60
wait_for_service "Radicale" "http://localhost:5232/.web/" 30
wait_for_service "Baikal" "http://localhost:8081/" 30
wait_for_service "Stalwart" "http://localhost:8082/" 60

echo ""
echo -e "${YELLOW}Setting up Nextcloud users...${NC}"

# Create Nextcloud users using OCC command
create_nextcloud_user() {
    local username=$1
    local password=$2

    # Check if user exists
    if docker exec kashcal-nextcloud php occ user:info "$username" > /dev/null 2>&1; then
        echo "  User $username already exists"
    else
        echo "  Creating user $username..."
        docker exec kashcal-nextcloud php occ user:add --password-from-env "$username" <<< "$password" 2>/dev/null || \
        docker exec -e OC_PASS="$password" kashcal-nextcloud php occ user:add --password-from-env "$username"
    fi
}

# Wait a bit more for Nextcloud to fully initialize
sleep 5

create_nextcloud_user "testuser1" "testpass1"
create_nextcloud_user "testuser2" "testpass2"
create_nextcloud_user "testuser3" "testpass3"
create_nextcloud_user "testuser4" "testpass4"

echo ""
echo -e "${YELLOW}Setting up Baikal...${NC}"
echo "  Baikal requires manual setup via web interface at http://localhost:8081/admin/"
echo "  Default admin: admin / admin (set during first run)"

echo ""
echo -e "${YELLOW}Setting up Stalwart...${NC}"
echo "  Creating Stalwart accounts via management API..."

# Stalwart user setup via management API
setup_stalwart_user() {
    local username=$1
    local password=$2

    curl -sf -X POST "http://localhost:8082/api/account" \
        -u "admin:adminpass" \
        -H "Content-Type: application/json" \
        -d "{\"name\": \"$username\", \"secret\": \"$password\", \"type\": \"individual\"}" \
        > /dev/null 2>&1 || echo "  Note: Stalwart user $username may already exist"
}

setup_stalwart_user "testuser1" "testpass1"
setup_stalwart_user "testuser2" "testpass2"
setup_stalwart_user "testuser3" "testpass3"
setup_stalwart_user "testuser4" "testpass4"

echo ""
echo -e "${GREEN}=== Test Server URLs ===${NC}"
echo ""
echo "Nextcloud:"
echo "  URL: http://localhost:8080"
echo "  CalDAV: http://localhost:8080/remote.php/dav/"
echo "  Users: testuser1/testpass1, testuser2/testpass2, testuser3/testpass3, testuser4/testpass4"
echo ""
echo "Radicale:"
echo "  URL: http://localhost:5232"
echo "  CalDAV: http://localhost:5232/"
echo "  Users: testuser1/testpass1, testuser2/testpass2, testuser3/testpass3, testuser4/testpass4"
echo ""
echo "Baikal:"
echo "  URL: http://localhost:8081"
echo "  CalDAV: http://localhost:8081/dav.php/"
echo "  Admin: http://localhost:8081/admin/ (requires manual setup)"
echo ""
echo "Stalwart:"
echo "  URL: http://localhost:8082"
echo "  CalDAV: http://localhost:8082/dav/"
echo "  Users: testuser1/testpass1, testuser2/testpass2, testuser3/testpass3, testuser4/testpass4"
echo ""

# Update local.properties with test credentials
echo -e "${YELLOW}Updating local.properties with test credentials...${NC}"

update_local_properties() {
    local file="$PROJECT_ROOT/local.properties"
    local temp_file="$file.tmp"

    # Create backup
    cp "$file" "$file.bak" 2>/dev/null || true

    # Remove old test server entries
    grep -v "^#.*Test Server" "$file" 2>/dev/null | \
    grep -v "^NEXTCLOUD_SERVER=" | \
    grep -v "^NEXTCLOUD_USERNAME" | \
    grep -v "^NEXTCLOUD_PASSWORD" | \
    grep -v "^RADICALE_" | \
    grep -v "^BAIKAL_" | \
    grep -v "^STALWART_" > "$temp_file" || true

    # Add test server credentials
    cat >> "$temp_file" << 'EOF'

# === Docker Test Server Credentials ===
# Nextcloud
NEXTCLOUD_SERVER=http://localhost:8080
NEXTCLOUD_USERNAME=testuser1
NEXTCLOUD_PASSWORD=testpass1
NEXTCLOUD_USERNAME_2=testuser2
NEXTCLOUD_PASSWORD_2=testpass2
NEXTCLOUD_USERNAME_3=testuser3
NEXTCLOUD_PASSWORD_3=testpass3
NEXTCLOUD_USERNAME_4=testuser4
NEXTCLOUD_PASSWORD_4=testpass4

# Radicale
RADICALE_SERVER=http://localhost:5232
RADICALE_USERNAME=testuser1
RADICALE_PASSWORD=testpass1
RADICALE_USERNAME_2=testuser2
RADICALE_PASSWORD_2=testpass2

# Baikal (requires manual setup first)
BAIKAL_SERVER=http://localhost:8081
BAIKAL_USERNAME=testuser1
BAIKAL_PASSWORD=testpass1
BAIKAL_USERNAME_2=testuser2
BAIKAL_PASSWORD_2=testpass2

# Stalwart
STALWART_SERVER=http://localhost:8082
STALWART_USERNAME=testuser1
STALWART_PASSWORD=testpass1
STALWART_USERNAME_2=testuser2
STALWART_PASSWORD_2=testpass2
EOF

    mv "$temp_file" "$file"
    echo -e "  ${GREEN}Updated local.properties${NC}"
}

update_local_properties

echo ""
echo -e "${GREEN}=== Setup Complete ===${NC}"
echo ""
echo "Run integration tests with:"
echo "  ./gradlew testDebugUnitTest --tests '*Nextcloud*'"
echo "  ./gradlew testDebugUnitTest --tests '*Radicale*'"
echo "  ./gradlew testDebugUnitTest --tests '*Baikal*'"
echo "  ./gradlew testDebugUnitTest --tests '*Stalwart*'"
echo ""
echo "To stop test servers:"
echo "  docker compose -f docker/docker-compose.yml down"
echo ""
