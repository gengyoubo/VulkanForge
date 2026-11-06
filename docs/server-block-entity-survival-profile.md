# 生存存档：服务端线程与方块实体采样

2026-11-06 17:42–17:44（日本时间），对用户运行的“胶兽大冒险Ⅱ”生存存档“生存游玩”附加 JFR `profile`，120 秒后自动停止。用户说明采样期间是多种机器混合运行。实例为 Minecraft 1.20.1 / Forge 47.4.23，包含 Canary、ModernFix、FerriteCore、Mekanism、AE2、Create、MFCL 等；没有修改用户存档或实例。

## 更正：早期数字由截断的栈得出

本文初版的 11.75%（323 / 2750）**不可用**。`jfr print` 默认每个栈只输出 5 帧（`--stack-depth`），当时的脚本只看到了栈顶 5 帧，且没有规整隐藏类的点分命名。录制本身完整，只有后处理有误；`tools/jfr_events.py` 改为按完整栈流式读取。

| 指标（共 2750 个样本） | 截断时 | 完整栈 |
| --- | ---: | ---: |
| 含 `MinecraftServer` 的 Tick 帧 | 60 | 2678 |
| 含 `Level.tickBlockEntities` | 26 | 725 |
| 含方块实体 Tick 包装器 | 297 | 579 |
| 含 `TileEntityMekanism.tickServer` | 256 | 302 |
| 含 `mekanism/` 命名空间 | 368 | 404 |
| 含 MFCL 命名空间 | 136 | 908 |

## 整体归因

以下是服务端线程 Java CPU 执行样本。阶段按调用栈互斥归类，可以相加；其余数字是包含某方法的样本，彼此可能重叠。24 个栈超过 64 帧被截断。

| 互斥阶段 | 样本 | 占比 |
| --- | ---: | ---: |
| 玩家 Tick（由 `ServerConnectionListener.tick` 进入） | 815 | 29.6% |
| 实体 Tick | 806 | 29.3% |
| `Level.tickBlockEntities` | 725 | 26.4% |
| `ServerChunkCache` 区块处理 | 242 | 8.8% |
| 其他 | 162 | 5.9% |

方块实体确实是三大阶段之一，但不是唯一的大头，最大的单一方法也不在其中：

- **`LegacyClientData.materialize`：572 个样本（20.8%）**，几乎全部位于 `Stream.findFirst` 线性遍历中，约为全部 Mekanism `tickServer`（302 个，11.0%）的两倍。调用方是 `HostInventoryStacks.encode`（344）、`HostToolStacks.encode`（99）、`LegacyPlayerInventory` 写回（66）和 `LegacyPlayerArmor.apply`（58）。
- MFCL 命名空间共 908 个样本（33.0%）：玩家 Tick 658、方块实体 128、实体 97。玩家 Tick 阶段主要是 `HostPlayers.livingTick`（324）和 `MirroredItem.inventoryTick`（325）。

## 方块实体阶段

725 个样本中，579 个进入方块实体 Tick 包装器，127 个（占全部样本 4.6%）是循环内每个方块实体都要做的 `Level.shouldTickBlocksAt` → `TickingTracker` 的 `Long2ByteOpenHashMap.get` 查询。

包装器的直接下层：Mekanism 系 ticker 325、MFCL `LegacyBlockEntity.tickServer` 128、`getBlockState` 35、CE 能量管道 16、`BlockEntityType.isValid` 12，其余模组各不超过 9 个。

## Mekanism：热点是结构件数量，不是机器逻辑

Mekanism 为每种方块实体类型注册独立的 ticker lambda，所以栈中 `tickServer` 调用方的 lambda 可以代替机器类型。其中 7 个占 `tickServer` 样本的 75%（227 / 302，全部样本的 8.3%）。用 `tools/count-block-entities.py` 只读扫描存档后，主世界中数量最多的 7 种 Mekanism 方块实体正好是这 7 种，共 9594 个，占主世界 Mekanism 方块实体（11701 个）的 82%：

| 类型 | 存档数量 | `tickServer` 样本 | 其中无下层帧 | Lambda |
| --- | ---: | ---: | ---: | --- |
| `fission_reactor_casing` | 1643 | 38 | 22 | Generators #12952 |
| `fission_fuel_assembly` | 1920 | 38 | 28 | Generators #13373 |
| `ultimate_induction_cell` | 1372 | 34 | 21 | Mekanism #24811 |
| `structural_glass` | 1169 | 34 | 23 | Mekanism #23759 |
| `ultimate_induction_provider` | 1372 | 30 | 24 | Mekanism #24851 |
| `turbine_casing` | 768 | 27 | 17 | Generators #14453 |
| `induction_casing` | 1350 | 26 | 16 | Mekanism #22574 |

Lambda 到类型的对应是推断：`fission_reactor_casing` 和 `induction_casing` 有栈帧支持（分别出现 `TileEntityFissionReactorCasing`、`MatrixMultiblockData`），其余依据注册顺序（已被其他 lambda 下方的具体类锚定）和数量吻合，没有直接证据。

具体的配方、能源和管道逻辑样本都很少：`TileEntityMultiblock.onUpdateServer` 23、`persists`→`SubstanceType.canHandle` 28、`updateHeatCapacitors` 14、`updateRadiationScale` 13、`TileEntityRotaryCondensentrator` 8、`TileEntityFactory` 7。真实机器的单实例开销可能高得多（3 台旋转冷凝器有 8 个样本，约为结构件单实例的百倍，样本量很小），但数量太少，不是这份负载的主要来源。

`TileEntityMekanism.tickServer` 是 static 方法。179 个样本以它为栈顶，其中 165 个在字节码偏移 97，即 `invokevirtual onUpdateServer` 处；这 165 个中 151 个（92%）属于上面 7 种结构件，而其余 lambda 大多能看到具体的 `onUpdateServer` 下层帧。这说明两者走的路径不同，但当时不能断定时间花在哪里：录制时没有 `-XX:+DebugNonSafepoints`，JIT 代码内的样本会归到最近的调试记录上。

**更正：** 后来带 `-XX:+DebugNonSafepoints` 重新录制（见下文“A/B 基线”），`tickServer` 共 460 个样本，其中 282 个（61%）在偏移 4 调用的 `TileComponentFrequency.tickServer` 内，237 个以它为栈顶；偏移 97 的 `onUpdateServer` 只有 70 个，且没有下层帧的只剩个位数。所以上面偏移 97 的集中是归因假象。这个方法在没有频率的方块实体上只是遍历一个空 Map，再读两个布尔字段，代码本身几乎没有工作，因此它又是 `tickServer` 里第一次访问组件对象的地方，开销可能来自冷对象的缓存未命中。这一点仍是推测，需要单独验证。之后的 4 组 A/B 录制都复现了这个排名。

## 限制

JFR 结果是 Java CPU 执行样本，不是每 Tick 墙钟时间或精确 CPU 时间；占比的分母是服务端线程正在执行 Java 的样本，不是 50 ms 的 Tick 预算。存档扫描反映扫描时已保存的区块，不是录制时实际 Tick 的集合；主世界 Mekanism 方块实体都在玩家所在的同一片 8×8 区块内（区块坐标 -8..-1），应当在录制时已加载，但未直接验证。结果只覆盖这两分钟、这个位置和这种机器状态，不能推广到其他世界或负载。

这是单人存档的集成服务端，所有比例都包含 MFCL 的影响；即使完全消除 Mekanism 的开销，整服也不会按相同比例加速。

## MFCL `materialize`：线性扫描

源码（MFCL `LegacyClientData.java`）中，`materialize` 先用完整 `Stack` 查 `originals`，未命中再遍历整个 Map，比较 `key`、`metadata` 和 `tag`。`Stack` 是包含 `count` 和 `name` 的 record，而 `renderSnapshot`、`storedSnapshot` 等路径构造的 `Stack` 的 `name` 固定为空串，`originals` 的条目又都来自 `snapshot()`，其 `name` 取显示名。只要显示名非空，这些路径的第一次查询就必然未命中。

| 线程 | 线程样本 | `materialize` | 其中线性扫描 | 扫描占比 |
| --- | ---: | ---: | ---: | ---: |
| Server thread | 2750 | 572 | 569 | 20.7% |
| Render thread | 3275 | 1606 | 1446 | 44.2% |

- **渲染线程：** 1543 个样本来自 `ClientContentBridge` 的物品着色回调（`itemColor` → `materialize`）。样本占比不等于帧时间，等待 GPU 等原生时间不计入。
- **反射：** `materialize` 内只有 3 / 572 个服务端样本落在 `Method.invoke` 上，现阶段不是瓶颈。
- **锁：** 在 `LegacyClientData` 监视器上，服务端线程阻塞 175 次，共约 2769 ms，中位 11.5 ms，最长 307 ms，主要等待 Render thread；Render thread 阻塞 42 次，共约 1106 ms，最长 183 ms。录制只记录超过阈值的阻塞（实际事件均不低于约 10 ms），真实总量更高。这里有普通 `HashMap` 和共享状态，不能直接删除 `synchronized`。
- **增长：** `originals` 只有 `snapshot()` 一个写入点，没有任何清除或删除。`HostInventoryStacks.encode` 经 `withTag` → `snapshot` 写入，NBT 或数量的每种新值都可能多留一份副本，所以 N 可能随游玩时间增长。N 的实际大小尚未测量。

方案是增加以 `key + metadata + tag` 为键的二级索引，只在 `snapshot()` 写入点维护。匹配条件与原扫描相同；差异是同一变种有多个对象时，由 HashMap 遍历顺序（扩容后会变）改为固定取最早写入的。索引不解决 `originals` 的增长和 `inLegacy` 的全局锁。上述比例不是预期提升幅度。

索引和开关已在 MFCL 工作区实现（未提交，尚未进入用户实例）。2026-11-06 在开发环境用物品审计跑了 `verify` 模式（TC4、Baubles、Extra Utilities，802 个物品变种）：日志中没有不一致或校验失败；最后一次计数为精确命中 7019、回退命中 94983、未命中 104，即 93.1% 的查询未命中精确条目；`originals` 从 1248 增长到 2826，变种从 1248 增长到 1730。审计报告有 2 个失败用例，错误栈中没有 `materialize`，详见 MFCL 的 `docs/materialize-index.md`。

同日又在整合包实例的副本上跑了 `verify`（换入这次构建的 MFCL 1.0.4，离线账户，进入“生存游玩”副本后约 5 分钟）：日志中同样没有不一致或校验失败；最后一次计数为精确命中 30944、回退命中 521907、未命中 35725，94.7% 的查询未命中精确条目，每次核对扫描约 5970 个条目，后四分钟这类查询约每秒 1700 次。`originals` 在进入世界时从 2767 升到 6545，之后四分钟只增加 3 条；这次没有脚本化的物品变动，所以未观察到上文所说的游玩中增长。`verify` 的额外扫描会改变耗时，这次仍未测量性能。系统属性 `mfcl.debug.materializeIndex` 为 `verify` 时同时做全表核对并每 10 秒记录计数，为 `scan` 时恢复原线性扫描作 A/B 对照，详见 MFCL 的 `docs/materialize-index.md`。

## `materialize` 索引 A/B

2026-11-06 在整合包副本（MFCL 1.0.4 含索引，离线账户）上，用同一个 jar 按 `scan`、索引、索引、`scan` 的顺序跑了 4 组。每组从原存档重置，进入世界后预热 120 秒、测量 180 秒，全程站立不操作；关闭垂直同步和帧率上限；JFR 从 JVM 启动起录制并带 `-XX:+DebugNonSafepoints`，测量结束后才转储。MSPT 取自 `MinecraftServer.tickTimes`，帧时间是相邻两次 `RenderTickEvent` 的间隔。

| 指标 | `scan` 第 1 / 4 组 | 索引第 2 / 3 组 | 两组均值变化 | 范围是否重叠 |
| --- | ---: | ---: | ---: | --- |
| MSPT 平均 | 45.3 / 45.0 ms | 38.5 / 36.0 ms | −17.4% | 否 |
| MSPT 中位 | 42.3 / 42.3 ms | 34.2 / 34.6 ms | −18.6% | 否 |
| MSPT P95 | 58.0 / 64.9 ms | 58.4 / 47.8 ms | −13.7% | 是 |
| MSPT P99 | 78.8 / 87.6 ms | 89.8 / 70.9 ms | −3.5% | 是 |
| 超过 50 ms 的 Tick | 13.4 / 18.9% | 8.7 / 3.9% | −60.7% | 否 |
| TPS | 19.36 / 19.94 | 19.45 / 19.61 | 无明显差异 | 是 |
| 平均 FPS | 48.1 / 43.5 | 65.0 / 57.8 | +34.2% | 否 |
| 帧时间平均 | 20.8 / 23.0 ms | 15.4 / 17.3 ms | −25.4% | 否 |
| 帧时间中位 | 16.2 / 18.4 ms | 10.6 / 13.4 ms | −30.6% | 否 |
| 帧时间 P95 | 35.1 / 42.3 ms | 31.1 / 30.2 ms | −20.8% | 否 |
| 帧时间 P99 | 52.8 / 63.5 ms | 56.7 / 42.7 ms | −14.5% | 是 |
| 服务端线程 `materialize` 样本 | 16.5 / 16.0% | 0.3 / 0.2% | | 否 |
| 渲染线程 `materialize` 样本 | 28.4 / 26.3% | 0 / 0 | | 否 |
| MFCL 命名空间样本 | 23.3 / 24.3% | 9.1 / 8.7% | | 否 |

JFR 占比与实测节省基本一致：服务端约 16.3% × 45.1 ms ≈ 7.3 ms，实测少 7.9 ms；渲染约 27.4% × 21.9 ms ≈ 6.0 ms，实测少 5.6 ms。服务端原本就接近 20 TPS，所以 TPS 看不出差异，收益体现为每 Tick 的余量。

限制：每种模式只有 2 组，P95、P99 的范围重叠，尾部延迟的改善不能下结论。监视器阻塞事件很少（服务端两组 `scan` 共 117、485 ms，索引 90、146 ms），波动太大，同样不能下结论。第 1、2 组测量窗口前段各有一次 3–4 秒的尖峰，第 3、4 组没有，原因未确定。第 1 组开始时空闲内存约 3 GB，其余约 8 GB，但第 1 组与第 4 组结果接近。这是站立挂机的负载，生物行为没有固定种子；没有测开关界面或搬动物品。FPS 是取消上限后的数字，正常开着垂直同步或帧率上限时不会同样显现。

更早的一次尝试只完成了 `scan` 一组：第 2 组因 C 盘可用空间低于 400 MB 被脚本中止，且那组用 `jcmd` 在测量开始时启动 JFR，约 3 秒后两个线程同时停顿约 25 秒，因此未采用。本轮改为随 JVM 启动录制后，25 秒停顿没有再现。

四组中 `TileComponentFrequency.tickServer` 都是 `tickServer` 最热的直接调用（342–398 个样本）。索引去掉扫描后，`tickServer` 占服务端线程样本从约 13.7% 升到约 17.0%，这主要是分母变小，并不代表它变慢了。

汇总：`build/reports/live-server-profile/materialize-ab-summary.json`。运行脚本和探针源码在 MFCL 的 `build/instance-verify/ab-tools/`，未入库。

## 下一步（按预期收益）

1. **MFCL `materialize`**（MFCL 项目，不在本仓库）：`verify` 和 A/B 都已完成，下一步是合入索引。之后再看 `originals` 在真实游玩中的增长，以及索引后 `itemColor` 的剩余开销。
2. **录制开关**：之后的 JFR 都从启动起加 `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`（该开关不能运行时开启），否则 JIT 代码内的栈顶归因不可信。
3. **Mekanism 结构件**：先验证 `TileComponentFrequency.tickServer` 的样本是否来自冷对象的缓存未命中，再逐一阅读这 7 种类型的 `onUpdateServer` 继承链，确认每 Tick 必须做的工作；在隔离世界副本中固定结构件数量测单实例开销，并与空闲机器、工作机器和接管道的机器对照。不得假设结构件可以跳 Tick：`ticker`、活动状态、比较器和多方块主控都可能依赖它。
4. **`shouldTickBlocksAt`**（4.6%，原版路径）：同一区块内连续的方块实体重复查询同一个键，这是本仓库范围内、对所有模组都有效的候选。需先确认已有的 `shouldTickBlockPosFilterNull` Mixin 来自哪个模组，并验证方块实体 Tick 期间区块票据变化时的失效语义。

## 复现

原始录制位于 `build/reports/live-server-profile/survival-blocks-90b74439-bde3-4dde-8b3c-0a86b4aea174.jfr`；结果为同目录的 `survival-block-entity-summary.json`、`survival-server-summary.json`、`survival-block-entity-counts.json` 和 `survival-materialize-summary.json`。

```powershell
python tools/analyze-block-entity-jfr.py <录制.jfr>
python tools/count-block-entities.py <世界目录>
python tools/analyze-method-jfr.py <录制.jfr> LegacyClientData.materialize --caller-prefix gengyoubo/mfcl/c --monitor LegacyClientData
```

**不要外传原始录制。** `jdk.JVMInformation` 事件保存了完整启动参数，其中包含 Minecraft access token。
