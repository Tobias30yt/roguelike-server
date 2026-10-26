# Roguelike auf deiner VM starten

Dieses Paket enthält einen getesteten Entwicklungsstand für **Paper 26.3 / Java 25**. Die vollständige Umsetzung des Master-Prompts ist noch nicht abgeschlossen. Fehlende Funktionen stehen in der README; die Mehrspieler- und ARM64-Abnahme steht noch aus.

## Direkt von GitHub installieren

Auf einer neuen Ubuntu-VM (ARM64 oder x86_64):

```bash
curl -fL --proto '=https' --proto-redir '=https' https://github.com/Tobias30yt/roguelike-server/releases/download/v0.1.0/install.sh -o install.sh
sudo bash install.sh --accept-eula --start
```

Mit `--accept-eula` bestätigst du die [Minecraft-EULA](https://aka.ms/MinecraftEULA). Ohne `--accept-eula --start` wird nur installiert. Anschließend weiter bei **Schritt 3**, um deinen Namen in die Whitelist einzutragen. Schritte 1 und 2 sind die Alternative für eine manuelle Dateiübertragung.

## 1. Paket übertragen

Die VM muss Ubuntu auf **ARM64 / Ampere A1** oder **x86_64** verwenden. Der mitgelieferte Dienst startet mit 2 GB Java-Heap; die VM benötigt zusätzlich RAM für Betriebssystem und Server-Nebenspeicher.

Übertrage `dist/Roguelike-0.1.0.zip` mit deinem SSH-Client auf die VM. Beispiel in PowerShell, aus dem Projektordner:

```powershell
scp .\dist\Roguelike-0.1.0.zip ubuntu@DEINE_VM_IP:~/
ssh ubuntu@DEINE_VM_IP
```

Falls dein SSH-Schlüssel nicht automatisch gewählt wird, ergänze `-i PFAD_ZU_DEINEM_SCHLUESSEL`. Der private Schlüssel bleibt auf deinem Rechner.

## 2. Installieren

Auf der VM:

```bash
sudo apt-get update
sudo apt-get install -y unzip
unzip Roguelike-0.1.0.zip
cd Roguelike-0.1.0
sudo bash deploy/install-ubuntu.sh ./Roguelike-0.1.0.jar
```

Das Skript installiert Java 25, die festgelegte Paper-Version, das Plugin und den systemd-Dienst. Es verändert keine Firewall-Regeln und startet den Server noch nicht. Eine bestehende Installation wird abgelehnt.

## 3. EULA, Whitelist und erster Start

Lies die [Minecraft-EULA](https://aka.ms/MinecraftEULA). Wenn du zustimmst, ändere mit `sudo nano /srv/roguelike/eula.txt` den Eintrag zu `eula=true`.

```bash
sudo systemctl stop roguelike
cd /srv/roguelike
sudo -u minecraft /opt/roguelike-java25/bin/java --enable-native-access=ALL-UNNAMED -Xms512M -Xmx2G -jar paper.jar --nogui
```

Warte auf `Roguelike ready on Paper 26.3`. Gib anschließend direkt in der Minecraft-Serverkonsole ein:

```text
whitelist add DEIN_MINECRAFT_NAME
op DEIN_MINECRAFT_NAME
roguelike selftest
stop
```

Der Selbsttest muss zweimal `PASS` melden. Anschließend:

```bash
sudo systemctl enable --now roguelike
sudo journalctl -u roguelike -f
```

## 4. Verbinden und spielen

Erlaube **TCP 25565** in der Oracle-Netzwerkregel und der Ubuntu-Firewall. Behalte deinen SSH-Zugang bei. Verbinde dich mit Minecraft Java 26.3 über die öffentliche VM-IP.

Die vier NPCs erscheinen im Hub. `/world create` startet einen Run, `/hub` parkt ihn, `/world rejoin` setzt ihn fort. Tod oder `/world complete` rechnen den Run ab. `/unlocks`, `/market`, `/trade` und `/plot` öffnen die weiteren Systeme.

Alle Einstellungen werden beim ersten Start unter `/srv/roguelike/plugins/Roguelike/` angelegt. Nach Änderungen ist ein Serverneustart erforderlich. Wenn Datenbank oder Konfiguration beim Start fehlschlagen, beendet das Plugin den dedizierten Server, damit niemand mit ungeschütztem Zustand weiterspielt.

Vor dem öffentlichen Betrieb: [Mehrspieler-Testliste](TESTING.md). Für Backups und Updates: [Deployment-Anleitung](DEPLOYMENT.md). Für Befehle: [Spielerhandbuch](PLAYER.md) und [Administration](ADMIN.md).
