# GL 输入生产调度实验

P0 先验证现有 GL 生产工作能否与 CPU Vulkan 录制重叠。当前 `NativePipelineBenchmark` 已在 Vulkan 地形录制前清除 GL 目标，但 GL 的 signal/flush 要等到后续合成/后处理提交附近才发生。

`-Dvulkanforge.earlyGlInputFlush=true` 在这次 GL 颜色/深度清除后、Vulkan 地形录制前调用一次 `glFlush`。完整游戏在宿主 `NativeMainWindow.renderWorld(..., nativeMode=true)` 进入隔离后端之前使用同一个 `GlInterop.flushInputsBeforeRecord()`。该调用仅提交当前已经排好的 GL 工作，不表示 GPU 已完成输入生产，也不会把依赖 Vulkan 输出的 GL 工作移到前面。

默认关闭。实验保留全部 semaphore signal/wait 和图像所有权边界；没有增加 `glFinish`、Fence Wait、探针提交、模拟负载、复制或合成。录制失败前的租约行为保持原状。它验证的是提前提交 GL 工作，不是提前交还租约；此前 Final Color EARLY 的结果不能替代这组测量。

## 复现

使用 JDK17，运行：

```powershell
.\gradlew.bat --no-parallel offscreenBenchmarkArgs :vulkan-lab:smokeNativePostBatch build
python tools/benchmark-input-scheduling.py --java '<JDK17>/bin/java.exe' --rounds 5 --seconds 8 --warmup 5000
# 游戏实验，仅在 Vulkan 原生合成路径前提前 flush；重启后生效
.\gradlew.bat '-Dvulkanforge.earlyGlInputFlush=true' runClient
```

`BASELINE` 与 `EARLY_GL_FLUSH` 均执行 GLVK_INTERVAL：1920×1080、RGBA8/D32F、两帧槽、相同地形与两次合成，分别测试无后处理和三次后处理。每轮随机顺序、独立 JVM，FPS 计入排空。游戏暂未默认采用跨阶段连续区间，因此离屏结果不能视为完整游戏收益。

预检包含 Vulkan 1.2、1.3、1.4。每个正式样本核对实际 Draw ID 清单、计时前后完整 RGBA/D32 与基准逐位一致，以及以下实际计数：

| 计数 | 地形 | 加三次后处理 |
|---|---:|---:|
| Vulkan submit / 帧 | 2 | 2 |
| GL semaphore 组调用 / 帧 | 2 | 4 |
| 组内图像引用 / 帧 | 4 | 8 |
| 深度复制 / 帧 | 1 | 1 |
| 新增输入 flush / 帧，基准 / 实验 | 0 / 1 | 0 / 1 |

`pipeline-audit.txt` 的 COUNTER_0..17 保持原定义；COUNTER_18 是额外 flush 次数，COUNTER_19 是额外 flush 的 CPU 纳秒数。额外 flush 不计入 semaphore 组的 flush 统计。计时启用 `vulkanforge.interopProfile=true`；游戏关闭 profile 时仅计数，不读取 CPU 时钟。

报告位于 `build/reports/input-scheduling/`：五轮配对结果、全部慢样本、CPU 帧数据、分段计时、图像与误差、源码/class 的 SHA256 归档、场景 hash 和整卡温度/频率/占用采样。计时关闭 validation，像素和计数检查不代表通过同步验证层。

提前 flush 会改变 GL 时间戳何时进入 GPU 队列；完整 GL GPU span 可能包含不同的 CPU 供给间隙。主要验收端到端 FPS、CPU P99 及稳定性，不能仅凭 GPU span 变短启用。

P1 继续以没有中途 GL 消费者的明确区间为前提研究私有中间图像。P2 单独测量命令录制、descriptor 更新和提交成本，不混入本轮时序实验。

## 2026-10-27 五轮结果

RTX 3050 Laptop；每例预热 5000 帧、计时至少 8 秒，20 个正式样本共完成 165669 帧。并发监测未发现其他 Java GPU 基准，全部慢样本保留。实际工作量 hash 为 `3e62644a84ab2279ff89f6c72bd61d116747ca9607aa9cdb387cd4eeeab55b6b`。

| 负载 | 基准 FPS 中位数 | 提前 Flush FPS 中位数 | 同轮 FPS 变化中位数 | 基准 → 实验 CPU P99 ms |
|---|---:|---:|---:|---:|
| 地形 | 1147.430 | 1148.881 | +0.17% | 1.6681 → 1.7117 |
| 地形加三后处理 | 923.578 | 923.370 | +0.11% | 2.2658 → 1.6399 |

配对变化的中位数与两列 FPS 中位数之比是不同统计量。地形五轮 FPS 配对范围为 -2.81%～+1.70%；三后处理为 -0.84%～+2.38%，后两轮均略有回退。当前实验没有显示明确吞吐收益，保持默认关闭。

三后处理的 CPU Record 轮间中位数为 0.1209→0.0836 ms，CPU Submit 为 0.0487→0.0342 ms；额外 flush 的 CPU 开销为 2.11 µs/帧。P99 在前三轮下降、后两轮分别增加 0.0193 和 0.0584 ms，不能声称每轮长尾均得到改善。地形的额外 flush 为 3.33 µs/帧，CPU Record 中位数反而为 0.0792→0.1016 ms。这些 CPU 调用区间变化没有转化为明确的端到端吞吐收益，不能仅据此宣称录制优化已经完成。

三后处理的 VK 两次提交间隙轮间中位数为 0.2604→0.2357 ms，但两次提交 Body 合计为 0.8032→0.8339 ms，完整 VK envelope 为 1.0714→1.0696 ms。上述各列分别取中位数，不能直接相加做记账；逐样本 envelope 的记账误差绝对值均小于 1e-6 ms。没有将提交间隙称为纯 semaphore 等待或硬件 idle。

完整 GL GPU span 中位数，地形为 0.8565→0.8550 ms，三后处理为 1.0564→1.0734 ms；仍应结合端到端 FPS 解读，不能跨 API 时钟相减。

完整构建、原有 `smokeNativePostBatch`（嵌套区间、GL 回退、Shader 拒绝、销毁与缩放）、两类负载的 Vulkan 1.2/1.3/1.4 预检均通过。全部正式样本的颜色和深度逐位匹配固定基准，实际提交/交接/复制计数不变。未运行完整 Forge 世界 A/B；本轮不对完整游戏性能作结论。验证层不可用。

下一项输入时序实验应识别真正的最后一次 GL 图像访问，研究提前 signal/flush 与录制失败恢复；本次没有测试提前 signal，结果不否定该方向。不能把 `flushPosts()` 中的 `releaseGL()` 直接挪到录制之前：现有路径依赖“录制成功后才改租约”的失败恢复保证。
