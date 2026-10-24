# Among Us — Fabric 1.21.11 source project

Source project for a Minecraft Java Fabric 1.21.11 Among Us prototype. Requires Java 21, Fabric Loader 0.19.5, Fabric API for 1.21.11, and Gradle.

## Build
Open this folder in IntelliJ IDEA as a Gradle project and run `gradle build`. The jar should be created in `build/libs/`.

## Commands
- `/amongus join` — join lobby
- `/amongus leave` — leave lobby
- `/amongus start` — operator starts (minimum 4)
- `/amongus end` — operator ends and resets
- `/amongus status` — show phase and player count
- `/amongus settings impostors <number>` — operator sets Impostor count
- `/amongus settings crewmates <number>` — operator sets Crewmate count
- `/amongus settings show` — operator displays settings
- `/amongus report` — open meeting
- `/amongus meeting` — call emergency meeting
- `/amongus vote <player>` — vote
- `/amongus skip` — skip vote
- `/amongus vent` — Impostor uses nearby connected iron-trapdoor vents
- `/amongus sabotage` — start prototype sabotage timer
- `/amongus help` — show help

Crewmates receive five randomly selected block-station tasks. Right-click assigned stations to complete them. Impostors eliminate by hitting a Crewmate with a sword. The configured Impostor and Crewmate counts must add up to the number of players in the lobby. If Crewmates are not explicitly configured, their count is automatic.

## Limitations
This is source code, not a compiled or tested JAR. It is an early prototype; it does not yet include AI bots, full graphical minigames, polished original-map recreation, custom player models, or finished sabotage systems. No successful Gradle build has been verified in this environment.
