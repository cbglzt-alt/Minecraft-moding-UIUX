# FrameLedger

> 一个 Minecraft 客户端模组，用来测量你的帧预算究竟花在了哪里 —— 而不是靠猜哪个图形选项昂贵。

`Fabric` · `Minecraft 1.21 / 1.21.1` · `Java 21` · 与 `Sodium` 搭配使用

---

## 这个模组为何存在

每个性能模组都会给选项标注性能影响：

```
Performance Impact: HIGH
```

那个标签只是开发者的猜测。它不知道你的 GPU 是什么、你玩什么分辨率、你的世界长什么样。
一个在丛林里要花 4 ms 的设置，在海洋上只花 0.2 ms。
FPS 计数器告诉你"你慢"，但从不告诉你"*什么*慢"。

FrameLedger 回答第二个问题。它把 60 FPS 下约 16.7 ms 的一帧看作一份预算，
对渲染通道进行插桩，然后把这份账本展示给你：

```
GPU frame  11.4 ms
Terrain          8.1  ██████████████████████  71%
Entities         2.0  █████                      18%
Block Entities   0.8  ██                          7%
Sky              0.4  █                           4%
```

于是"调低实体距离"变成了一个背后有数字支撑的决定，而不是猜测。

## 功能

| | |
|---|---|
| **帧预算账本** | 逐通道的 GPU 耗时，覆盖地形、实体、方块实体、粒子、云与天空。 |
| **异步测量** | 在环形缓冲区中使用 `GL_TIMESTAMP` 查询。结果延迟 3 帧读取，因此性能分析永远不会阻塞它所测量的管线。 |
| **可嵌套的时间跨度** | `GL_TIME_ELAPSED` 无法嵌套；时间戳可以。方块实体在世界通道*内部*计时而不会冲突。 |
| **非侵入式** | 不重写渲染器。热路径上只增加了 `glQueryCounter` 调用。 |
| **一眼可读** | 行按开销排序，并配有按比例绘制的条形。`F8` 切换显示，`F9` 展开。 |

## 为什么不直接用一个自适应画质模组？

自适应模组的做法是对*症状*（FPS 下降）做出反应，去降低设置。那是一个内部含猜测的闭环：
它们不知道是哪个设置导致了下降，于是把所有东西都降级，直到症状消失。

FrameLedger 则产出这些模组所缺少的*归因*信息。两者可以组合使用：用账本找出开销，
用你喜欢的任何方式去处理它。

## 经过验证，而非假设

测量基础设施是对着真实运行时验证过的，而不是直接信任：

- 通过导出插桩后的字节码（`mixin.debug.export`）并检查 `WorldRenderer.renderLayer`，
  确认了 Mixin 注入确实生效 —— 地形时间跨度是真实被调用的。
- Sodium 0.6.13 用 `@Overwrite` 覆盖了 `WorldRenderer.renderLayer`。我们显式检查了在该
  覆盖之下注入是否存活，因为一个静默失效的注入器会一直报告 `0.0 ms` 的地形行，
  看起来却像是"只是没在采样"。

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21   # 需要 JDK 21
./gradlew build          # -> build/libs/frameledger-<version>.jar
./gradlew runClient      # 带 Sodium 的开发客户端
```

也可以在 `gradle.properties` 中把 `org.gradle.java.home` 设为 JDK 21 的路径。
`gradle.properties` 同时固定了 Minecraft、Yarn、Fabric API 与 Sodium 的版本。

## 使用

1. 把 jar 放入 `mods/`，与 Sodium 并列。
2. 进入世界。首批查询解析完成（几帧）后，账本会出现在左上角。
3. `F8` 隐藏/显示 · `F9` 折叠/展开。

### 需要 GL 3.3 上下文

**要求真实上下文版本 ≥ 3.3。** 这一点在开发机上实测踩过坑，值得说明：

Minecraft 在 NVIDIA 驱动上常跑 **GL 3.2 core** 上下文。此时驱动仍会报告
`GL_ARB_timer_query=true`，`glQueryCounter` 函数指针也能解析，甚至单次探针调用会成功，
但在真实渲染中每一次调用都失败。因此本模组**不信任扩展声明**，只认读回来的上下文版本。

在 3.2 上下文下，账本会完全静默并在日志中写一行：

```
GL probe: supported=false ctxVersion=3.2 ... arbTimerQuery=true
GPU timer queries need a GL 3.3 context; ledger disabled for this session
```

这是有意为之：宁可什么都不显示，也不显示假的零值，更不刷 GL 错误。

## 状态

第一阶段（即本仓库当前状态）：测量主干、HUD 与操作控制均已实现并通过构建验证，
注入已在字节码层面得到确认。

有意尚未实现的部分 —— 理由见 `ROADMAP.md`：

- 逐选项的开销归因（带置信区间的 A/B/A/B 消融实验）
- 由实测开销（而非固定距离）驱动的方块实体剔除

## 许可证

Apache License 2.0 —— 见 `LICENSE`。
