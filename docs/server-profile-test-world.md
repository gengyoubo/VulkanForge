# “测试”存档的服务端线程采样

2026-11-06 16:18:40–16:20:40（日本时间），对用户运行的“胶兽大冒险Ⅱ”单人世界“测试”附加 JFR `profile` 采样。运行环境是 Minecraft 1.20.1 / Forge 47.4.23，整合包包含 VulkanForge、Canary 0.3.3、ModernFix 和 FerriteCore；不是之前的 Radium 测试组合。JFR 录制完成后自动停止，原始文件保存在 `build/reports/live-server-profile/test-world-20261106.jfr`。

**更正：** 本文初版的数字来自 `jfr print` 默认只输出的栈顶 5 帧，下面已按完整栈重算；录制本身没有问题。

服务端线程的完整录制有 2148 个 Java 执行样本，其中 27 个包含 `CollisionGetter.m_285750_`（`findSupportingBlock`，约 1.26%）。为降低刚进入场景、加载任务的影响，单独分析 16:19:10–16:20:40 的 90 秒：1418 个服务端线程样本中有 21 个包含该方法（约 1.48%）。录制只覆盖用户当时的位置和操作。

同一 90 秒窗口中，613 个服务端线程样本包含 `LegacyClientData.materialize`（约 43.2%），557 个包含其过滤 lambda（约 39.3%）；MFCL 命名空间共 661 个（约 46.6%），几乎全部经由 `HostPlayers.livingTick`（655 个）。方块实体阶段只有 24 个样本（约 1.7%），说明这个存档与机器密集的生存存档负载完全不同。

实例中的 `ModernForgeConnectLoader-1.0.1.jar` 的 `LegacyClientData.materialize` 字节码表明：精确 Map 查询未命中后，会遍历 `originals.entrySet()`，按 key、metadata 和 tag 过滤并取第一个结果。工作区之外的本地 MFCL 源码也有相应实现。此处是下一步性能调查的候选；缓存或索引需要先验证原 Map 的更新、键相等性、匹配顺序、未命中和并发语义。没有修改该项目或用户实例。

这些比例是 JFR 记录到的**服务端线程 Java CPU 执行样本比例**，不是方法的精确 CPU 时间或 Tick 墙钟时间。JIT 内联、采样周期、栈缺失或截断及本次游戏负载都会影响统计，不能用它们直接计算精确 Tick 占比。因此它足以说明当前场景下该方法没有显露为主要 CPU 热点，但不能给出精确的 ms/Tick。若需要精确值，应在隔离副本中做低开销的调用计时与计时开关对照，再覆盖其他负载。

分析脚本：`tools/analyze-live-server-jfr.py`；完整窗口和后 90 秒摘要分别位于 `build/reports/live-server-profile/test-world-summary.json` 和 `test-world-steady-summary.json`。后者命令：

```powershell
python tools/analyze-live-server-jfr.py build/reports/live-server-profile/test-world-20261106.jfr --start '2026-11-06T16:19:10+09:00' --end '2026-11-06T16:20:40+09:00'
```
