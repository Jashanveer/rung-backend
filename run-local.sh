#!/usr/bin/env bash
# Source .env into the environment, then start the Spring Boot app with the
# local profile. Anything after `./run-local.sh` is forwarded to mvnw.
#
# Usage:
#   ./run-local.sh                  # spring-boot:run
#   ./run-local.sh test             # mvn test with .env exported
#
# .env must be at the project root (same directory as this script).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$HERE/.env"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "⚠️  .env not found at $ENV_FILE"
    echo "   Copy .env.example to .env and fill in values before running."
    exit 1
fi

# Export every KEY=VALUE line into the environment.
# `set -a` auto-exports any variables defined until `set +a`.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ $# -eq 0 ]]; then
    exec "$HERE/mvnw" spring-boot:run
else
    exec "$HERE/mvnw" "$@"
fi
