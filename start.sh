#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v java >/dev/null || { echo 'Install JDK 17+'; exit 1; }
java --list-modules 2>/dev/null | grep -q '^jdk.compiler@' || { echo 'Install a full JDK 17+, not a JRE'; exit 1; }
test -f backend/target/skyshelf.jar || { echo 'Missing backend/target/skyshelf.jar'; exit 1; }
(cd backend && exec java -jar target/skyshelf.jar --app.demo=true) &
backend_pid=$!
java frontend/FrontendServer.java frontend 5500 &
frontend_pid=$!
trap 'kill "$backend_pid" "$frontend_pid" 2>/dev/null || true' EXIT INT TERM

if command -v curl >/dev/null; then
  ready=false
  for _ in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:5500/api/health >/dev/null 2>&1; then ready=true; break; fi
    sleep 0.3
  done
  "$ready" || { echo 'SkyShelf did not start. Check the backend output above.'; exit 1; }
else
  sleep 4
fi
echo 'SkyShelf: http://localhost:5500'
echo 'Demo: demo@skyshelf.local / SkyShelfDemo2026!'
echo 'Ctrl+C stops both processes.'
wait -n "$backend_pid" "$frontend_pid"
