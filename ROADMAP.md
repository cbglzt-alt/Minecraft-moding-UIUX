# Roadmap

## Done (Phase 1)

- [x] GPU frame-budget ledger via `GL_TIMESTAMP` ring buffer
- [x] Nested-safe spans (terrain / entities / block entities / particles / clouds / sky)
- [x] HUD breakdown with cost-sorted rows and proportional bars
- [x] Key bindings (`F8` toggle, `F9` expand)
- [x] Verified mixin injection under Sodium's `@Overwrite` of `renderLayer`

## Phase 2 — per-option cost attribution

The differentiating feature. Instead of shipping `Impact: HIGH`, measure it on the player's machine.

**Method.** For a target option, run alternating A/B/A/B blocks while recording the relevant span:

```
A B A B A B ...   (A = current value, B = one step lower)
```

Alternating rather than measuring all A then all B is what makes this robust: it cancels slow
drift (day/night, mob spawning, thermal throttling, background load) that would otherwise be
mistaken for the option's cost.

**Output.** `Δ = mean(A) − mean(B)` with a confidence interval, and a refusal to report when the
interval straddles zero. Reporting "this option costs 0.1 ms here, i.e. nothing" is as valuable as
reporting a real cost, and much more valuable than a wrong number.

**Risks to handle honestly.**

- Options that require a full renderer reload cannot be A/B'd per frame; those need a longer
  sampling window or must be excluded.
- A/B toggling is visible to the player. The sweep should be explicit, bounded, and cancellable —
  not a hidden background process.
- Sodium does not expose a per-option `applyChanges()` cost, so the measurement window has to be
  defined by the caller.

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

## Explicitly out of scope

- **Adaptive quality loops.** Already well served by existing mods; and without attribution they
  are guessing. FrameLedger should feed them, not duplicate them.
- **Rewriting Sodium's renderer.** Not the point, and Sodium's Polyform-Shield license makes
  derived redistribution awkward.
