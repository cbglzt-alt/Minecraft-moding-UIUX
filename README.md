# FrameLedger

> A Minecraft client mod that measures where your frame budget actually goes — instead of
> guessing which graphics option is expensive.

`Fabric` · `Minecraft 1.21 / 1.21.1` · `Java 21` · pairs with `Sodium`

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
| **Frame-budget ledger** | Per-pass GPU time for terrain, entities, block entities, particles, clouds and sky. |
| **Asynchronous measurement** | Uses `GL_TIMESTAMP` queries in a ring buffer. Results are read 3 frames late, so profiling never stalls the pipeline it measures. |
| **Nested spans** | `GL_TIME_ELAPSED` cannot nest; timestamps can. Block entities are timed *inside* the world pass without conflict. |
| **Non-invasive** | No renderer rewrite. Only `glQueryCounter` calls are added to the hot path. |
| **Read at a glance** | Rows sorted by cost, with proportional bars. `F8` toggles, `F9` expands. |

## Why not just use an adaptive-quality mod?

Adaptive mods react to *symptoms* (FPS dropped) by lowering settings. That is a closed loop with a
guess inside it: they do not know which setting caused the drop, so they degrade everything until
the symptom goes away.

FrameLedger instead produces the *attribution* that those mods lack. The two compose: use the
ledger to find the cost, use whatever you like to act on it.

## Verified, not assumed

The measurement infrastructure was validated against the real runtime rather than trusted:

- Mixin injection was confirmed by dumping post-mixin bytecode (`mixin.debug.export`) and
  inspecting `WorldRenderer.renderLayer` — the terrain span is genuinely invoked.
- Sodium 0.6.13 `@Overwrite`s `WorldRenderer.renderLayer`. Injection survival under that
  overwrite was checked explicitly, because a silently-dead injector would report a permanent
  `0.0 ms` terrain row and look like it was merely "not sampling".

## Build

```bash
export JAVA_HOME=/path/to/jdk-21   # JDK 21 is required
./gradlew build          # -> build/libs/frameledger-<version>.jar
./gradlew runClient      # dev client with Sodium loaded
```

Alternatively set `org.gradle.java.home` in `gradle.properties` to a JDK 21 path.
`gradle.properties` also pins the Minecraft, Yarn, Fabric API and Sodium versions.

## Usage

1. Drop the jar in `mods/` alongside Sodium.
2. Enter a world. The ledger appears top-left once the first queries resolve (a few frames).
3. `F8` hide/show · `F9` collapse/expand.

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

Phase 1 (this repository state): the measurement spine, HUD, and controls are implemented and
build-verified, with injection confirmed at the bytecode level.

Deliberately not yet implemented — see `ROADMAP.md` for the reasoning:

- Per-option cost attribution (A/B/A/B ablation with confidence intervals)
- Block-entity culling driven by the measured cost rather than a fixed distance

## License

Apache License 2.0 — see `LICENSE`.
