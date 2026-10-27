# GL46 开销测量与第一轮优化

本轮按 P0 → P1 → P2 处理游戏自己的 OpenGL 4.6 渲染路径。既有 VK 原生路径和 GL-VK 计划不参与这些测量。默认模式保持原配置；GL46 需选择 `GL / GL_4_6`，重启并通过 F8 开启地形接管。

| 优先级 | 范围 | 已落地的改动 |
| --- | --- | --- |
| P0 | `Gl46Terrain` 剔除、间接绘制、可见性统计 | 将诊断原子加法限制在实际统计采样帧；绘制数量的原子计数保持每帧执行。分别测量统计 CPU、剔除 GPU 时间线与间接绘制 GPU 时间线。 |
| P0 | `Gl46Buffers.Ring` 满载同步 | 先零超时检查可复用段，找不到空闲段时才阻塞；未提交的段始终不可覆盖。记录总等待、最坏等待、等待分位数、轮询、忙段、延迟提交和流量。 |
| P1 | Hi-Z 构建与屏障 | 尺寸固定的每层 UBO 只在尺寸变化时创建；移除每帧逐层参数写入和重复纹理绑定。逐层保留 texture-fetch 依赖，强 image-access 屏障集中在覆盖金字塔前。 |
| P1 | 动态实体与箱子实例 | 复用分组容器与矩阵临时数组；相邻且完全相同的 uniform 记录在待提交批次内复用；记录/实例用批量数组复制上传。箱子实例一次预留整个批次，超大批次按预算增长，避免尚未绘制的数据被回绕覆盖。命令环同样支持超大批次。 |
| P2 | Shader 与 GL 状态 | 缓存本模组内置 GLSL/SPIR-V 资源字节并记录加载/链接耗时；在自有通道范围内跳过重复 program、VAO、texture、sampler、深度、混合及剔除设置，记录实际调用与跳过数量。 |

## 测量开关

```powershell
# 原有的实际世界检查；isolatedGl46 使用独立 run-gl46 目录
.\gradlew.bat -Pgl46Smoke -PisolatedGl46 '-Dvulkanforge.gl46.profileGpu=true' runClient
# 正确性检查：额外用真实 GL 查询核对 Minecraft 状态缓存
.\gradlew.bat -Pgl46Smoke -PisolatedGl46 -Pgl46Verify '-Dvulkanforge.gl46.profileGpu=true' runClient
# 流动区块负载，用于观察上传、arena 增长和等待尖峰
.\gradlew.bat -Pgl46Move -PisolatedGl46 '-Dvulkanforge.gl46.profileGpu=true' runClient
```

| JVM 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `vulkanforge.gl46.counterInterval` | `60` | 统计采样帧间隔；`0` 同时关闭诊断原子加法和回读，不影响绘制数量和剔除。 |
| `vulkanforge.gl46.profileGpu` | `false` | 启用剔除、间接绘制和 Hi-Z 的独立 GPU 时间戳。 |
| `vulkanforge.gl46.profileInterval` | `60` | 每个通道的 GPU 采样间隔；至少为 `1`。 |
| `vulkanforge.gl46.diffState` | `false` | 真实入口查询与差异恢复；默认仍保留现有完整恢复，自有通道内的重复设置过滤在两种模式下均生效。 |

GPU 计时使用最多八个待处理槽位，先检查结果可用性，槽位全忙时丢样本；不会为统计等待 GPU。默认关闭时不创建或调用 GL 查询。报告包含有效样本、丢弃、尚未完成的样本、平均值及 P50/P95/P99/max。时间戳反映通道的 **GPU 时间线跨度**，包含该区间内资源/屏障/驱动供给间隙，不能当成纯着色器执行时长。

完成但差值为零的时间戳也计为有效样本，避免安静区间被遗漏后高估平均开销。

开启 `gl46Verify` 会增加真实 GL 查询成本；其 CPU 数字用于正确性定位，不应与未开启校验的帧率直接比较。独立通道的 CPU 计时和 GPU 时间线也不能直接相加为完整帧时间。

## 报告

游戏目录的 `logs/vulkanforge/gl46-report.txt` 在 F8 关闭、渲染器重建与退出时记录完整统计；使用上述隔离参数时位于 `run-gl46`。自动世界检查另写 `gl46-smoke/report.txt` 和截图。

- `TERRAIN_COST`：统计处理 CPU 分位数、Hi-Z CPU、剔除/间接绘制/Hi-Z GPU 时间线，以及 Hi-Z dispatch 和 barrier 次数。
- `TERRAIN_SUBMISSIONS`：绘制帧、统计复制、实际收集样本、忙槽丢弃、间接调用及空层跳过。
- `ENTITY_UPLOADS`：实际 uniform 上传与复用次数、动态顶点/索引字节、分组 CPU 分位数。
- `BLOCK_ENTITY_UPLOADS`：实例字节、实例环增长次数和环统计。
- `GL_STATE_COST`：通道/恢复/设置数量，以及 Minecraft 缓存读取、真实 GL 快照、验证和失效数量。计数为进程累计值。
- `PROGRAM_LOAD`：每个程序的读取/编译/链接 CPU 耗时和资源字节缓存命中；没有复用跨上下文 GL 程序 ID。

每个 Ring 的统计数组保持前四项兼容，完整顺序为：

```text
fences, waits, waitNs, oversized, maxWaitNs, polls, busySegments,
deferred, wraps, bytes, waitP50Ns, waitP95Ns, waitP99Ns
```

等待分位数仅统计发生阻塞的事件，保留最近 4096 个样本；总量和最坏值覆盖该 Ring 的生命周期。零超时轮询耗时不计入阻塞等待。缓存实例环增长后报告当前环，另报增长次数；不要把零等待解释成任意负载都不会等待。所有可用段都仍被 GPU 占用时会继续等待，缓冲预算没有无限扩大。

## 生命周期和正确性边界

uniform 只在当前待提交批次内复用；提交后清除复用引用，避免为已封存的段追加未受 fence 保护的读者。透明实体保持捕获顺序，不透明实体继续按 key 分组。缓存箱子仍只接管已经支持的静态关闭状态，动画及不支持的 RenderType 继续走原来的路径。

状态去重只在自有 `Gl46State` 范围内有效，不跨通道猜测第三方原生 GL 状态。嵌套或未知回调使外层采用保守恢复；异常路径仍恢复状态，保留 VAO 清理和 `BufferUploader.invalidate()`。`GlStateCache` 不用猜测取代失败后的真实查询。

Hi-Z 仍每帧构建，未降低遮挡更新频率或删除相邻 mip 的必要依赖。覆盖前的一次 image-access 屏障负责旧采样到新写入的依赖；mip 间的 texture-fetch 屏障负责 imageStore 结果供后续采样使用。强 image 屏障从逐 mip 减为每次构建一次，**总 barrier 调用仍为 mip 数 + 1**，性能应按实测判断。屏障语义依据 [Khronos glMemoryBarrier 参考](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/gl4/glMemoryBarrier.xml)。

## 验证命令

```powershell
.\gradlew.bat --no-parallel smokeTerrainTuning smokeEntityBatchBoundary smokeGlOffscreen build
```

`smokeTerrainTuning` 对 `counterInterval=0/1/60` 检查真实 GPU 像素、复制频率、空层判断、异步 GPU 计时、原生状态失配/嵌套/异常恢复；额外以三段各 32 字节的环执行 2048 次 GPU 拷贝，核对所有回绕后的数据和未提交段保护。

`smokeEntityBatchBoundary` 检查缓存实例在 vanilla 回退前提交，并提交 20,480 个延迟实例，超过旧环总容量；只有第一部分覆盖目标像素，后续实例位于屏幕外，从像素和计数共同检查回绕没有破坏首批实例。

`smokeGlOffscreen` 使用生产地形与 Shader 检查 GL 4.3、4.4、4.5、4.6 的颜色/深度、镂空、透明、删除、重置和 Hi-Z。新增 65→66 尺寸检查：二者金字塔基础尺寸相同，但深度源尺寸不同，必须重建固定参数并保留最外侧深度。

这些检查及单次场景开销记录都不构成整体 FPS 提升的证据；性能结论需要固定场景、预热后的多轮交错 A/B。

## 2026-10-27 实机记录

RTX 3050 Laptop / NVIDIA 610.78，854×480，渲染距离 4，平地检查含 60 头牛、箱子、水、玻璃与遮挡墙。`gl46Verify` 开启和关闭的两次完整世界检查均通过；以下取关闭状态校验、开启低频 GPU profiling 的第二次渲染器会话，包含移动与转向检查：

| 测量项 | P50 | P99 |
| --- | ---: | ---: |
| 剔除 GPU 时间线 | 14.3 μs | 20.5 μs |
| 不透明间接绘制 GPU 时间线 | 30.7 μs | 766.0 μs |
| Hi-Z GPU 时间线 | 39.9 μs | 95.2 μs |
| Hi-Z CPU 提交 | 10.5 μs | 27.7 μs |
| 可见性统计 CPU 处理 | 0.1 μs | 37.0 μs |
| 动态实体捕获 CPU / 帧 | 23.3 μs | 51.7 μs |
| 实体批次 flush CPU / 帧 | 22.7 μs | 76.7 μs |

每个 GPU 通道收集 121 个样本，丢弃与待完成均为零。该会话上传 277,728 个 uniform 记录，复用 5,383 次；进程累计跳过 274,110 个自有通道中的重复状态设置。该场景的地形 staging、frame、command 和实体环阻塞次数为零。它不能覆盖持续流入区块时的满载行为。

与原版图像比较的平均 RGB 绝对差为 `0.16/255`，单通道差大于 48 的像素比例为 `0.04%`；按现有游戏检查阈值通过，并非逐像素一致。GL 4.3–4.6、统计间隔 0/1/60、Ring 回绕、延迟实例超容量及完整构建均通过。

报告快照：`build/reports/gl46-cost-tuning/world/backend.txt`、`result.txt`。上述为单次诊断结果，没有用两次游戏检查之间的数值差异宣称性能提升；间接绘制的长尾仍需进一步定位。

另一次生成世界飞行检查使用渲染距离 10，400 tick 内移动约 600 格，发布 1,728 个网格，上传约 266.5 MiB；包括预热的峰值驻留为 1,223 个 Section。所有 Ring 的阻塞次数仍为零，顶点 arena 增长 4 次，总 CPU 耗时约 3.1 ms、最坏一次约 1.2 ms。飞行段整个帧间隔的 P50/P99 为 1.054/4.436 ms，`WorldCapture.update` 为 7.8/93.0 μs；这不是与原实现的性能比较。间接绘制 GPU 时间线仍观察到毫秒级长尾，不能将其归因为未发生的 Ring 等待。原始报告保存在 `build/reports/gl46-cost-tuning/move/`。
