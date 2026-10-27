# GL-VK 最终颜色交接与 Gate 诊断

本轮先测量剩余跨 API 边界和额外合成工作。没有通过删除 Semaphore、漏画或省略请求的世界内容提高 FPS；省略工作量的诊断单独命名、审计和报告。

## 执行路径

| 路径 | 世界与三次后处理 | 共享图像 | GL 消费 | 目的 |
|---|---|---|---|---|
| GL46_NATIVE | 现有 GL46 地形与 GL 后处理 | 无 | 原生 GL | 同内容架构参考 |
| GLVK_INTERVAL | 当前区间混合，保留两次 VK 全屏合成与一次深度复制 | 原有世界/目标图像 | 本负载无额外 GL 绘制 | 当前混合基线 A |
| VK_NATIVE_OFFSCREEN | 同一原生 VK 内核，直接透明混合与后处理 | 无，全私有 | 无 | 无交接参考 |
| FINAL_COLOR_ONLY | 完整原生 VK 世界与后处理 | 每槽仅最终颜色，共 2 张 | 无 | 最终 GL 操作消融 C |
| FINAL_COLOR_GL | 同上 | 同上，不导出深度 | 一次全屏采样到本地 GL 颜色 FBO | 最终交接模式 |
| FINAL_COLOR_EARLY | 与 FINAL_COLOR_GL 相同 | 同上 | 相同一次消费 | 提前归还 GL 租约的时序候选 D |
| GATE_MINIMAL | 无世界、后处理或 GL 消费；硬件顶点为 0 | 同一适配层、相同尺寸/格式的最终颜色 | 无 | 最小交接 B，不能与世界 FPS 排名 |

所有路径均为隐藏 Context/FBO 离屏实验，不调用 SwapBuffers 或 Present。最终 GL 消费代表窗口呈现前的一次采样工作，没有测到桌面合成器/显示器呈现延迟。

`NativeWorldRenderer` 默认仍为严格无 GL 的私有执行器；实验显式提供 `NativeFrameBoundary`。`FinalColorBoundary` 只替换最终输出颜色，复用 `VulkanSceneCore`、透明绘制和 `NativePost`。没有 CPU 图像搬运，也没有额外 VK→共享图像复制。实际绘制清单、计时内每帧硬件输入装配顶点 699024、完整最终颜色及私有诊断场景深度对齐。最终颜色模式没有声称提供 GL 可读的最终深度。

“交给 GL 一次”指每帧一次最终颜色输出。两槽循环复用仍需要 GL→VK 归还租约以及 VK→GL 输出，各有必要的 wait/signal。每帧 GL 信号量组调用 2 次、组内图像引用 2 次；只有两张颜色图共享、四个外部 Semaphore 对象，深度和中间图保持私有。

此模式不支持中途 GL 模组绘制/回退。实验适配器只接受未触碰共享中间图的地形诊断或规定的三次后处理图；不支持的图必须在改变租约之前拒绝。它没有接入完整游戏默认模式。

## 测量定义

每个完成帧槽获取 VK 时间戳：

```
等待后入口 → acquire → 剔除准备 → 不透明 → 透明 → 三后处理 → release
```

各段使用同一 Vulkan 时钟。`VK_BODY_MS` 包含入口至 release 结束的完整命令缓冲跨度；`CPU_RECORD_MS` 单独包围 CPU 命令录制，`CPU_QUEUE_SUBMIT_MS` 只测 CPU 主绘制提交调用，`CPU_PREPARE_MS` 包含 Shader/资源预检与上传 flush。Fence、query、GL signal/flush/wait CPU 时间另外保存。CPU 数据与 GPU 跨度不混用。

当前混合执行器也增加分段 GPU 查询：不透明、透明、剔除、深度复制、两次合成和三次后处理。计时前后排空已完成槽并读取累计量的差值，不把预热和图像读回混入统计。每帧样本数分别断言为 1、1、1、1、2、3。

GL 时间戳为帧开始、VK 等待后/GL 消费前、GL 消费后。`GL_CONSUMER_MS` 是一次实际 GL 采样的时间戳差；`GL_FRAME_SPAN_MS` 包含该 GL 时间线中的可见空隙和交接依赖。移动 flush 会改变标记何时到达 GPU，时间线跨度可能包含不同的 CPU 供给间隙；它不是 GPU 活跃时间。时序候选的性能仍以端到端 FPS / CPU P99 为准。

## Gate 消融

只有单独的 `GATE_*` 诊断增加探针提交和额外标记；正式吞吐样本不增加探针提交。

| 对照 | 时序变化 | 保留的依赖 |
|---|---|---|
| GATE_NORMAL | 先提交无等待 VK 探针，再 GL signal/flush，再提交等待它的 VK 工作 | 完整 Semaphore/所有权 |
| GATE_MINIMAL | 相同时序，但主提交无绘制、复制和后处理 | 同上，内容保持 |
| GATE_EARLY | GL signal/flush 提前到 CPU 录制之前 | 同上，无新增 host wait |
| GATE_MINIMAL_EARLY | 无绘制的提前归还对照 | 同上 |
| GATE_INPUT_READY | signal 后 glFinish，确认输入就绪才提交探针 | 同上，新增 host wait |
| GATE_OUTPUT_READY | VK Fence 完成后才记录 GL wait 前后标记 | 同上，新增 host wait |
| GATE_BOTH_READY | 同时强制输入、输出就绪 | 同上，新增 host wait |

`VK_GATE_SPAN_MS = VK(等待后首标记) − VK(无等待探针)`。这包括信号可用性、CPU 发信号/提交间隙、驱动调度与资源边界，并非纯 Semaphore GPU 等待，更不是已证明的固定硬件延迟。探针本身增加提交，也可能改变调度；不能用带探针诊断的 FPS 与无探针正式样本混比。

`GL_GATE_SPAN_MS = GL(wait 后标记) − GL(wait 前标记)`。这个可见区间很小，也不能证明 VK→GL 交接免费：等待或延后调度可能发生在前标记可执行之前。需要结合完整 GL 帧跨度、输出已就绪对照和最终消费消融判断。当前环境没有 Nsight Systems，因此没有声称测得完整硬件队列的 idle/上下文切换轨迹。

Gate、VK Body、GL 帧跨度会重叠，不能把它们相加再当成一帧总成本。也不跨 GL/VK 时钟直接相减。强制就绪控制中的 CPU wait 单列；它们是因果定位手段，不能作为性能优化。

最小模式只在初始化时清除一次共享颜色；计时内没有绘制或复制。初始化图像与计时前后的图像逐位核对，初始化颜色允许 UNORM 的 1 单位量化差异，计时后的内容必须与实际初始字节完全相同。统计硬件顶点为 0，避免把无绘制吞吐冒充世界绘制性能。

## 复现与输出

```powershell
.\gradlew.bat offscreenBenchmarkArgs
python tools/benchmark-final-color.py --java '<JDK17>/bin/java.exe' --rounds 5 --seconds 8 --warmup 5000 --gate-rounds 3 --gate-seconds 3 --gate-warmup 1000
```

完整结果在 `build/reports/final-color-gate/`，包括随机顺序、独立 JVM 命令、实际工作审计、完整像素验证、逐帧 GPU 时间线 `gpu-timeline.csv`、CPU 原始帧、分段计数、GPU 温度/频率/占用，以及测量时源码/class 归档。固定世界负载没有实体、HUD、中途 GL 回退或游戏逻辑。

方法依据：[Vulkan 查询与时间戳](https://docs.vulkan.org/spec/latest/chapters/queries.html)、[GL 外部对象与 Semaphore](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects.txt)、[GL Timer Query](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt)。验证层状态以日志为准；本机之前在允许启用层时仍返回不可用，不能把像素验证描述为验证层通过。
