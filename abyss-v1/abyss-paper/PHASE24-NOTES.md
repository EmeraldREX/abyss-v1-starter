# Phase 24 experimental map and AI update
- Mob movement speed archetype multipliers: 1.5x, 1.8x, 2.2x, 2.5x, 3.0x (base movement speed, max 0.85).
- AI skill cadence shortened; spider leaps, status attacks, zombie leaps and skeleton projectiles strengthened.
- Floor decoration fixed: previous world.getHighestBlockYAt lookup could start above the schematic in a sky world. Now scans inside pasted schematic bounds; places terrain surface variation, raised blocks, and cover pillars on combat floors; compact floors receive surface variation only.
- Schematic dimensions are still determined by the selected .schem. This patch does NOT resize or replace the original .schem, and does not generate fully new maps.
- Untested: no Gradle wrapper/JDK 25 build environment in current container. Verify on Paper test server before production.
- Future phase requirements were not specified by user; no unrelated features added.
