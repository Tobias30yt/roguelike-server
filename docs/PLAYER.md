# Spieleranleitung

Du startest im Hub. Rechtsklicke einen der vier Dorfbewohner oder benutze die Befehle.

| Befehl | Funktion |
| --- | --- |
| `/world create` | Neue private Inselwelt, Startvorteile waehlen und bestaetigen |
| `/world rejoin` | Aktiven Run fortsetzen |
| `/hub`, `/world leave` | Run parken und zum Hub zurueckkehren |
| `/world info` | Run-ID, Zustand und Seed |
| `/world complete` | Run freiwillig beenden und Inventar verwerten |
| `/upgrades`, `/unlocks` | Freischaltungen kaufen |
| `/balance`, `/pay Spieler Coins` | Kontostand und Ueberweisungen |
| `/rebirth` | Anforderungen ansehen und Rebirth bestaetigen |
| `/market` | Marktmenue |
| `/market buy Asset Anzahl` | Sofortkauf mit Bestaetigung |
| `/market sell Asset Anzahl` | Sofortverkauf mit Bestaetigung |
| `/market portfolio` | Eigene Anlagen |
| `/market order buy\|sell Asset Anzahl Limit` | Limit pro Einheit inklusive Gebuehren |
| `/market orders`, `/market cancel ID` | Auftraege ansehen/abbrechen |
| `/trade Spieler`, `/trade accept` | Handel anbieten/ansehen |
| `/trade coins Betrag` | Coin-Angebot aendern; 0 ist erlaubt |
| `/trade asset ID Anzahl` | Marktanlage anbieten |
| `/trade item` | Item aus der Haupthand hinterlegen, nur im Hub |
| `/trade claim` | Abgeschlossene oder zurueckgegebene Items abholen |
| `/trade deny`, `/trade cancel` | Handel abbrechen |
| `/plot`, `/plot home` | Baumaterial-Menue und Plot |
| `/plot trust Spieler`, `/plot untrust Spieler` | Baurechte vergeben/entziehen |
| `/plot visitors on\|off` | Besucher zulassen/sperren |
| `/trophies` | Dauerhafte Trophaeen; auf dem eigenen Plot anzeigen |
| `/stats` | Lebenszeitstatistik |
| `/leaderboard Metrik Zeitraum` | Rangliste |
| `/season` | Jahreszeit und aktive Events |

Zeitraeume: `daily`, `weekly`, `monthly`, `annual`, `lifetime`. Metriken: `coins`, `runs`, `deaths`, `rebirths`, `achievements`, `survival`. Wochen beginnen montags in der konfigurierten Zeitzone.

Ein Tod beendet den Run dauerhaft. Das bewertete Inventar umfasst Hauptinventar, Ruestung und Nebenhand. Nur in `economy.yml` aufgefuehrte, unmarkierte Items haben einen Auszahlungswert. Inhalte von Kisten/Shulkerboxen werden nicht rekursiv bewertet. Hub-Baumaterial und Trophaeen sind nicht verwertbar. Gegenstaende im Run bleiben vom Hub-Inventar getrennt.

Bei einem freiwilligen Abschluss gilt dieselbe Verwertung, ohne einen Tod zu zaehlen. Leere Runs erzeugen keine Inventar-Coins. Geparkte Runs behalten ihre Welt. Biome, Erze und Ruinen-Freischaltungen gelten fuer neu erzeugte Runs; Item-Freischaltungen gelten sofort.

Pruefe im Handelsmenue beide Angebote. Jede Aenderung setzt beide Bestaetigungen zurueck. Nach einem Abbruch oder Verbindungsverlust bleiben hinterlegte Items im Abholdepot. Wenn Inventarplaetze oder Freischaltungen fehlen, bleiben sie dort, bis du sie abholen kannst. Die Depot-Items verfallen beim Jahresreset.

Beim Jahresreset gehen Coins, aktuelle Anlagen, Rebirths, Freischaltungen und Runs verloren. Plotbauten, Trophaeen, historische Erfolge und Lebenszeitstatistiken bleiben. Ein Jahreszeitenwechsel allein setzt keine Konten zurueck.
