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
| **Honest about its source** | If a trustworthy GPU path is unavailable it falls back to CPU submit timing, and the HUD title says `CPU frame` rather than passing CPU numbers off as GPU time. |
| **Non-invasive** | No renderer rewrite. Only timing calls are added to the hot path. |
| **Read at a glance** | Rows sorted by cost, with proportional bars. |
| **Standalone** | Works with or without Sodium. Sodium is a `recommends`, not a requirement. |

Keys: `F8` ledger · `F9` expand · `F10` measure next option · pause menu cancels a sweep.

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
Phase 3 — block-entity culling driven by measured cost. Not started; see `ROADMAP.md`.

## License

Apache License 2.0 — see `LICENSE`.
