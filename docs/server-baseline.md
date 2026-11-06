# 服务端基线（阶段 0）

这是开发用采样工具，不是服务端优化，也不随正式 JAR 打包。目标版本为 Minecraft 1.20.1、Forge 47.4.28、Java 17。`-PserverBaseline` 使用独立目录 `run-server-baseline/`，不会改动客户端世界。

## 启动和采样

1. 使用 Java 17，在项目根目录运行：

   ```powershell
   .\gradlew.bat runServer -PserverBaseline -PvanillaRendererBench --console plain
   ```

2. 首次启动会生成 `run-server-baseline/eula.txt` 并退出。自行阅读其中链接的 Minecraft EULA；只有同意后才将该文件设为 `eula=true`，然后重新运行上述命令。请勿把包含世界或 EULA 的运行目录提交到仓库。
3. 服务器启动后默认预热 1200 Tick、采样 6000 Tick，结束时日志包含 `Server baseline complete`。采样文件位于 `run-server-baseline/logs/vulkanforge/server-baseline/ticks-*.csv`，每次使用不同文件名。等待完成，再通过服务端控制台输入 `stop`，验证正常存档与退出；若运行环境没有控制台输入，启动时增加 `'-PserverBaseline.stopAfterSample=true'`，采样完成后请求正常停服。再次启动并检查同一世界能正常加载。
4. 分析指定文件：

   ```powershell
   python tools/analyze-server-baseline.py <CSV路径> --expected-samples 6000
   ```

   可用 `'-PserverBaseline.warmupTicks=200' '-PserverBaseline.sampleTicks=600'` 做短时功能检查；分析时相应传入 `--expected-samples 600`。PowerShell 中属性参数需要保留引号。分析脚本会拒绝样本数不符的采样。

采样测量 Forge 服务端 Tick START/END 事件之间的墙钟耗时：不含睡眠和整个服务端循环，也不保证包含其他监听器在这两个事件上的全部耗时。CSV 写入本身也有开销。它不是 CPU 时间、真实 TPS 或游戏全 Tick 的精确测量，不能单独用于断言优化收益；应结合独立 profiler 和 GC 日志。空服静止场景只是启动基线，不能代表多人负载或新区块生成。

## 基准方案

记录硬件、电源策略、操作系统、Java/JVM 参数、Forge 和模组版本及配置、世界种子与世界副本哈希、玩家数量、视距和模拟距离。使用固定的世界副本和操作脚本，保留原始 CSV、完整日志与 profiler 结果；每次开始前恢复同一副本。按交替顺序至少运行三轮，分别记录中位数、P95、P99、超过 50 ms 的数量、GC 和 CPU/内存信息；不要把每轮的百分位数混成逐 Tick 的百分位数。

逐步准备并记录以下场景，确认场景真实产生对应负载后才采样：

| 场景 | 必须固定并记录 |
| --- | --- |
| 空服 / 已生成区块 | 世界、加载范围、是否有玩家、后台任务 |
| 实体和寻路 | 实体种类/数量、出生位置、目标和环境 |
| 方块实体 | 方块种类/数量、输入输出及更新频率 |
| 多人追踪 | 玩家数量、客户端或机器人、路径和速度 |
| 新区块生成 | 起始世界副本、移动路径、种子、生成距离 |

目前只有下述实验性牛群场景；其余场景尚未自动生成。空服不加载普通玩家周围的活动区块；需有玩家或其他明确加载机制才能测实体及区块活动。生成场景之间要恢复初始世界，否则后续运行就不是新生成测试。联网玩家和行为脚本应单独记录。

## 实验性实体场景和 JFR

仅限隔离测试世界，可追加 `'-PserverBaseline.entityWorkload=true' '-PserverBaseline.jfr' '-PserverBaseline.stopAfterSample=true'`。该场景强制加载出生点区块，在地表生成 160 头牛，并在正常停服时移除它们和本次新增的区块加载票据。异常终止时可能残留实体或票据；不要对珍贵存档使用。生物移动、天气和世界状态未固定，不能直接用于严谨 A/B 对照。JFR 从 JVM 启动开始录制，保存在对应运行目录的 `logs/vulkanforge/server-baseline/`；分析时必须按日志时间排除启动与停服阶段。检测实体 Tick 的监听器和 JFR 均有开销。

一次开发探索（预热 200、采样 600 Tick）中，日志确认 160 头牛共执行 128000 次 Tick（包括预热）。采样时间段内的 295 个服务端线程 JFR 执行样本中，`BlockCollisions.computeNext` 出现在 59 个调用栈、位于栈顶 53 次；实体区段查询也有出现。样本具有随机性，不是精确 CPU 百分比，且该场景没有对照组，不能用于证明实际性能收益。碰撞与实体查询应先检查与 Radium 等模组的重叠，再考虑实现。

`python tools/compare-server-radium.py --rounds 3` 从已停止的 `run-server-baseline` 复制世界和 EULA，逐组核对世界哈希；每一轮两组交替顺序，独立保存运行目录、日志、CSV、JFR 和汇总。只在用户已同意 EULA 的测试世界上运行；确保模板服务端已停服。默认每组预热 200、采样 600 Tick，可用 `--warmup` 和 `--samples` 调整。Radium Re-Reforged 0.15.0（CurseForge 文件 9434151）仅在对照组作为开发运行时依赖加载，不打包进 VulkanForge。

2026-11-06 的一组探索结果（同一世界副本、160 头牛、JFR 启用）中，Forge + VulkanForge 的采样区间平均 7.22 ms、P95 16.17 ms；加入 Radium 后分别为 4.93 ms、12.96 ms。两组都记录 128000 次牛 Tick。Radium 组采样期间的 181 个服务端线程 JFR 样本中，实体可推动性查询和区块处理仍可见。两组运行顺序固定、仅一轮，生物行为随机、预热短，而且计时范围不包含完整主循环；数字仅帮助选择后续研究方向，不代表实际服务器提升或模组兼容结论。

后续三轮交替测试中，第一轮基线因并行解析另一份 JFR 受到人为干扰，整轮不纳入比较。保留的两轮中，基线平均 / P95 分别为 7.83 / 17.75 ms 和 6.93 / 16.67 ms；Radium 组为 5.16 / 12.53 ms 和 4.67 / 11.28 ms。各组均为 128000 次牛 Tick。只有两个有效配对，随机行为、短预热、JFR 开销及其他系统负载仍限制结论。

两次 Radium 记录分别取采样阶段内 25 秒：服务端线程执行样本为 154、132 个，其中 `CollisionGetter.findSupportingBlock` 分别出现在 18、15 个调用栈；分配样本为 239、242 个，其中该方法分别出现在 23、28 个调用栈。调用栈样本可重叠，不能相加当作 CPU 占比或精确分配量。可用 `tools/analyze-server-jfr.py <文件> --start <带时区ISO时间> --end <带时区ISO时间>` 重现统计；JFR 时间窗口应根据服务端日志选择。

候选实验：`findSupportingBlock` 使用碰撞迭代器寻找坐标；在完整方块命中分支，迭代器会将碰撞形状平移后传给结果函数，而原版该查询的结果函数只取坐标。开发原型仅对标记过的支撑坐标查询跳过这一次平移，其他调用者保持平移，复杂形状仍走原有相交检查。原型及字节码变换只在 smoke 源码和 `-PserverBaseline.supportBlock` 测试构建中启用；正常构建不注册或包含它。

Radium + 原型的差分检查通过了 2000 次石头、空气、半砖、栅栏、水、蜂蜜块、活塞和灵魂沙等混合形状查询，实际确认跳过 497 次平移。它尚未覆盖自定义模组方块、动态形状、跨区块、世界边界等情况，不能据此声称完全兼容。

2026-11-06 在同一世界副本上，以 Radium 为两组共同基线、交替顺序测试 3 轮（每组预热 200、采样 600 Tick、启用 JFR），结果为：

| 轮次 | Radium 平均 / P95 ms | Radium + 原型平均 / P95 ms |
| --- | --- | --- |
| 1 | 6.316 / 13.064 | 6.847 / 14.441 |
| 2 | 7.332 / 13.596 | 7.271 / 13.420 |
| 3 | 5.434 / 12.509 | 5.122 / 11.991 |

每组牛 Tick 数为 128000。组间结果有正有负，在此场景下尚无稳定收益，因此**不作为正式优化启用**。短预热、随机实体行为、采样范围和 JFR 开销限制结论。原始数据见 `build/reports/server-comparison/eb40f3da-93e1-4168-aea2-c9f8f17ec4c9/`，可用 `python tools/compare-server-radium.py --support-ab --rounds 3` 重测。

对照至少包含同版本 Forge、Forge + VulkanForge、分别加入 Radium Re-Reforged、ModernFix、FerriteCore 及常用组合；分别固定每个文件的版本、哈希和配置。开发采样器依赖 VulkanForge，因此不适合直接充当无 VulkanForge 的对照：跨组使用同一外部 profiler/采样方式，开发 CSV 仅作补充。需要额外采集堆、GC、线程及热点调用栈；例如使用与目标服务器版本兼容的外部 profiler，检查其各组配置一致。

当前尚未提供自动化多人驱动、固定场景生成、裸 Forge 对照、profiler/GC 汇总或兼容模组矩阵，也尚无有效性能结论。完成这些项目并运行多轮前，阶段 0 不应标记为完成。

## 2026-11-06 开发环境启动检查

Windows 11、Java 17.0.17、Forge 47.4.28，`-PvanillaRendererBench` 排除工程中配置的客户端渲染依赖。专用服务器加载 VulkanForge，生成测试世界；在隔离世界中完成 20 Tick 预热、100 Tick 采样。添加自动停服选项后，服务端保存全部维度并退出；再次启动同一存档、完成采样并正常保存退出。CSV 样本数及分析脚本检查通过。

第一次启动检查中，运行环境未开放控制台标准输入，无法发送 `stop`，因此手动终止了隔离的测试服务端；后续两次使用自动停服正常保存。以上仅是启动与工具功能检查，不是性能基准：没有受控负载、对照组、正式预热时长或多轮分析。
