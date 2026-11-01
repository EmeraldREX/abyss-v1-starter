# Phase 17–18 experimental integration

This is an **unverified development snapshot**, not a production-ready release.

Changes: shared DungeonRun and slot for an online party (leader starts), party-size HP scaling fixed at entry, XP sharing among surviving members, revive command, downed spectator state, team friendly-fire cancellation, boss HP phase transitions and delayed AoE warning. Existing HUD world restrictions are retained.

Known gaps: revive currently uses `/abyss revive <name>` rather than consuming a spell/item; boss AI is a basic phase attack rather than a full behavior tree; multi-member save and quit/disconnect edge cases require further work. The database migration and Paper runtime tests have not been executed. Do not use on a live server without testing.

For testing, use two online accounts, `/abyss party create`, `/abyss party invite NAME`, `/abyss party accept`, then leader `/abyss start easy`. Test deaths, revives, retreat, reward persistence, and reload/restart.
