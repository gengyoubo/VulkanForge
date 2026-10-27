# 统一 GL / Vulkan Benchmark

本实验按 Baseline、GPU Driven、Full Pipeline 三层分别比较 GL 4.3 / 4.4 / 4.5 / 4.6 与 Vulkan 1.2 / 1.3 / 1.4。每层的输入、Shader 语义、颜色/深度目标与实际提交工作通过检查后，才纳入比较。

这是受控渲染内核，使用合成标准化绘制包，不是 Mekanism 或完整 Minecraft 的 FPS。原有游戏实体捕获、Mek 模型构建、纹理镜像、模组逻辑和真实回退频率没有混入基本绘制测量。`CAPTURE_SOURCE=SYNTHETIC_CANONICAL_TAPE` 写入每份报告，明确输入来源。

## 三层工作契约

| 层次 | GL 与 Vulkan 共同执行的工作 |
| --- | --- |
| Baseline | 1024 个 CPU 逐 Draw 提交；同一 24 顶点 / 36 索引立方体、逐实例记录和 Shader 计算；无额外 GPU 剔除 |
| GPU Driven | 同一 AABB 六面剔除计算 Shader；固定 1024 个五字段 indexed indirect 槽位；每槽包含相同 firstInstance / 几何范围，剔除槽只将 indexCount 写成 0 |
| Full Pipeline | GL 清屏生产 → 主后端剔除/不透明 → 深度复制 → 透明 → GL 深度复制/后处理 → GL 兼容阶段；1280 个主后端候选包与 16 个显式兼容包 |

全部为 1920×1080、RGBA8、D32F、单采样、两帧槽，同一个 GPU UUID。没有窗口 Surface、Swapchain 或呈现，隐藏 GL 上下文仅用于离屏目标、兼容阶段和外部资源交接。

GL 各版本都使用同一个 GL 4.3 兼容内核，不包含 GL 4.6 专有路径的额外功能。Vulkan 的顶点/索引/记录数据上传到设备本地静态缓冲后再开始预热；GL 使用相同静态数据。Shader 主体来自同一份文件，Vulkan 仅转换 NDC 深度范围，完整深度读回用于检查语义相等。

GPU Driven 不使用 Hi-Z、Indirect Count、Mesh Shader 或 API 专有额外剔除。透明命令保持同一稳定顺序，不通过不确定的原子压缩重排。不同层之间允许工作不同，但同一层的两个 API 必须一致。

## Full Pipeline 的资源交接

Vulkan 路径使用项目的 `SharedPresentImage` / `GlInterop`，真实执行 GL → Vulkan → GL 外部内存和 semaphore 交接。主颜色/深度在 Vulkan 主后端完成后交还 GL，供相同 GL 后处理和兼容阶段使用。

GL 路径在同 API 内执行相同语义流程，不额外插入没有用途的 Vulkan 往返。因此两边都有两个资源图边界，而物理跨 API 交接数分别为 0 和 2；这正是本层要测量的架构差异。深度复制均为两次、后处理均为一次。操作次数按实际记录/调用计数，并逐帧断言。

这张受控图不是现有 Minecraft 全部后处理和 RenderType 的覆盖声明。这里的 16 个 GL 兼容包由测试政策显式指定，不是测得的 Mek 自动回退率。

## 怎样确认工作量一致

每个包有唯一绘制 ID。审计组合三种证据：

1. **输入账本**：固定场景、几何、记录数据、共享 Shader 的 SHA-256；明确该层输入的包数。
2. **实际提交参数**：直接 Draw 的 firstInstance、兼容 Draw ID，以及 MDI 真正传入的起始范围和槽位数。每帧检查调用参数指纹保持不变；不是按预期列表伪造提交 ID。
3. **GPU 输出**：读回计算 Shader 实际生成的间接命令，并用硬件 pipeline statistics 查询输入装配顶点数与 vertex shader invocation 数。只读取真实提交范围中的命令。

对输入 ID 检查互斥且完整的划分：

```text
CAPTURED_DRAWS = 主后端非零 Draw + GPU 剔除 Draw + GL 兼容 Draw
DUPLICATED_DRAWS = 0
```

两个帧槽必须生成相同 ID 清单。测量前后再次审计 GPU 命令；测量中的每帧核对硬件顶点计数、实际 API 参数指纹与操作次数。重复 ID、漏画和计时中调用参数变化都有故意注入的负例，审计必须拒绝。

所有 API 在同分辨率预检完整颜色与深度结果，并对照 GL 4.6：颜色每通道误差上限 1/255，深度绝对误差上限 2e-6；同时检查 Baseline 与 GPU Driven 的可见像素一致。误差原值写入 `conformance.json`，不以“达到容差”替代实际差异记录。

## 指标的精确定义

| 字段 | 本实验含义 |
| --- | --- |
| `CAPTURED_DRAWS` | 该层标准化输入包数，来源是合成输入，不是实际游戏 GL 捕获计数 |
| `VK_SUBMITTED_DRAWS` | 审计得到的 Vulkan 主后端非零逻辑 Draw 数；MDI API 调用数与命令槽数单独记录 |
| `GL_FALLBACK_DRAWS` | 实际传给 GL 兼容绘制 API 的 ID 数 |
| `DUPLICATED_DRAWS` | 实际主后端/兼容提交与 GPU 命令中重复的 ID，非零即拒绝 |
| `VK_CULLED_DRAWS` | 实际提交范围内 indexCount=0 的 GPU 间接命令 ID 数 |
| `VK_DRAWN_VERTICES` | GPU 查询到的输入装配顶点数，按索引引用计数，不是唯一网格顶点数 |
| `VK_VERTEX_SHADER_INVOCATIONS` | 硬件计数的顶点着色器实际调用数，独立于上一个指标 |
| `VK_GPU_ENTITY_MS` | Vulkan 主后端不透明/透明通道时间戳区间之和，排除剔除、复制、后处理与资源交接 |

GL 对应的主后端字段使用 `GL_` 前缀。`CORE_API_DRAW_CALLS` 是实际直接 Draw / MDI 调用次数；`INDIRECT_COMMAND_SLOTS` 包含被 GPU 置零的槽位，两者不能当作非零逻辑 Draw 数。

`GPU_TIMELINE_MS` 是 GPU 时间戳的时间跨度。Full Pipeline 的首尾时间戳都由 GL 发出，涵盖跨 API 等待与可能的 GPU 空闲；它不是 GPU 活跃时间百分比。各实体通道的时间戳也可能包含命令流空闲。整卡 GPU 利用率另外用 `nvidia-smi` 采集，包含桌面及其他负载。

FPS 按计时区间完成的帧数计算，区间结束时排空两帧槽。CPU P99 是帧槽等待、查询收集、录制和提交的 CPU 调用区间；不含最后排空，不能等同于显示端到端延迟。颜色/深度和命令读回、图像编码、初始化与预热都在计时区间外。

硬件计数含义依据：[Vulkan pipeline statistics](https://docs.vulkan.org/refpages/latest/refpages/source/VkQueryPipelineStatisticFlagBits.html)、[OpenGL ARB_pipeline_statistics_query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_pipeline_statistics_query.txt)。

## 运行与结果

```powershell
.\gradlew.bat :vulkan-lab:testClasses :vulkan-lab:traceArgs
python tools/benchmark-unified-rendering.py --java <JDK17路径>/bin/java.exe
# 仅验证所有 API 的三层工作，不进行正式五轮计时
python tools/benchmark-unified-rendering.py --java <JDK17路径>/bin/java.exe --preflight-only --output build/reports/unified-preflight
# 单 API / 单层的短测试
.\gradlew.bat :vulkan-lab:benchmarkUnified '-Pbench.api=VK1.4' '-Pbench.level=GPU_DRIVEN' '-Pbench.seconds=1'
```

默认每 API 五个独立 JVM，API 顺序和层次顺序打乱；每层预热 1000 帧，正式测量至少 3 秒。脚本先完成七种 API 的正确性预检，然后开始计时；检测到其他 Java GPU 测试与测量重叠时，保留冲突输出并重测。

汇总：`build/reports/unified-rendering/summary.md`、`summary.json`、`runs.json`。每层保留 `audit.txt`、实际 ID 清单、最终 PNG、压缩原始颜色/深度和像素一致性报告；每次运行的命令与环境也保留。图像和计数不通过的结果不会纳入汇总。

常规计时关闭 Vulkan 校验；是否有校验层以原始日志为准。此环境此前报告校验层不可用，因此像素与计数一致不能被表述为已通过 Vulkan 校验层。

## 本机五轮结果

105 个正式样本全部通过：完整 RGBA 和 D32 输出与 GL 4.6 逐值一致；实际提交/剔除/兼容 ID 一致；重复提交为 0；每帧 GPU 输入装配计数和提交参数指纹保持一致。具体范围：

| 层次 | 输入包 | 主后端非零 Draw | GPU 剔除 | GL 兼容 | 主后端 IA 顶点 | VS 调用 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | 1024 | 1024 | 0 | 0 | 36864 | 24576 |
| GPU Driven | 1024 | 672 | 352 | 0 | 24192 | 16128 |
| Full Pipeline | 1296 | 867 | 413 | 16 | 31212 | 20808 |

1920×1080、RTX 3050 Laptop，五轮 FPS 中位数：

| API | Baseline | GPU Driven | Full Pipeline |
| --- | ---: | ---: | ---: |
| GL 4.3 | 949 | 2452 | 1333 |
| GL 4.4 | 1003 | 2437 | 1752 |
| GL 4.5 | 1163 | 1838 | 1291 |
| GL 4.6 | 1041 | 2347 | 1151 |
| Vulkan 1.2 | 3977 | 4545 | 764 |
| Vulkan 1.3 | 3313 | 5310 | 710 |
| Vulkan 1.4 | 3295 | 4793 | 830 |

Full Pipeline 中，GL 4.6 / Vulkan 1.4 的实体通道 GPU 区间分别为 0.082 / 0.065 ms，而完整 GPU 时间线为 0.287 / 1.053 ms；整卡利用率均值为 35.4% / 42.1%。本实验支持把基本绘制效率与整个资源图的代价分开排查；不能仅用实体通道时间推断完整流程吞吐量。

**FPS 波动较大，不能据此稳定排列相邻 API 小版本。** 例如 Vulkan 1.3 GPU Driven 五轮为 268–11886 FPS，其中一个实际区间显著长于 3 秒阈值。慢样本全部保留，未挑选有利结果；当前日志不能区分长区间来自单次调用、调度还是末尾排空。应把表格看作这次环境下的描述性结果，不将名次外推到实际游戏。

原始命令、逐轮范围、GPU 利用率和 P99 在报告目录；`verification.txt` 记录 105 个样本的完整性核对，`kernel-manifest.json` 和 `runtime-kernels/` 保留正式测量所用内核/Shader 的指纹及字节码。它们不能代替 Mek 实景的模型、捕获、回退及交接统计。
