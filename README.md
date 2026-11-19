# FrameLedger

> A Minecraft client mod that measures where your frame budget actually goes — instead of
> guessing which graphics option is expensive.

`Fabric` · `Minecraft 1.21 / 1.21.1` · `Java 21` · standalone (works with or without Sodium)

---

## The problem this exists for

Every performance mod ships options labelled with a performance impact:

```
Performance Impact: HIGH
```

That label is a developer's guess. It has no idea what your GPU is, what resolution you play at,
or what your world looks like. A setting that costs 4 ms in a jungle costs 0.2 ms over an ocean.
An FPS counter tells you that you are slow, never *what* is slow.

FrameLedger answers the second question. It treats the ~16.7 ms of a 60 FPS frame as a budget,
instruments the render passes, and shows you the ledger:

```
GPU frame  11.4 ms
Terrain          8.1  ██████████████████████  71%
Entities         2.0  █████                      18%
Block Entities   0.8  ██                          7%
Sky              0.4  █                           4%
```

Now "turn down entity distance" is a decision with a number behind it, not a guess.

## What it does

| | |
|---|---|
| **Frame-budget ledger** | Per-pass time for terrain, entities, block entities, particles, clouds and sky. |
| **Option cost measurement** | `F10` runs an alternating A/B sweep on a vanilla option and reports **how many ms it actually costs on your machine**, with a 95% confidence interval. |
| **Asynchronous measurement** | GPU path uses `GL_TIMESTAMP` queries in a ring buffer, read 3 frames late, so profiling never stalls the pipeline it measures. |
| **Nested spans** | `GL_TIME_ELAPSED` cannot nest; timestamps can. Block entities are timed *inside* the world pass without conflict. |
| **Honest about its source** | If a trustworthy GPU path is unavailable it falls back to CPU submit timing, labels it `CPU frame`, and warns when the per-pass split is too unstable to be meaningful. |
| **Non-invasive** | No renderer rewrite. Only timing calls are added to the hot path. |
| **Read at a glance** | Rows sorted by cost, with proportional bars. |
| **Standalone** | Works with or without Sodium. Sodium is a `recommends`, not a requirement. |
| **Block-entity culling** | Vanilla only rejects block entities by sphere distance; it never tests the view frustum. This does. `F11` toggles it. |
| **Culling benchmark** | `F7` runs six alternating on/off blocks and reports whether culling actually saved frame time here — with a confidence interval, and "no measurable gain" when it did not. |

Keys: `F8` ledger · `F9` expand · `F10` measure next option · `F11` block-entity culling ·
`F7` benchmark culling · pause menu cancels a sweep.

The expanded ledger shows `BE cull: ON 15/36 skipped`, `BE cull: ON (none in view)` or
`BE cull: OFF`, and the current FPS next to the frame total.

### Should you expect culling to raise your FPS?

Sometimes, and the mod will tell you rather than leave you guessing. `F7` measures it because
toggling `F11` and comparing two FPS readings taken seconds apart cannot answer this: mobs spawn,
chunks load, the sun moves, the GPU may throttle, and any difference is as likely to be that drift
as the culling.

A measured example from development, in a world with 36 block entities of which 15 were culled:

```
Culling benchmark: no measurable difference (7.27 +/- 13.76 ms/frame)
```

The interval spans zero, so the honest answer there is "no gain". Two reasons that were visible in
the same session: 36 block entities is too few to matter, and vsync held the frame rate at 60 fps,
so interval variation came from the frame cap rather than from rendering.

Culling pays off when a scene has many block entities off-screen — a storage room of chests, a hall
of signs or banners behind you. Turn vsync off before benchmarking, and stand somewhere with a lot
of them.

## Why not just use an adaptive-quality mod?

Adaptive mods react to *symptoms* (FPS dropped) by lowering settings. That is a closed loop with a
guess inside it: they do not know which setting caused the drop, so they degrade everything until
the symptom goes away.

FrameLedger instead produces the *attribution* that those mods lack. The two compose: use the
ledger to find the cost, use whatever you like to act on it.

## How the option measurement works

For a target option the mod alternates A/B/A/B… in blocks, discarding the first frames of each
block so the change takes effect, then sampling 40 frames per block over 8 pairs.

**Why alternating rather than "measure A, then measure B":** two blocks taken seconds apart differ
by more than the option — the sun moved, mobs spawned, the GPU may have throttled. Alternating
spreads that drift across both arms so it cancels in the difference instead of being charged to
the option.

**Why a confidence interval and a refusal to report:** a difference smaller than the run-to-run
noise is a coin flip. When the 95% interval straddles zero the mod says *"no measurable
difference"* instead of printing a number that would flip sign if you looked at a different wall.

**Which options are eligible — and why some are not.** Only options that take effect immediately.
This was checked against the bytecode rather than assumed: `ao` (smooth lighting) and
`biomeBlendRadius` look like ideal candidates, but their change callbacks call
`WorldRenderer.reload()`. Sweeping them frame-by-frame would fold a multi-frame reload hitch into
whichever arm was active and produce a confidently wrong number, so they are excluded. Currently
measurable: particles, clouds, entity shadows, entity distance, mipmap levels.

## Verified, not assumed

The measurement infrastructure was validated against the real runtime rather than trusted:

- Mixin injection was confirmed by dumping post-mixin bytecode (`-PmixinDebug`) and inspecting the
  method bodies — not by reading a clean console.
- Sodium 0.6.13 `@Overwrite`s `WorldRenderer.renderLayer`. Injection survival under that
  overwrite was checked explicitly, because a silently-dead injector would report a permanent
  `0.0 ms` terrain row and look like it was merely "not sampling".
- The mod was launched with Sodium absent to confirm it is genuinely standalone.
- Timing was checked against the vanilla draw order, which caught a real bug (below).

### A bug this found: terrain was absorbing the entity pass

Vanilla `WorldRenderer.render` calls `renderLayer` **eight times, non-contiguously**: solid,
cutout-mipped and cutout, then the entity and block-entity loops, then translucent, tripwire,
translucent again, lines and tripwire again.

An earlier version timed a pass as "first start to last end". For terrain that spanned the whole
world pass, so the terrain row swallowed entities and block entities — it reported 11.0 ms / 79%
while entities also reported their own time, and the rows overlapped. Passes are now timed as
**summed segments**, which is what "how much time did this pass take" actually means.

### A limitation this found: the CPU split is not trustworthy
On a GL 3.2 context the mod falls back to CPU submit timing. Six samples from a live session:

| sample | Terrain | Sky | total |
|---|---|---|---|
| 2 | 5.43 | 5.31 | 12.15 |
| 3 | 6.59 | 5.64 | 12.97 |
| 4 | 4.48 | 4.17 | 10.50 |
| 5 | 8.36 | 1.77 | 11.93 |
| 6 | 8.23 | 1.64 | 11.53 |

Terrain and sky move in opposite directions while the total stays flat. This is not noise to be
filtered out, it is a property of the backend: CPU timing measures **submit** time, and when the
driver blocks waiting for the GPU, the wait lands on whichever call happened to block. The total
reflects real waiting; the split between passes does not.

The mod therefore tracks per-pass variability and, on the CPU backend, prints
`split unstable, total is reliable` and colours those bars differently rather than presenting a
breakdown that would not reproduce. Per-option A/B sweeps are unaffected: they compare the same
pass against itself, alternating, so a moving baseline cancels.

### Why the GPU path does not work here, in detail

Minecraft requests a **GL 3.2** context (`GLFW_CONTEXT_VERSION_MAJOR=3`, `MINOR=2` in
`Window`'s constructor), even though the driver on this machine supports 4.6. That request is the
reason `GL_TIMESTAMP` is unavailable: it is core only from 3.3.

Two repairs were attempted and both were measured, not assumed:

1. **ARB extension entry points.** `GL_ARB_timer_query` is advertised, so `ARBTimerQuery` was
   tried. Its startup validation — eight counters plus a readback — **passed**, and the backend
   switched to GPU. Every counter issued during real rendering then failed with
   `GL_INVALID_OPERATION: Query object not found`, 8222 times in one session, while the ledger
   displayed `0.00 ms`.
2. This is why a startup probe is not trusted any more. The GPU path now runs on probation: if it
   produces no sample within 90 frames it shuts itself down for the session and the CPU backend
   takes over. Error count after the fix: 90, exactly the probation window, then clean.

3. **Requesting a 3.3+ context** would be the remaining option. It is deliberately not done: it
   changes the GL version for the entire game, including every other rendering mod loaded
   alongside, to gain a measurement feature. That is a bad risk trade for a diagnostics tool.

The consequence is stated plainly rather than hidden: on this class of machine the total is
meaningful and the per-pass split is not.

## Build

```bash
export JAVA_HOME=/path/to/jdk-21   # JDK 21 is required
./gradlew build              # -> build/libs/frameledger-<version>.jar
./gradlew runClient          # dev client
./gradlew runClient -PmixinDebug   # also dumps post-mixin bytecode to run/.mixin.out
```

Alternatively set `org.gradle.java.home` in `gradle.properties` to a JDK 21 path.
`gradle.properties` pins the Minecraft, Yarn and Fabric API versions.

## Usage

1. Drop the jar in `mods/`. Sodium is optional.
2. Enter a world. The ledger appears top-left once the first samples resolve (a few frames).
3. `F8` hide/show · `F9` collapse/expand · `F10` measure the next option.

A sweep visibly changes your settings for a few seconds and **always restores them** when it
finishes, is cancelled, or when you leave the world.

### Requires a GL 3.3 context

**A real context version of 3.3+ is required.** This cost real debugging time, so it is worth
stating plainly:

Minecraft frequently runs a **GL 3.2 core** context on NVIDIA drivers. The driver still reports
`GL_ARB_timer_query=true`, the `glQueryCounter` pointer resolves, and even a single probe call
succeeds — yet every counter issued during real rendering fails. FrameLedger therefore does **not**
trust the extension flag; it reads the context version back and requires 3.3.

On a 3.2 context the ledger stays silent and logs one line:

```
GL probe: supported=false ctxVersion=3.2 ... arbTimerQuery=true
GPU timer queries need a GL 3.3 context; ledger disabled for this session
```

That is deliberate: showing nothing beats showing fake zeros, and it must never spam GL errors.

## Status

Phase 1 — frame-budget ledger, HUD, controls. Done and verified at the bytecode level.
Phase 2 — per-option cost attribution with A/B sweeps and confidence intervals. Done.
Phase 3 — block-entity frustum culling. Done and verified on a live world; the two bugs it
surfaced are documented in `ROADMAP.md`.

## License

Apache License 2.0 — see `LICENSE`.
