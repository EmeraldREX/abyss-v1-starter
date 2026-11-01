# Phase 11: run-only leveling

- At successful dungeon run allocation, level starts at 1 and XP at 0.
- XP is awarded only for dungeon enemies defeated during an active run.
- Level and XP are held in memory, not saved to the legacy `abyss_levels` SQLite table.
- On extraction, `/abyss leave`, death, and plugin shutdown, run level is discarded.
- Equipment, acquired permanent warehouse items, class selection, and learned magic remain persistent.
- `/abyss level N` is OP/admin-only and affects only the current run; it cannot be used in the lobby.
- Existing legacy `abyss_levels` rows are not deleted, but are ignored.
- **Limitation:** in-progress runs are not restart-resumable; reloading the plugin discards run levels. Server restart/run restoration is a separate feature.
- Build and live Paper testing were not performed.
