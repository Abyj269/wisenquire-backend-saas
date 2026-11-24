#!/usr/bin/env bash
# Stop staging. Data stays in the volumes unless --wipe (deletes the DB, MinIO and media cache).
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == "--wipe" ]]; then compose down -v --remove-orphans; else compose down --remove-orphans; fi
