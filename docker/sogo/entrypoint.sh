#!/bin/bash
set -e

echo "=== SOGo CalDAV Test Server ==="

# Wait for PostgreSQL
echo "Waiting for PostgreSQL..."
until PGPASSWORD=sogopass psql -h sogo-db -U sogo -d sogo -c '\q' 2>/dev/null; do
    sleep 1
done
echo "PostgreSQL is ready."

# Initialize users table
echo "Initializing users..."
PGPASSWORD=sogopass psql -h sogo-db -U sogo -d sogo -f /docker-entrypoint-initdb.d/init-users.sql

# Fix ownership for SOGo
chown -R sogo:sogo /var/log/sogo /var/spool/sogo /var/run/sogo 2>/dev/null || true
mkdir -p /var/log/sogo /var/spool/sogo /var/run/sogo
chown sogo:sogo /var/log/sogo /var/spool/sogo /var/run/sogo

# Start memcached
echo "Starting memcached..."
memcached -d -u memcache -m 64

# Start SOGo
echo "Starting SOGo..."
su -s /bin/bash -c "sogod -WONoDetach NO" sogo &

# Wait for SOGo to be ready
echo "Waiting for SOGo daemon..."
for i in $(seq 1 30); do
    if curl -sf http://127.0.0.1:20000/SOGo/ > /dev/null 2>&1; then
        echo "SOGo is ready."
        break
    fi
    sleep 1
done

# Start Apache in foreground
echo "Starting Apache..."
exec apachectl -D FOREGROUND