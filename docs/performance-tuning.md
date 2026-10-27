# 地形调优与后续瓶颈验证（2026-10-27）

ImmediatelyFast、Embeddium、Flywheel、Nvidium 与 Iris 的固定版本源码对照，以及缓存实例 fallback 边界修复见 [渲染参考记录](rendering-references.md)。

Vulkan 大量实体的捕获/回放分项、去掉合批中间数组的实测和实际场景诊断参数见 [实体性能记录](entity-performance.md)。

按相同输入、GPU 实际提交与颜色/深度结果核对的三层跨 API 实验见 [统一 Benchmark](unified-benchmark.md)。

GL 输入生产提前 flush 的 P0 独立时序实验、开关与配对验证见 [输入调度实验](input-scheduling.md)。

Vulkan 地形批次、GL 兼容绘制与最终 GL 输出的可选执行计划见 [GL 尾部兼容模式](gl-tail-compatibility.md)。

进一步拆分外部共享图像交接、兼容 Draw、深度复制和交接次数的实验见 [A / B / C / D 对照](interop-ablation.md)。

原生 Vulkan 的深度复制裁减与后处理提交合并，以及原版 GL / GL46 / VK14 对照方法见 [交接与复制优化](interop-optimization.md)。

## 已接入的运行时优化

| JVM 属性 | 默认值 | 行为 |
| --- | --- | --- |
| `vulkanforge.gl46.counterInterval` | `60` | 每 N 个地形帧尝试复制一次 GPU 统计；`1` 每帧尝试，`0` 禁用统计 |
| `vulkanforge.gl46.skipEmptyLayers` | `true` | CPU 驻留网格数为零时跳过该层间接绘制 |
| `vulkanforge.gl46.diffState` | `false` | 查询真实通道入口状态，跟踪通道内部修改，仅恢复变化的状态 |

采样以零超时 fence 轮询确认 GPU 复制完成，槽位忙时丢弃统计，不等待 GPU。GL 4.3 的 CPU 镜像也只在 fence 完成后刷新。`cullStats()[0]` 表示实际采样数，统计平均值必须除以它；采样频率不控制地形或剔除。

空层判断只维护各槽位驻留的层位图，在网格替换、删除和世界重置时更新。驻留但被 GPU 全部剔除的层仍提交 Indirect Count，避免回读可见数量。

差异恢复没有跨通道复用假定可靠的影子状态。入口读取真实 GL 状态，发现 Minecraft 缓存不一致时使缓存失效；通道内部所有受保护修改走跟踪函数。未知原生回调须调用 `Gl46State.invalidateCurrent()`，嵌套通道会使外层采用保守恢复；异常退出也恢复。保留 `BufferUploader.invalidate()` 和 VAO 清理。该模式额外支付入口查询成本，当前结果不足以默认启用。

## 同场景五轮 A/B

RTX 3050 Laptop，1920×1080，隐藏上下文、单 FBO，无屏幕呈现。每项 5 个独立 JVM，预热 2000 帧、每次至少测量 6 秒，分组随机顺序。所有实际绘制清单一致：346 个不透明 Section / 98446 个四边形，131 个透明 Section / 18058 个四边形；不透明部分只有 Solid 有网格。Minecraft 状态缓存预先与实际 GL 对齐。

原源码基准固定为 `89e19184da4671d9bf92719986a22d9cb0ce4914`，四个生产类独立编译覆盖，避免把新安全回读路径冒充旧实现。

| 配置 | FPS 中位数 | GPU ms/帧中位数 | GPU 占用均值 | 同组配对 FPS 变化中位数 |
| --- | ---: | ---: | ---: | ---: |
| 原源码 | 3837 | 0.256 | 98.8% | — |
| 每帧尝试采样，fence 确认 | 3876 | 0.254 | 98.9% | +0.9% |
| 每 60 帧采样 | 3931 | 0.250 | 99.8% | +2.4% |
| 仅跳过空层 | 3916 | 0.251 | 100.0% | +2.2% |
| 仅差异恢复 | 3834 | 0.257 | 99.7% | +0.9% |
| 默认：60 帧采样 + 空层跳过 | 3939 | 0.249 | 100.0% | +2.7% |
| 三项全开 | 3877 | 0.254 | 98.5% | +1.8% |

单项开关以“每帧尝试采样，fence 确认”为共同基础。配对变化与两列中位数的比值不是同一种统计量。差异恢复减少约一半恢复调用，但 FPS 没有稳定的额外收益。空层收益依赖内容，三层都存在网格时不会跳过。GPU 占用是 nvidia-smi 整卡读数；以上是地形吞吐量，不是完整游戏 FPS。

原始输出：`build/reports/terrain-tuning/`，包括打乱顺序、命令、逐轮结果、实际绘制清单和 GPU 采样。

```powershell
.\gradlew.bat --no-parallel smokeTerrainTuning smokeGlOffscreen build
.\gradlew.bat offscreenBenchmarkArgs
python tools/benchmark-terrain-tuning.py --java <JDK17路径>/bin/java.exe
# 实际游戏 A/B 示例：每帧尝试统计、保留空层、启用差异恢复
.\gradlew.bat '-Dvulkanforge.gl46.counterInterval=1' '-Dvulkanforge.gl46.skipEmptyLayers=false' '-Dvulkanforge.gl46.diffState=true' -Pgl46Smoke runClient
```

脚本依赖现有 `build/reports/offscreen-performance/matched-scene.bin` 与相邻 `expected-workload.txt`；运行前须先用项目的相同内容对照流程生成它们。测量期间检测其他 Java GPU 测试，发生重叠则丢弃并重测。

## Bindless Texture：GPU 可行性已验证，生产实体尚未接入

`GlTerrainOptimizationSmoke` 在本机确认支持 ARB/NV Bindless，并用 SSBO 保存两个驻留纹理句柄，在一次 MDI 中分别采样红、蓝纹理，读回左右像素验证结果。测试会在 GPU 完成后撤销驻留和释放对象；不支持扩展的机器报告跳过。

该检查不代表实体合批已有 Bindless 收益。正式接入还需处理版本化纹理、资源重载、退役 fence、采样器组合和无扩展回退。不能直接向所有原版纹理取句柄：句柄创建后，相关纹理的参数、尺寸和格式不能再更改。应由自有纹理视图/镜像管理这些约束，按更新代次和 GPU 完成情况退役。依据：[Khronos ARB_bindless_texture 规范](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_bindless_texture.txt)。

## 方块实体缓存：已有箱子白名单，扩展须分离静态与动画部分

生产代码已对原版 `ChestRenderer` 本身、关闭盖子的受支持箱子网格缓存并实例化；子类、开合动画和不支持的渲染状态走原版。现有限额为 1024 个网格、32 MiB 顶点区，资源重载清空缓存。

后续候选是潜影盒：底座与盖子分别缓存，盖子使用实例变换表达动画；缓存键要覆盖颜色、朝向、模型和资源版本。当前尚未扩大生产白名单，离屏地形基准也没有实体，不能用它证明实体优化的 FPS 收益。需要在同一游戏场景比较缓存命中、回退、renderer CPU 时间、绘制数和 P99，并核对动画、光照与重载像素。

## Vulkan P99：增加分项测量，暂不改资源预算

`OffscreenBenchmark` 的 `-Dbench.tail=true` 分别记录预热与稳态的 CPU 帧调用、fence 等待、GL 交接的 P50/P95/P99/max。每项保留最近 65536 个原始时间样本，max 覆盖全阶段；无逐帧分配、额外 GPU 等待。阶段边界非阻塞轮询上传完成情况，报告已提交/完成批次、字节、背压等待和 pending accounting。帧内 pending 值是生产最近轮询的记账值，不能当成实时 GPU 队列长度。

静态地形场景只能提供同步基线。它不包含持续区块流入、实体、Swapchain 或显示延迟，不能据此调整 64 MiB 上传预算或 16 MiB 批大小。下一步应在移动加载/卸载、贴图更新、资源重载压力中将超预算/背压事件与 P99 尖峰关联，再对单一参数做交错 A/B。报告保存在 `build/reports/vulkan-tail/`。

同一实际绘制清单下，Vulkan 1.4 五轮同步基线为 1856 FPS 中位数、0.470 ms/帧 GPU 时间、90.7% 整卡占用；CPU 帧调用 P99 的五轮中位数 1.460 ms，fence 等待 0.482 ms，GL 交接 0.617 ms。分项包含在帧调用中，不能相加。预热后 pendingBytes=0、背压等待 0；其中一轮 GL 交接 max 为 9.716 ms，仍需压力场景复现定位。该轮 `VALIDATION=UNAVAILABLE`，不能据此声称 Vulkan 校验零错误。
