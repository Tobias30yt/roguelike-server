#!/usr/bin/env bash
set -euo pipefail
# Installs a NEW server only. Existing installations are never overwritten.
# Usage: sudo bash install-ubuntu.sh /path/Roguelike-0.1.0.jar [--accept-eula] [--start]
if [[ ${1:-} == --help || ${1:-} == -h ]]; then
  echo 'Usage: sudo bash deploy/install-ubuntu.sh PLUGIN.jar [--accept-eula] [--start]'
  exit 0
fi
[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
case "$(uname -m)" in
  aarch64) java_arch=aarch64 ;;
  x86_64) java_arch=x64 ;;
  *) echo 'ARM64 or x86_64 required.' >&2; exit 1 ;;
esac
[[ -f /etc/os-release ]] || { echo 'Ubuntu required.' >&2; exit 1; }
. /etc/os-release
[[ $ID == ubuntu ]] || { echo 'Ubuntu required.' >&2; exit 1; }
plugin_jar=$(realpath "${1:?Provide the built plugin JAR}")
[[ -f "$plugin_jar" && "$plugin_jar" == *.jar ]] || exit 1
shift
accept_eula=false
start_server=false
while (($#)); do
  case "$1" in
    --accept-eula) accept_eula=true ;;
    --start) start_server=true ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
if $start_server && ! $accept_eula; then
  echo '--start requires --accept-eula. Read https://aka.ms/MinecraftEULA first.' >&2
  exit 2
fi
[[ ! -e /srv/roguelike/paper.jar ]] || { echo 'Existing installation: follow the upgrade guide.' >&2; exit 1; }
[[ ! -e /etc/systemd/system/roguelike.service ]] || { echo 'Existing systemd unit: follow the upgrade guide.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
apt-get update
apt-get install -y ca-certificates curl jq tar
work_dir=$(mktemp -d /tmp/roguelike-install.XXXXXX)
echo "Download workspace: $work_dir"
if [[ ! -x /opt/roguelike-java25/bin/java ]]; then
  curl -fsSL --retry 3 --proto '=https' --proto-redir '=https' "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$java_arch&image_type=jdk&os=linux&vendor=eclipse" -o "$work_dir/java.json"
  java_url=$(jq -er '.[0].binary.package.link' "$work_dir/java.json")
  java_sha=$(jq -er '.[0].binary.package.checksum' "$work_dir/java.json")
  [[ $java_sha =~ ^[a-fA-F0-9]{64}$ ]] || { echo 'Invalid Java checksum.' >&2; exit 1; }
  curl -fL --retry 3 --proto '=https' --proto-redir '=https' "$java_url" -o "$work_dir/java.tar.gz"
  printf '%s  %s\n' "$java_sha" "$work_dir/java.tar.gz" | sha256sum --check -
  mkdir "$work_dir/jdk"
  tar -xzf "$work_dir/java.tar.gz" --strip-components=1 -C "$work_dir/jdk"
  [[ ! -e /opt/roguelike-java25 ]] || { echo '/opt/roguelike-java25 exists but is not usable.' >&2; exit 1; }
  mv "$work_dir/jdk" /opt/roguelike-java25
fi
/opt/roguelike-java25/bin/java -version
/opt/roguelike-java25/bin/java -XshowSettings:properties -version 2>&1 | grep -Eq 'java.specification.version = 25$' || { echo 'Java 25 required.' >&2; exit 1; }
id minecraft >/dev/null 2>&1 || useradd --system --user-group --home-dir /srv/roguelike --shell /usr/sbin/nologin minecraft
install -d -o minecraft -g minecraft /srv/roguelike /srv/roguelike/plugins /srv/roguelike/backups
paper_sha=f8e684a4fef2a8b46d03aac049b49d59275e9ce5367dba7c6075cb5780f38470
curl -fL --retry 3 --proto '=https' --proto-redir '=https' "https://fill-data.papermc.io/v1/objects/$paper_sha/paper-26.3-223.jar" -o "$work_dir/paper.jar"
printf '%s  %s\n' "$paper_sha" "$work_dir/paper.jar" | sha256sum --check -
install -o minecraft -g minecraft -m 0640 "$work_dir/paper.jar" /srv/roguelike/paper.jar
install -o minecraft -g minecraft -m 0640 "$plugin_jar" /srv/roguelike/plugins/Roguelike.jar
install -o minecraft -g minecraft -m 0640 "$script_dir/server.properties" /srv/roguelike/server.properties
if $accept_eula; then
  printf 'eula=true\n' > /srv/roguelike/eula.txt
else
  printf 'eula=false\n' > /srv/roguelike/eula.txt
fi
chown minecraft:minecraft /srv/roguelike/eula.txt
install -m 0644 "$script_dir/roguelike.service" /etc/systemd/system/roguelike.service
systemctl daemon-reload
if $start_server; then systemctl enable --now roguelike; fi
echo 'Installed. Read https://aka.ms/MinecraftEULA and set eula=true if you agree.'
echo 'Then: sudo systemctl enable --now roguelike'
echo 'Logs: sudo journalctl -u roguelike -f'
echo 'Whitelist is enabled. Add your account before opening the server to players.'
echo 'Setup guide: https://github.com/Tobias30yt/roguelike-server/blob/main/docs/START-HIER.md'
