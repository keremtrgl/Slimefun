#!/usr/bin/env bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/.mvn-local/apache-maven-3.9.9/bin/mvn" "$@"
