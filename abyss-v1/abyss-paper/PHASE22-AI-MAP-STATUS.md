# Phase 22 AI / map update

Implemented: timed behavior logic for Zombie/Husk/Drowned (5), Skeleton/Stray (5), Spider/Cave Spider (3), Pillager (5). These use leap, ranged arrows, slow/weakness/poison/resistance, or retreat, in addition to the existing stat variants. AI runs on the main thread every 20 ticks and triggers only for close targeted players. All dungeon spawns register in the behavior controller.

Implemented: bounded cosmetic terrain dressing after schematic paste, with random moss, carpets, plants; fewer objects on rest/treasure/event floors. Existing schematics and their dimensions are unchanged. The requested variable map size, actual terrain elevation, and additional schematic layouts are NOT implemented; they require map schematics or a new generator. Decor is intentionally non-blocking to preserve routes.

Verification: No Gradle wrapper or Java 25 toolchain is installed in this environment; build and Paper runtime tests NOT performed. Please compile and test on a staging server. Check Paper API compatibility of potion effect types and projectile methods.
