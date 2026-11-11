#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=/home/raze/apps/decentralert
WEB_ROOT=/var/www/decentralert
SOURCE_ROOT=/home/raze/apps/decentralert-src

if [[ ! -x "$SOURCE_ROOT/target/release/decentralert-backend" ]]; then
  echo "Release binary missing: $SOURCE_ROOT/target/release/decentralert-backend" >&2
  exit 1
fi
if [[ ! -f "$SOURCE_ROOT/dashboard/index.html" ]]; then
  echo "Dashboard bundle missing: $SOURCE_ROOT/dashboard/index.html" >&2
  exit 1
fi

install -d -m 0750 "$APP_ROOT"
if [[ -f "$APP_ROOT/.env" ]]; then
  DB_PASSWORD="$(sed -nE 's#^DATABASE_URL=postgres://decentralert:([^@]+)@.*#\1#p' "$APP_ROOT/.env")"
  if [[ -z "$DB_PASSWORD" ]]; then
    echo "Existing $APP_ROOT/.env does not contain a valid DATABASE_URL" >&2
    exit 1
  fi
else
  DB_PASSWORD="$(openssl rand -hex 24)"
  OPERATOR_TOKEN="$(openssl rand -hex 32)"
  umask 077
  printf 'DATABASE_URL=postgres://decentralert:%s@127.0.0.1:5432/decentralert\nOPERATOR_TOKEN=%s\n' "$DB_PASSWORD" "$OPERATOR_TOKEN" > "$APP_ROOT/.env"
fi

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql postgresql-18-postgis-3
sudo systemctl enable --now postgresql

if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='decentralert'" | grep -q 1; then
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE decentralert LOGIN"
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 --set=db_password="$DB_PASSWORD" <<'SQL'
ALTER ROLE decentralert PASSWORD :'db_password';
SQL
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='decentralert'" | grep -q 1; then
  sudo -u postgres createdb --owner=decentralert decentralert
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 -d decentralert -c "CREATE EXTENSION IF NOT EXISTS postgis"

install -m 0755 "$SOURCE_ROOT/target/release/decentralert-backend" "$APP_ROOT/decentralert-backend"

sudo install -d -o caddy -g caddy -m 0755 "$WEB_ROOT"
sudo cp -a "$SOURCE_ROOT/dashboard/." "$WEB_ROOT/"
sudo chown -R caddy:caddy "$WEB_ROOT"
sudo find "$WEB_ROOT" -type d -exec chmod 0755 {} +
sudo find "$WEB_ROOT" -type f -exec chmod 0644 {} +

sudo install -m 0644 "$SOURCE_ROOT/decentralert.service" /etc/systemd/system/decentralert.service
sudo systemctl daemon-reload
sudo systemctl enable decentralert.service
sudo systemctl restart decentralert.service

backup="/etc/caddy/Caddyfile.pre-decentralert.$(date -u +%Y%m%dT%H%M%SZ)"
sudo cp -a /etc/caddy/Caddyfile "$backup"
sudo install -m 0644 "$SOURCE_ROOT/Caddyfile.production" /etc/caddy/Caddyfile
if ! sudo caddy validate --config /etc/caddy/Caddyfile; then
  sudo cp -a "$backup" /etc/caddy/Caddyfile
  echo "Caddy validation failed; original configuration restored" >&2
  exit 1
fi
# The existing Caddy configuration disables its admin API, so systemd reload cannot work.
sudo systemctl restart caddy

cloudflared_backup="/etc/cloudflared/config.yml.pre-decentralert.$(date -u +%Y%m%dT%H%M%SZ)"
sudo cp -a /etc/cloudflared/config.yml "$cloudflared_backup"
sudo install -m 0644 "$SOURCE_ROOT/cloudflared.yml" /etc/cloudflared/config.yml
if ! sudo cloudflared tunnel --config /etc/cloudflared/config.yml ingress validate; then
  sudo cp -a "$cloudflared_backup" /etc/cloudflared/config.yml
  echo "Cloudflare ingress validation failed; original configuration restored" >&2
  exit 1
fi
sudo systemctl restart cloudflared

curl --fail --silent --show-error http://127.0.0.1:8181/health >/dev/null
curl --fail --silent --show-error --header 'Host: decentralert.razecrs.com' http://127.0.0.1:4400/ >/dev/null
echo "Decentralert service and dashboard installed successfully"

