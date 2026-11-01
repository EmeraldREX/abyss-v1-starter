# Phase 26: compact, bright non-combat rooms

REST: 21x21; TREASURE: 17x17; EVENT: 19x19. Seven-block-tall enclosed rooms, smooth flooring, sea lanterns on the ceiling, and minimal decoration along the edges. These three floor types no longer choose the oversized schematic maps. Bounds are stored in arena-slots.yml and removed during normal floor transition. Existing combat/exploration/boss schematics remain unchanged.

Verification: source edited and ZIP verified. Gradle and Paper runtime were not available in this environment; compilation and in-game behavior are not verified. Test transitions and the exit block before production use.
