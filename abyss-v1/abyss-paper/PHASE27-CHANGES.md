# Phase 27 (source-only, unverified)
- Added broad 17x17 stepped terraces to combat/exploration maps where the schematic has sufficient clearance. Existing schematic floor remains; maps with too little clear area log zero terraces.
- Changed mob aggro radius to 8/16/28/48 blocks at 0/30/60/90 seconds; mobs without nearby players lose target. Vanilla AI may still briefly acquire targets between one-second ticks.
- Boosted custom enemy attack value (the one used in AbyssStats.combat), including floor 1; not just the vanilla attribute.
- Set food to 20 and saturation to 5 on successful floor-entry teleports for all party members.
- WARNING: Gradle not available; no compile/runtime test. Existing schematic spawn locations may intersect terraces and require a further collision-safe pass.
