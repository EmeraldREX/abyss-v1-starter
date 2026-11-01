Phase41 fixes (source patch, not compiled):
- Skill GUI shows per-class effect details; Q activation gives chat feedback and particles.
- Treasure floor blocks gold-block advance until every online member selects a reward; selection submits pending reward and acknowledges it is saving.
- Weapon refresh removes duplicate generated weapons from other slots; normal inventory snapshot excludes generated weapons; restoration clears special inventory first.
Caveats: pending reward writes are asynchronous; confirmation requires checking /abyss bag after save. Offline party members and asynchronous save races need live testing. Existing duplicated weapons in normal-world snapshots from older versions are not automatically cleaned. Gradle/Paper tests not performed.
