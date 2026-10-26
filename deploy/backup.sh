#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
destination=${1:-/var/backups/roguelike}
destination=$(realpath -m -- "$destination")
case "$destination" in
  /srv/roguelike|/srv/roguelike/*) echo 'Backup destination must be outside /srv/roguelike.' >&2; exit 1 ;;
esac
install -d -m 0700 "$destination"
was_active=false
if systemctl is-active --quiet roguelike; then was_active=true; systemctl stop roguelike; fi
restart_if_needed() { if $was_active; then systemctl start roguelike; fi; }
trap restart_if_needed EXIT
archive=$(mktemp "$destination/roguelike-$(date -u +%Y%m%dT%H%M%SZ).XXXXXX.tar.gz")
tar -czf "$archive" -C /srv roguelike
echo "Full consistent world and database backup written to $destination"
