# GL / Vulkan 交接与深度复制优化

## 实现

`SharedWorldRenderer` 的原生 Vulkan 模式不再把不透明深度复制到旧版 overlay 的 `sceneDepth`。原生实体直接使用共享 RenderTarget 的深度，`sceneDepth` 只由旧版 overlay 路径消费；原生帧现在只保留透明目标所需的独立深度副本。旧版 overlay 仍保留原来的复制与实体深度语义。

连续原生后处理通过显式 `PostChain.process()` 范围合并。每个 pass 先完成资源、格式及 Shader 支持检查并进入队列；链结束时按原顺序录制到一个命令缓冲，只在链的入口和出口交接每个目标一次。中间的目标切换仍有真实的 Vulkan layout / 读写依赖，不会跳过 pass，也不会重排透明或混合操作。

遇到不支持的 GL pass 时先提交前面的 Vulkan pass，再将资源归还 GL。正常与异常退出都有范围清理；异常处理保留原始异常，清理错误加入 suppressed。命令录制、分配、编译和上传排空发生在改变 GL 所有权之前，录制失败不会留下未完成的 GL lease。帧结束和 RenderTarget 销毁也会先排空队列。

没有显式 PostChain 范围时维持立即提交，实体/地形合成的真实 GL 边界也不合并。诊断用 JVM 参数 `-Dvulkanforge.noInteropOptimizations=true` 同时恢复旧深度复制和逐后处理提交，便于同批 A/B。

## 三方基准

```powershell
.\gradlew.bat offscreenBenchmarkArgs
python tools/benchmark-interop-optimized.py --java '<Java17>/bin/java.exe'
```

默认 1920×1080、RGBA8 / D32F、两帧槽、每例预热 1000 帧、每轮至少 6 秒，共五轮。每个案例独立 JVM，四种路径及两种负载在每轮随机排序。没有 surface、swapchain 或 present；检测到其他 Java GPU 基准并发时等待或重测。

| 路径 | 实际实现 |
|---|---|
| VANILLA_GL | Minecraft 1.20.1 `ShaderInstance` / `VertexBuffer` 逐 Section 绘制 |
| CUSTOM_GL46 | 生产 `Gl46Terrain`，GPU Driven / Indirect Count / SPIR-V，关闭 Hi-Z |
| VK14_BEFORE | 生产原生 Vulkan 地形、最终合成和后处理，关闭本次优化 |
| VK14_AFTER | 相同生产代码，启用本次优化 |

相同场景清单为 346 个不透明 Section / 98446 个四边形、131 个透明 Section / 18058 个四边形。所有路径统一不透明背面剔除关闭、透明背面剔除开启，使用相同相机、纹理、光照和透明排序。原版的 Shader 与逐 Section 绘制代码保留，仅在基准中统一光栅状态。

TERRAIN 测不透明、透明与最终合成；TERRAIN_POST3 再增加三次相同颜色变换（两个目标交替读写），模拟可以连续执行的后处理链。GL 两种路径使用相同的 GL 后处理实现；VK 使用真实 `NativePost`。这三个 pass 是明确指定的实验负载，不代表所有游戏场景都会运行三个后处理。

GPU 时间线首尾均采用当前隐藏 GL context 的 timestamp，包含必要的跨 API 等待；不混减 GL 与 Vulkan 的时钟。CPU P99、最长帧和最后排空均单列，所有提交帧在结束计时前完成。整卡 GPU 占用由 nvidia-smi 采样，排除每例开头 2 秒和最后 0.3 秒。

## 正确性门槛

每例计时前后审计真实 Section / 四边形清单，捕获后处理前的颜色与深度、最终结果及计时结束后的最终结果。Vulkan 优化前后必须逐位一致；跨 API 保留完整误差数据，而不把不同着色器的结果描述成逐位相同。

跨 API 门槛为 RGB 平均误差 < 1/255、任一 RGB 通道差 > 8/255 的像素比例 < 1%、深度差 > 2e-6 的像素比例 < 0.1%。少量几何边缘误差仍会在报告中列出，不计入统计时段。

计数器的索引为：

| Counter | 含义 |
|---|---|
| 0 | 实际深度复制 |
| 1 | 避免的未使用深度复制 |
| 2 | 合并后的后处理提交 |
| 3 | 合并提交中的后处理 pass 数 |
| 4 | Vulkan 队列提交 |
| 5 | GL 信号量组调用（每组可以包含颜色和深度） |

TERRAIN_POST3 的优化前后，逐例断言实际复制为 2→1、VK 提交为 6→4、GL 信号量组调用为 16→8，后处理 pass 数仍为 3。TERRAIN 不包含后处理，预期只减少未使用的深度复制。

图像、深度、每轮参数、逐帧 CPU 记录、硬件采样及测量时源码/字节码归档保存到 `build/reports/interop-optimized/`。这是实际渲染实现的受控地形/后处理吞吐，未运行世界逻辑、实体、HUD 或窗口呈现，不能当作完整游戏 FPS。

## 回归验证

`PostChainTransformSmoke` 对实际 Minecraft `PostPass` / `PostChain` 应用发布的 coremod，验证字节码，并通过 JVM 执行正常及异常退出路径。`NativePostBatchSmoke` 检查交替读写依赖、单次提交、双帧槽、嵌套范围、GL 回退排空、Shader 拒绝、尺寸变化和销毁。`SharedOffscreenSmoke` 检查旧版共享图像与实体 overlay 的像素、深度和生命周期。

可通过 `.\gradlew.bat smokePostChainTransform :vulkan-lab:smokeNativePostBatch` 重跑新增回归检查。开发运行可用 `-Dvulkanforge.noInteropOptimizations=true` 配合 `runClient` 恢复旧行为；常规启动器则把该参数作为 JVM 参数传入。

验证层可用性以原始日志为准；常规计时关闭验证层。通过像素与计数检查不等于通过了 Vulkan 验证层。


## 五轮结果（2026-10-27）

| 路径 | 纯地形 FPS | 地形加三后处理 FPS | 后处理组整卡 GPU |
|---|---:|---:|---:|
| 原版 GL | 2902 | 1743 | 88.0% |
| GL 4.6 | 3073 | 1844 | 97.2% |
| VK 1.4 优化前 | 1039 | 746 | 88.3% |
| VK 1.4 优化后 | 1113 | 875 | 88.4% |

同轮配对 FPS 提升中位数为纯地形 +7.2%、三后处理 +16.4%。仍保留透明/实体所需的 GL 边界，GL 路径在此负载中更快。GPU 顶点/索引存储保留各自实际实现，这是渲染器架构对照，不是同字节流的纯 API 效率测试。

正式数据包含一次 205.6 ms、一次 941.1 ms 的长帧，未剔除；三组追加 12 秒对照未复现近一秒停顿，优化后为 888～894 FPS。不能据此宣称长尾停顿已解决。原始范围、追加数据和完整误差见 build/reports/interop-optimized/summary.md。

后续跨越地形合成与后处理的显式资源租用区间实验见 [native-lease-interval.md](native-lease-interval.md)。该实验保留 GL 访问边界，游戏未默认开启跨阶段区间；新结果与本节历史样本分开报告。
