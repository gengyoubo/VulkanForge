# 原生 VK 性能诊断（2026-10-29）

本轮确认了当前实现的重复工作与固定开销。结果支持优先改造 VulkanForge 的资源/提交架构；不能把先前的 223 FPS 当成 RTX 3050 或 Vulkan API 的性能上限。本轮增加计量和测试，没有实现静态 GPU 网格缓存，也没有以删绘制或跳过等待来制造性能收益。

## 截图与对照范围

用户提供的 Fabric VulkanMod 截图显示 913 FPS、RTX 3050 Laptop、610.78 驱动、Minecraft 1.21.1、Java 21，视距行显示 `D:3`。本项目对照为 Minecraft 1.20.1 / Forge / Java 17、视距 8，因此不直接计算两者的性能比。截图中的 `Device Memory` 是该渲染器的统计口径，也不与 nvidia-smi 的整卡显存数字直接比较。

[VulkanMod 官方 Wiki](https://github.com/xCollateral/VulkanMod/wiki) 将它描述为替代原版引擎的 Vulkan 体素渲染引擎，而非原版 OpenGL 引擎的直接移植。这可以帮助确定架构方向；本轮没有复制其实现代码。

## 分辨率筛选

同一生成代码准备种子 1234 的平坦世界，60 头 NoAI 牛、箱子、水、玻璃、树叶，固定相机；Fancy、视距 8、模拟距离 5、云关闭、mipmap 0，HUD 和手持物开启。两个尺寸均使用无边框窗口、VSync 关闭、`presentMode=0`、不限帧。每项独立游戏 JVM，预热后测 3 个 10 秒区间；只开启计数，不开启 CPU/GPU 分项计时和校验层。

| 指标 | 854×480 | 1920×1080 |
| --- | ---: | ---: |
| FPS 中位数 | 231.1 | 233.2 |
| 三个区间 FPS | 226.3 / 231.1 / 242.9 | 221.6 / 233.2 / 247.9 |
| Draw / 帧 | 185.09 | 184.98 |
| 顶点与索引拷贝 / 帧 | 5.578 MiB | 5.578 MiB |
| 未更新的地形顶点重传 / 帧 | 4.627 MiB | 4.627 MiB |
| 渲染线程 Java 堆分配 / 帧 | 2.515 MiB | 2.522 MiB |
| 渲染线程 CPU 时间 / 墙钟时间 | 62.4% | 61.1% |
| 整卡 GPU 利用率均值 | 37.4% | 46.4% |
| GPU SM 时钟均值 | 1495 MHz | 1848 MHz |

像素数量增加约 5.06 倍，FPS 中位数变化约 0.9%，小于区间波动。几何数据量与提交次数基本相同，支持固定工作主导、并非主要受像素填充限制的判断。但这不是锁频交错实验；GPU 动态时钟不同，不能把分辨率筛选单独当成 CPU 瓶颈的最终证明。

CPU 百分比来自 JDK ThreadMXBean，表示渲染线程相对于一个逻辑 CPU 的执行时间，不是整机 CPU 利用率。本轮没有观察到“渲染线程占满一个核心”的证据。显卡利用率来自 nvidia-smi 整卡采样，仅取实际计时区间内的样本（分别 58 / 57 个），不能全部归属某一个 Vulkan 通道。

## P0：重复传输已确认

`NativeDeviceState` 在 `glBufferData` 与映射写入完成时记录逻辑缓冲版本，并按帧识别地形缓冲再次提交。地形分类使用原版着色器的 `ChunkOffset`；统计单位是地形 VBO 的绘制提交，不冒充唯一 Section 数量。

在稳定测试区间，每帧 150 次地形 VBO 提交全部没有发生缓冲更新，却仍重新写入 GPU 当帧缓冲，顶点数据为 4.627 MiB/帧。

代码路径也直接证实了这一点：

- `NativeDeviceState.draw` 从 CPU 镜像取得整个顶点数组，每次用 `Arrays.copyOfRange` 创建索引切片，再打包绘制参数。
- `NativeGameBackend.flush` 对每个 draw 重新执行顶点/索引 `ByteBuffer.put`，没有按逻辑缓冲及版本保存常驻 GPU 网格。
- 每帧约 185 个 `vkCmdDrawIndexed`，并对应约 185 次描述符集分配和更新。185 这个数字本身不能证明 Draw 过多，问题在于每次提交附带了重复的资源和状态处理。

整个渲染线程约分配 2.5 MiB Java 堆对象/帧，即该负载下约 0.58 GiB/秒。这个数字包含 Minecraft、兼容适配和后端，不能全部归为 `NativeDeviceState`，也不包含原生内存或 GPU 显存。

## CPU/GPU 分项

另外启动一次 1080p 的完整计时，测 1 个 10 秒区间、2116 帧。开启计时后为 211.5 FPS，平均帧周期 4.728 ms；这一结果只用于诊断，不替代上方计时关闭时的 FPS。计时开关本身会增加开销。

| 指标 | 平均每帧 |
| --- | ---: |
| 渲染线程 CPU 执行时间 | 3.175 ms |
| `NativeDeviceState.call` 墙钟时间（含下游调用） | 0.540 ms |
| 其中 draw 适配 | 0.316 ms |
| `NativeGameBackend.flush` 墙钟时间（含子项） | 1.187 ms |
| 其中几何拷贝、uniform 打包与写入 | 0.501 ms |
| 其中描述符分配/更新 | 0.071 ms |
| 其中场景 QueueSubmit | 0.046 ms |
| `vkQueuePresentKHR` CPU 墙钟时间 | 1.650 ms |
| acquire | 0.012 ms |
| 场景 fence 等待 | 0.009 ms |
| 呈现提交 fence 等待 | 0.0024 ms |
| WSI presentation fence 等待 | 0.0015 ms |
| GPU 场景命令时间戳跨度 | 1.050 ms |
| GPU 最终采样/呈现命令时间戳跨度 | 0.0479 ms |

每帧还有约 1756 次兼容入口调用、185 个描述符集，以及 2.094 次 QueueSubmit（场景 1、呈现 1、纹理上传批次约 0.094）。两个 GPU 通道各取得 2116 个有效样本；只在现有 fence 完成后读取查询，不新增 GPU 等待。

以上不能逐行相加：draw 适配属于适配总时间，拷贝/描述符/submit 属于 flush，CPU Present 内部可能与 GPU 执行和驱动等待重叠。GPU 场景计时不包含独立纹理上传提交，Present 调用耗时也不能直接解释成全部是 GPU 渲染或 fence 等待。

由此需要修正初始猜测：显式 fence/acquire 的直接等待很小，**不能把问题简单归因于 `vkWaitForFences`，更不能直接删除它们**。当前单份场景命令缓冲和资源、固定复用 slot 0 的设计仍限制流水线组织；但最大的呈现侧 CPU 墙钟耗时实际出现在 `vkQueuePresentKHR`。应连同驱动/WSI 和帧资源轮转一起定位。

GPU 也不是没有负担（场景约 1.05 ms），但目前证据更支持 CPU 资源转换、命令记录及呈现串行组织共同限制吞吐，而非 RTX 3050 的像素渲染能力已经达到上限。

## 按测量调整的改造顺序

1. **静态顶点/索引常驻 GPU**：以逻辑缓冲 ID + 版本维护 GPU 资源，只在真正变化时上传；删除、区块卸载、排序索引更新及资源重载必须正确处理。动态数据保留独立上传环，不能按 ID 原地覆盖在途数据。
2. **常规地形的原生提交路径**：减少每帧 CPU 索引切片、属性/状态数组重建和重复 uniform 打包，逐步绕过兼容状态模拟。保留现有转换路径处理尚未迁移的模组绘制，不引入 OpenGL 回退。
3. **帧资源与呈现调度**：针对 Present 的 1.65 ms 进一步追踪，并在明确生命周期后引入多份场景命令/附件/描述符资源。只取消等待会破坏当前资源复用安全。
4. **同状态地形合批与描述符复用**：以原版顺序语义为边界做 MDI/批次化。描述符分配更新当前约 0.071 ms，收益不能仅凭调用次数推断，更不能任意重排半透明和模组绘制。

本轮是同一当前 VK 实现的分辨率诊断，不是 VulkanMod 对照，也不是完整画面等价的后端排名。此前报告中“玻璃、箱子缺失”的表述不准确：重新检查保存的 VK 截图，两者均可见；这也不能代替逐像素等价验证。后续优化必须同时验证画面和工作量，不能把漏画当成性能收益。

## 复现与验证

```powershell
# 只开计数，适用于分辨率与 FPS 测试
.\gradlew.bat -PgameFps -PfpsMode=VK -PnativeDiagnostics -Pfps.width=854 -Pfps.height=480 '-Pfps.output=build/reports/native-diag-new/low' runClient
.\gradlew.bat -PgameFps -PfpsMode=VK -PnativeDiagnostics -Pfps.width=1920 -Pfps.height=1080 '-Pfps.output=build/reports/native-diag-new/high' runClient
# CPU/GPU 分项；应与上述 FPS 数据分开使用
.\gradlew.bat -PgameFps -PfpsMode=VK -PnativeProfile -Pfps.rounds=1 -Pfps.width=1920 -Pfps.height=1080 '-Pfps.output=build/reports/native-diag-new/profile' runClient
```

两个计量开关默认关闭。`-PnativeProfile` 同时开启 `nativeDiagnostics`。未开启 CPU 计时的 JSON 中相应 `Ns=0` 表示没有采集，不代表该操作真实耗时为零。报告使用区间开始/结束的计数差，避免把启动、着色器编译与预热计入稳定阶段。

原始报告在 `build/reports/native-diagnosis-20261028/`：

- `854x480/`、`1920x1080-retry/`：各三个有效区间；`resolution-summary.json` 为汇总。
- `profile-1920x1080/`、`profile-summary.json`：分项原始计数与平均值。
- `telemetry.csv`、`telemetry-retry.csv`：整卡采样。第一次 `1920x1080/` 因界面中断失败，整个尝试排除，不参与汇总。
- `results.json`、逐帧 CSV、`scene.json`、Swapchain 截图与后端报告保留在各子目录。

新增计量已经通过编译、无 GL 着色器检查、实际游戏主菜单，以及启用 Vulkan 同步校验的完整世界检查（含背包、缩放、资源重载、Fabulous、进出世界和关闭）。完整构建通过，`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。这验证计量的生命周期，不意味着已修复上述渲染质量问题。
