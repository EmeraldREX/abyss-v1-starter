# Abyss Dungeon v0.1 — Foundation

Initial Paper plugin foundation for the agreed v1 plan. This is **not yet a playable dungeon**.

## Requirements
- Java 25 / Gradle 9.x / Paper Minecraft 26.2
- IntelliJ IDEA (open the project root as a Gradle project)

## Build
`gradle :abyss-paper:build`

Output: `abyss-paper/build/libs/abyss-paper-0.1.0.jar`

Place this JAR in the dedicated Paper server's `plugins/` folder, then restart.

## Commands
- `/abyss status` — verify plugin is active
- `/abyss lobby` — teleport to the dedicated lobby world

## Included
- Dedicated flat `abyss_lobby` world
- SQLite initialization in `plugins/AbyssDungeon/abyss.db`
- Tables for players, progress and equipment
- Placeholder executor for future async DB operations

## Not yet included
- Dungeon world/instances, 10-floor runs, mobs, magic, inventory isolation, rewards, Velocity integration
- No item isolation guarantee yet: **do not connect this prototype to production worlds or inventory-sync plugins**.

## Notes
- The Paper API coordinate in build.gradle.kts should be checked against the exact Paper 26.2 build available in your repository. No dependency download or compilation was performed in this environment.
- SQLite driver is bundled in the plugin jar for initial prototyping; a production release should use a proper shadow/relocation build.
