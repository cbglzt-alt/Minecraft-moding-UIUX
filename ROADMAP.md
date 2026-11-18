# Roadmap

## Done (Phase 1)

- [x] GPU frame-budget ledger via `GL_TIMESTAMP` ring buffer
- [x] Nested-safe spans (terrain / entities / block entities / particles / clouds / sky)
- [x] HUD breakdown with cost-sorted rows and proportional bars
- [x] Key bindings (`F8` toggle, `F9` expand)
- [x] Verified mixin injection under Sodium's `@Overwrite` of `renderLayer`

## Phase 2 — per-option cost attribution  ✅ done

Instead of shipping `Impact: HIGH`, measure it on the player's machine.

**Method.** Alternating A/B/A/B blocks, 12 settle frames then 40 sampled frames per block, 8 pairs.
Statistic is the mean of per-pair differences with a Student's t 95% interval.

Alternating rather than measuring all A then all B is what makes this robust: it cancels slow
drift (day/night, mob spawning, thermal throttling, background load) that would otherwise be
mistaken for the option's cost.

**Output.** `Δ = mean(A) − mean(B)` with a confidence interval, and a refusal to report when the
interval straddles zero. Reporting "this option costs 0.1 ms here, i.e. nothing" is as valuable as
reporting a real cost, and much more valuable than a wrong number.

**Eligibility was decided by reading the bytecode, not by intuition.** The change callbacks in
`GameOptions` were resolved through `BootstrapMethods` to find which options reload the renderer:

| Option | Callback | Effect | Verdict |
|---|---|---|---|
| particles, clouds, entityShadows, entityDistanceScaling, mipmapLevels | — | pure state change | swept |
| **ao** (smooth lighting) | `method_47999` | `WorldRenderer.reload()` | excluded |
| **biomeBlendRadius** | `method_41797` | `WorldRenderer.reload()` | excluded |

`ao` and `biomeBlendRadius` look like ideal candidates and were in the first draft. Sweeping them
frame-by-frame would fold a reload hitch into whichever arm was active and produce a confidently
wrong answer, which is the one failure mode this mod exists to avoid.

**Design rules honored.**

- Only one sweep at a time; concurrent sweeps would both mutate options.
- A sweep is explicit (`F10`), bounded, and cancellable (pause menu).
- Settings are always restored: on completion, on cancel, and on world exit.
- "One notch down" is *computed*, never applied speculatively. An earlier draft called the stepper
  on the live option and restored it, which fired the change callback every time the catalog was
  built — merely opening the menu flipped clouds and particles.

## Phase 3 — block-entity culling on the measurement spine

**What the decompilation showed.** Sodium 0.6.13 ships a dormant extension point:

- `api.blockentity.BlockEntityRenderPredicate` — public interface (`shouldRender`)
- `api.blockentity.BlockEntityRenderHandler` — public registration API
- Sodium itself registers **zero** predicates.

**The important caveat, found by reading the call site.** `ExtendedBlockEntityType.shouldRender`
is invoked from `ChunkBuilderMeshingTask`, i.e. during **chunk mesh rebuild**, not per frame. So a
predicate based on camera distance is evaluated whenever a section is rebuilt and then goes stale
as the player walks away. That makes the predicate API a poor fit for per-frame distance culling.

**Therefore** Phase 3 uses the predicate only for *geometry-level* decisions (things that are
genuinely static per section), and any per-frame block-entity culling goes through an explicit
`SodiumWorldRenderer.renderBlockEntities` hook instead. Cost thresholds for those decisions come
from Phase 2's measurements rather than a hardcoded distance.

**Standalone requirement.** FrameLedger does not depend on Sodium. Where a Sodium extension point
exists it will be used; where it does not, the equivalent behaviour has to come from the mod's own
mixin against the vanilla renderer. The two paths must share one culling policy, not diverge.

## Explicitly out of scope

- **Adaptive quality loops.** Already well served by existing mods; and without attribution they
  are guessing. FrameLedger should feed them, not duplicate them.
- **Rewriting Sodium's renderer.** Not the point. Sodium is a `recommends`, never a requirement.
