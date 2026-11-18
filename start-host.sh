#!/bin/sh
if ! command -v node >/dev/null 2>&1 || ! node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=6)?0:1)'; then
  echo "Flowstate needs Node.js 22.6 or newer. Download and install it from https://nodejs.org/ then try again."
  exit 1
fi
exec node "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/host/scripts/start-host.mjs" "$@"
