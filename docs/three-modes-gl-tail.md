# GL46 / GL Tail / 纯 VK 离屏对比

`tools/benchmark-three-modes.py --gl-tail` 将混合执行器切换为当前 GL Tail 计划；不加这个参数仍保留原先的交错执行器，其他实验脚本的行为不变。

GL Tail 通过生产 `MainWindowBackend` 的显式计划参数启用，Vulkan 负责地形，GL 完成不透明/透明颜色与深度合成及后处理。最终目标是私有 GL FBO，不创建 Swapchain。测试使用全屏三角形实现与 `TerrainPresenter` 相同的 alpha 丢弃、深度和混合语义；没有包含 Minecraft 的 ShaderInstance / VertexBuffer Java 包装开销。它用于后端吞吐比较，不能代替完整游戏测试。

三个执行器使用同一份场景输入、实际 Section/四边形清单、相机、纹理、光照和透明排序：

- GL46：生产 `Gl46Terrain`，启用 GPU Driven、Indirect Count 和 SPIR-V。
- GL-VK：生产 GL Tail 地形，每帧一次 Vulkan 地形提交、两次 GL semaphore 组调用、八次组内图像引用；保留一次透明深度复制，GL 执行两次合成和零/三次后处理。
- VK：生产 Vulkan 场景内核，私有图像，每帧一次提交；隔离类加载器禁止加载 GL、GLFW 和 GlInterop，硬件输入装配顶点数检查为每帧 699024。

1920×1080、RGBA8/D32F、两帧槽、无窗口呈现。地形与地形加三次相同颜色变换分别测试；五轮随机顺序、独立 JVM，每例预热 1000 帧，计时至少 6 秒并计入最终排空。测量使用 Vulkan 1.4，不启用可选 NVIDIA 扩展；Vulkan 1.2/1.3 另做输出预检。GL/混合的 GPU 时间由同一 GL 时钟首尾测量，包含交接及调度空隙；纯 VK 使用自己的时间戳，不能跨 API 相减当作固定同步成本。

2026-10-27 的标准场景图集为 32×32。这是扩展 8192 支持后的标准性能回归，不是 8192 图集带宽或显存压力测试；大图集功能另见 [8192 验证](large-atlas.md)。游戏配置保持 GL_VK / GL_TAIL。

```powershell
.\gradlew.bat offscreenBenchmarkArgs :vulkan-lab:testClasses
python tools/benchmark-three-modes.py --java '<JDK17目录>/bin/java.exe' --gl-tail --rounds 5 --seconds 6 --warmup 1000 --output build/reports/three-modes-gl-tail-20261027
```

计时前后均检查实际绘制清单和完整颜色/深度。GL Tail 的计时区间还检查提交、交接、深度复制及无 Vulkan Post/Composite 回放；不因省略负载提高成绩。GL 与 Vulkan 的已有光栅化误差按原容差报告，不宣称三个 API 输出逐位一致。

报告目录包含 `summary.md`、`summary.json`、`runs.json`、每例 CPU 原始帧、阶段计时、颜色/深度误差、GPU 温度/频率采样、并发检测、执行命令和源码/class 快照。另保存实际场景与 SHA256。测试环境没有 Vulkan 验证层，不能将功能通过写成同步验证通过。当前纯 VK 完整游戏仍受覆盖门禁限制，此测试没有测量游戏帧率或呈现延迟。

## 2026-10-27 五轮结果

RTX 3050 Laptop，30 个正式样本全部通过，未检测到其他 Java GPU 测试并发。下表为五轮 FPS 中位数（括号为最小值到最大值），包含全部慢样本。

| 执行器 | 地形 | 地形 + 三次后处理 |
|---|---:|---:|
| GL46 | 2956（2782–3078） | 1833（1724–1870） |
| GL-VK / GL Tail | 1208（1057–1249） | 899（860–934） |
| VK 原生离屏 | 2617（2113–2651） | 1789（1460–1796） |

地形 + 三次后处理的 GPU 时间线中位数分别为 0.524 / 0.955 / 0.531 ms，CPU P99 中位数分别为 1.181 / 2.127 / 1.186 ms。GL Tail 与原生 VK 的场景/最终输出逐位一致；与 GL46 的最大 RGB 平均误差为 0.04377 / 255，最大深度误差为 0.0002263，按既有阈值通过，不能称作三后端逐位相同。

原始结果在 `build/reports/three-modes-gl-tail-20261027/`。本轮没有测量 Vulkan Swapchain、完整游戏或 8192 图集带宽，也不能把后端差额全部归因于跨 API 等待。
