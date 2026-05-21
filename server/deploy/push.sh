#!/bin/sh
# Deploy the proxy server to NearlyFreeSpeech.net.
# Usage: ./deploy/push.sh
# Requires SSH key already configured for the NFSN account.
# Copy deploy/push.env.example to deploy/push.env and fill in credentials.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"

# Load local credentials if present; otherwise require env vars.
if [ -f "${SCRIPT_DIR}/push.env" ]; then
  # shellcheck disable=SC1091
  . "${SCRIPT_DIR}/push.env"
fi

: "${NFSN_USER:?Set NFSN_USER in deploy/push.env or the environment}"
: "${NFSN_HOST:?Set NFSN_HOST in deploy/push.env or the environment}"

REMOTE_SERVER="/home/protected/server"
REMOTE_PYLIB="/home/protected/pylib"
REMOTE_PUBLIC="/home/public"

echo "==> Syncing server files to ${NFSN_HOST}:${REMOTE_SERVER}/"
rsync -avz --delete \
  --exclude='.env' \
  --exclude='__pycache__' \
  --exclude='*.pyc' \
  --exclude='.pytest_cache' \
  --exclude='tests/' \
  --exclude='Dockerfile' \
  --exclude='docker-compose.yml' \
  --exclude='docker-entrypoint.sh' \
  --exclude='apache/' \
  --exclude='deploy/' \
  "${SERVER_DIR}/" \
  "${NFSN_USER}@${NFSN_HOST}:${REMOTE_SERVER}/"

echo "==> Uploading .env files..."
scp "${SERVER_DIR}/.env" "${NFSN_USER}@${NFSN_HOST}:${REMOTE_SERVER}/.env"
# Upload the repo-root .env to /home/protected/.env so wsgi.py can load keys
# (e.g. 511_API_KEY) that aren't duplicated in server/.env.
ROOT_ENV="${SERVER_DIR}/../.env"
if [ -f "${ROOT_ENV}" ]; then
  scp "${ROOT_ENV}" "${NFSN_USER}@${NFSN_HOST}:/home/protected/.env"
fi

echo "==> Deploying CGI entry point and .htaccess..."
scp "${SCRIPT_DIR}/index.cgi" "${NFSN_USER}@${NFSN_HOST}:${REMOTE_PUBLIC}/index.cgi"
ssh "${NFSN_USER}@${NFSN_HOST}" "chmod +x ${REMOTE_PUBLIC}/index.cgi"
scp "${SCRIPT_DIR}/nfsn-htaccess" "${NFSN_USER}@${NFSN_HOST}:${REMOTE_PUBLIC}/.htaccess"

echo "==> Installing Python dependencies..."
ssh "${NFSN_USER}@${NFSN_HOST}" \
  "pip3 install --upgrade --target ${REMOTE_PYLIB} -r ${REMOTE_SERVER}/requirements.txt"

echo "==> Pre-compiling Python bytecode..."
ssh "${NFSN_USER}@${NFSN_HOST}" \
  "python3 -m compileall -q /home/protected/server /home/protected/pylib"

echo ""
echo "Deployed. Test your site URL at /healthz"
