#!/usr/bin/env bash
# Download a published Roguelike release and install it on Ubuntu.
set -euo pipefail
repo=Tobias30yt/roguelike-server
version=0.1.0
installer_args=()
while (($#)); do
  case "$1" in
    --repo) repo=${2:?Missing OWNER/REPO}; shift 2 ;;
    --version) version=${2:?Missing version}; version=${version#v}; shift 2 ;;
    --accept-eula|--start) installer_args+=("$1"); shift ;;
    -h|--help)
      echo 'Usage: sudo bash install.sh [--accept-eula] [--start] [--version 0.1.0] [--repo OWNER/REPO]'
      echo 'Installs a new Ubuntu server. --start requires --accept-eula. Existing servers are not overwritten.'
      exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
[[ $repo =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ && $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid repository or version.' >&2; exit 2; }
[[ -f /etc/os-release ]] || { echo 'Ubuntu required.' >&2; exit 1; }
. /etc/os-release
[[ $ID == ubuntu ]] || { echo 'Ubuntu required.' >&2; exit 1; }
case "$(uname -m)" in aarch64|x86_64) ;; *) echo 'ARM64 or x86_64 required.' >&2; exit 1 ;; esac
[[ ! -e /srv/roguelike/paper.jar ]] || { echo 'Existing server found. Follow docs/DEPLOYMENT.md for updates.' >&2; exit 1; }
if [[ " ${installer_args[*]} " == *' --start '* && " ${installer_args[*]} " != *' --accept-eula '* ]]; then
  echo '--start requires --accept-eula. Read https://aka.ms/MinecraftEULA first.' >&2
  exit 2
fi
apt-get update
apt-get install -y ca-certificates curl unzip
work_dir=$(mktemp -d /tmp/roguelike-release.XXXXXX)
# Keep the uniquely named directory for diagnosis; never remove arbitrary paths.
echo "Release downloads: $work_dir"
base="https://github.com/$repo/releases/download/v$version"
archive="Roguelike-$version.zip"
curl -fSL --retry 3 --proto '=https' --proto-redir '=https' "$base/$archive" -o "$work_dir/$archive"
curl -fsSL --retry 3 --proto '=https' --proto-redir '=https' "$base/SHA256SUMS.txt" -o "$work_dir/SHA256SUMS.txt"
expected=$(awk -v name="$archive" '$2 == name {print $1}' "$work_dir/SHA256SUMS.txt")
[[ $expected =~ ^[a-fA-F0-9]{64}$ ]] || { echo 'Release checksum missing or ambiguous.' >&2; exit 1; }
printf '%s  %s\n' "$expected" "$work_dir/$archive" | sha256sum --check -
unzip -q "$work_dir/$archive" -d "$work_dir"
project="$work_dir/Roguelike-$version"
exec bash "$project/deploy/install-ubuntu.sh" "$project/Roguelike-$version.jar" "${installer_args[@]}"
