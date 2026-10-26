# Oracle Cloud Ubuntu ARM64

The plugin is built for Paper **26.3 build 223** and Java **25**. The API and server JAR were fetched from Paper's official repository/download service; the server checksum is pinned in the installer. SQLite JDBC includes Linux aarch64 native libraries. Actual Ampere deployment still needs to be tested on your VM.

## Prepare the VM

Use an Ubuntu ARM64 image on Ampere A1. Connect as the image's `ubuntu` user using your SSH key. Start with two loaded run worlds and a 512-block border if RAM is limited. The supplied service uses a 2 GB heap; reserve additional memory for Linux, native allocations and chunk I/O. Tune limits after measuring real player load.

In the OCI VCN security list or network security group, allow inbound **TCP 25565** from your intended players. Allow SSH only from your management IP. Apply the corresponding rule to the Ubuntu host firewall. Preserve existing SSH access; do not flush firewall rules. Minecraft Java gameplay does not require opening RCON or a database port.

Official references: [Oracle instance access](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/accessinginstance.htm), [OCI security lists](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securitylists.htm), [Paper Java requirements](https://docs.papermc.io/paper/getting-started/), [Adoptium downloads API](https://github.com/adoptium/api.adoptium.net/blob/main/docs/cookbook.adoc).

## Install directly from GitHub

The release bootstrap supports Ubuntu ARM64 and x86_64. Download `install.sh` from [release v0.1.0](https://github.com/Tobias30yt/roguelike-server/releases/tag/v0.1.0), then run `sudo bash install.sh`. Add `--accept-eula --start` to accept the Minecraft EULA and immediately enable/start the service. The release archive, JDK and Paper downloads are checksum-verified. See [the German quickstart](START-HIER.md) for the exact commands and whitelist setup.

## Install from local files

Build locally with JDK 25 and `mvn clean package`. Upload `roguelike-paper/target/Roguelike-0.1.0.jar` and the entire `deploy/` directory to the VM. Keep private SSH keys on your own machine.

```bash
sudo bash deploy/install-ubuntu.sh ./Roguelike-0.1.0.jar
```

The installer downloads a checksum-verified Temurin Java 25 ARM64 JDK and the pinned Paper server. It creates a dedicated service user, installs the plugin and service, and leaves the server stopped. It refuses to overwrite an existing installation. It does not change your firewall.

Read the Minecraft EULA and set `/srv/roguelike/eula.txt` to `eula=true` if you accept. Alternatively pass `--accept-eula` explicitly to the installer. Start once in the foreground to add your account to the whitelist and grant administration:

```bash
cd /srv/roguelike
sudo -u minecraft /opt/roguelike-java25/bin/java --enable-native-access=ALL-UNNAMED -Xms512M -Xmx2G -jar paper.jar --nogui
# In the Minecraft console:
whitelist add YOUR_MINECRAFT_NAME
op YOUR_MINECRAFT_NAME
stop
```

Then start the service:

```bash
sudo systemctl enable --now roguelike
sudo journalctl -u roguelike -f
```

Connect with Minecraft Java 26.3. The four hub NPCs are created on the first plugin startup. Start a run with the Create NPC or `/world create`.

## Memory and maintenance

Set `JAVA_XMS=512M` and `JAVA_XMX=4G` in `/etc/default/roguelike` only if your VM has enough memory. Restart after changing them. Tune `worlds.max-loaded` in the plugin's `config.yml`, together with view/simulation distances in `server.properties`.

For a full consistent backup, use `sudo bash deploy/backup.sh`. It stops the service, archives worlds and plugin data together, then restarts it if it was previously running. `/roguelike backup` and automatic reset backups contain **SQLite only**; they are not substitutes for full world backups.

To upgrade: stop the service, make a full backup, replace only `plugins/Roguelike.jar`, then start and inspect logs. Keep Paper and the plugin API target compatible. Do not downgrade worlds. Restore a full backup into a stopped server; never combine an old database with newer world/player files.

Before allowing public players, complete [TESTING.md](TESTING.md). No VM was accessed or modified during local development.
