# Local verification, 2026-10-26

Executed on Windows 11 amd64, Temurin Java 25.0.4.1+1, Maven 3.9.16.

- `mvn -o -B clean package`: **BUILD SUCCESS**, 15:04:57 Europe/Berlin.
- **18 JUnit tests**, zero failures/errors/skips. Actual Surefire text reports: `junit.txt`.
- Final plugin loaded on Paper **26.3-223 / API 26.3.build.223-stable**, bound only to `127.0.0.1:25579`.
- Startup readiness at 15:05:21; `roguelike selftest` at 15:05:39 passed terrain, item codec, armor/offhand valuation, tagged-item exclusion, NPC creation/removal and managed world deletion.
- Clean console `stop` at 15:06:03; server process exit code 0.
- Actual server log: `paper-smoke.log`. The OSHI error concerns this Windows host's performance-counter registry. There were no Roguelike startup or smoke failures.
- Both deployment shell scripts passed `bash -n` syntax validation. They have **not** been installed or executed on an ARM64 VM.

Tested plugin SHA-256:

```text
a796240c6f7db8c6cf778dbb142034719d685df50d74a156df3ed63ab42d5627
```

No real Minecraft clients participated. Inventory transitions, menu interaction, two-player trading and injected server-crash scenarios still require the acceptance procedure in `../TESTING.md`. Unit and console smoke results do not substitute for those tests. The README lists outstanding master-spec features.
