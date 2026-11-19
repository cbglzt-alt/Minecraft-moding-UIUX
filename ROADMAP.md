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

## Phase 3 — block-entity culling  ✅ done

**The gap, measured rather than assumed.** Vanilla already rejects block entities beyond a
spherical distance — `BlockEntityRenderer.isInRenderDistance` is a plain `isInRange`, 64 blocks by
default. It never tests the view frustum. A chest twenty blocks behind the player still gets its
matrix pushed, its position translated, and its renderer dispatched before anything discards it.

That is CPU work on the render thread, which is the cost this mod exists to measure, and it is
wasted on any renderer.

**Where it hooks.** `BlockEntityRenderDispatcher.render`, because both the vanilla renderer and
Sodium funnel every block-entity draw through it — one seam, both paths.

Sodium's own `BlockEntityRenderPredicate` was considered and rejected: it is evaluated from
`ChunkBuilderMeshingTask`, i.e. at section rebuild time, so a frustum or distance test there goes
stale as the player turns. Per-frame visibility has to be decided per frame.

**Two bugs found by testing it on a live world instead of trusting the build:**

1. **Double-transformed coordinates.** `Frustum.isVisible(Box)` subtracts the camera position
   internally, having been given it by `setPosition`. The first version also offset the box by the
   camera, so it moved twice. Result: 42 of 43 block entities culled regardless of where the player
   looked — a constant 97%, which is not a number that can be right.
2. **`0/0` was not a bug.** After the fix the counters read zero, which looks like the hook never
   fires. It does: `BlockEntityRenderDispatcher.render` is only called when there is a block entity
   to draw. A frame with `0/0` means the view genuinely contained none. A one-shot log now
   distinguishes the two cases, because the counters alone cannot.

**Verified behaviour after the fix**, sampling while turning on the spot:

| frame sample | culled / considered |
|---|---|
| facing the cluster | 0 / 36 |
| turned away | 34 / 42 |
| side-on | 15 / 41 |
| facing again | 1 / 43 |

The rate now tracks the view, which is what a frustum test is supposed to do. `F11` toggles it so
the effect can be A/B'd, and the ledger shows the per-frame count.

**Deliberate design rule.** `shouldRender` returns "keep" whenever the frustum is unavailable, so
this optimisation can never remove geometry it could not prove was off-screen.

## Explicitly out of scope

- **Adaptive quality loops.** Already well served by existing mods; and without attribution they
  are guessing. FrameLedger should feed them, not duplicate them.
- **Rewriting Sodium's renderer.** Not the point. Sodium is a `recommends`, never a requirement.
