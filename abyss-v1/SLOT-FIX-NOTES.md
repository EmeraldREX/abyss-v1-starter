# Phase 48 main inventory fix v2
- In special worlds, slots 9..35 are not used. Clear all 27 slots on weapon refresh and inventory grant.
- Prevent clicks into those slots. This avoids reliance on legacy weapon lore/IDs.
- Normal worlds are not cleared: existing snapshot restoration is unchanged.
- Source only. Gradle compile and Paper runtime tests have not been performed.
