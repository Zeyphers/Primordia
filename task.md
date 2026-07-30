# Task checklist: wall-surface navigation

Plan: `implementation_plan.md` (approved). User addition: climbing must also apply to creatures
with 6+ legs that are relatively small (mass ≤ 0.6).

- [x] [NEW] `entity/ai/SurfaceNodeEvaluator.java` — climb edges in the A* graph
- [x] [NEW] `entity/ai/SurfaceClimberNavigation.java` — climb-aware path following
- [x] [MODIFY] `CreatureEntity.java` — `createNavigation` override + widen `canClimb` (6+ legs, mass ≤ 0.6)
- [x] [MODIFY] `gradle.properties` — bump `mod_version` to 0.2.0-26.2-alpha.18
- [x] Verify: `gradle build` clean (exit 0)
- [x] Deploy: alpha.18 jar in Modrinth profile, alpha.17 removed (exactly one primordia jar)
- [ ] In-game check (manual): cave crawler point-to-point across rock, overhang target reached the
      long way round, tempt unchanged on flat ground, mantle/dismount never interrupted
