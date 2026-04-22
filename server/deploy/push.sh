#!/bin/sh
# Usage: NFSN_USER=myuser NFSN_HOST=mysite.nfshost.com ./push.sh
set -e

: "${NFSN_USER:?Set NFSN_USER}"
: "${NFSN_HOST:?Set NFSN_HOST}"
REMOTE_PATH="${NFSN_REMOTE_PATH:-/home/protected/server}"

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
  "$(dirname "$0")/../" \
  "${NFSN_USER}@${NFSN_HOST}:${REMOTE_PATH}/"

echo "Deployed to ${NFSN_HOST}:${REMOTE_PATH}"
echo "Remember: pip install --user -r ${REMOTE_PATH}/requirements.txt if deps changed"
