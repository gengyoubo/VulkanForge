# GL-VK 跨 Pass 资源租用区间

本实验在 `SharedWorldRenderer` 的现有地形、深度合成和 `NativePost` 上增加显式连续区间。它复用生产绘制代码，不改变 Shader、Draw ID、透明顺序、深度复制或后处理数量。

## 边界分析

`render(..., nativeMode=true)` 已经让不透明/透明地形源图像保持 Vulkan 所有权，合成直接采样这些图像。这部分没有每 Pass 的 GL 往返可以删除。

当前可减少的往返发生在合成的**目的 RenderTarget**：

```
当前：GL 清除 → VK 地形 → [取回目标 → 不透明合成 → 交还 GL]
                        → [取回目标 → 透明合成   → 交还 GL]
                        → [取回两个目标 → 三次后处理 → 交还 GL]

实验：GL 清除 → VK 地形 → [取回两个目标 → 两次合成 → 三次后处理 → 交还 GL]
```

地形仍使用一次独立提交；实验只合并后续连续合成和后处理。所有权转移保留在区间入口和出口，Pass 内依赖使用 Vulkan image barriers。合成保留 `LOAD`、颜色附件读写访问以及深度读写；后处理保留清除、采样与交替写入的依赖。

## 使用合同

`MainWindowBackend.beginNativeInterval()` / `endNativeInterval()` 是显式实验接口，当前不由完整游戏默认调用。调用者必须确认区间内只有可接管的 Vulkan 操作。

```java
backend.beginNativeInterval();
try {
    backend.nativeComposite(target, width, height, 0, packets);
    backend.nativeComposite(target, width, height, 1, packets);
    // 可嵌套 PostChain；受支持的 PostPass 按原顺序追加。
} finally {
    backend.endNativeInterval();
}
```

任何 GL 绘制、清除、复制、采样或读回之前，调用者必须先 `flushNativePosts()`。排空后可继续在同一区间追加 Vulkan 操作。返回 `false` 的不支持 PostPass/合成在返回 GL 之前会排空；非共享目标和实体合成目前也是强制边界。帧结束、Reset/Atlas 更新、尺寸变化、资源销毁与关闭会排空并等待必要的生命周期依赖。

合并期间按目标追踪最后的颜色/深度 Layout，目标首次使用时从 GL 取得，后续仅执行 Vulkan 内部依赖，最终统一释放。只在录制全部成功之后释放 GL 租约，避免 Shader 编译拒绝后留下半转换的所有权。每个逻辑 Pass 仍预留其原本的 descriptor/uniform 环形槽。

与现有 PostChain 延迟提交合同相同，提交给区间的 packet 及其数组在区间排空之前必须保持不变。

这不是允许任意 Forge 回调跨越 GL 所有权边界的开关。真实游戏在不透明与透明合成之间可能有实体、特殊 RenderType、复制或模组 GL 操作；需要先覆盖这些实际访问的边界，才能把某个连续区间接入游戏。不能用本实验的 FPS 宣称已经优化了所有游戏场景。

## 预期计数

计时区间使用已预热的两帧槽，初始化不计入每帧值。

| 工作量 | 路径 | VK 提交 | GL semaphore 组调用 | 组内图像引用 | 深度复制 |
|---|---|---:|---:|---:|---:|
| 地形与两次合成 | 当前混合 | 3 | 4 | 8 | 1 |
| 地形与两次合成 | 合并区间 | 2 | 2 | 4 | 1 |
| 加三次后处理 | 当前混合 | 4 | 8 | 16 | 1 |
| 加三次后处理 | 合并区间 | 2 | 4 | 8 | 1 |

组内图像引用是每次调用实际传入的图像数之和，不是不同图像的数量。颜色与深度共享组首图像的一对 semaphore；不把组调用、图像引用和分配的 semaphore 个数混为一谈。本实现也为每个参与图像记录对应方向的 ownership barrier。

## 测试与复现

```powershell
.\gradlew.bat offscreenBenchmarkArgs :vulkan-lab:smokeNativePostBatch smokePostChainTransform
python tools/benchmark-native-interval.py --java '<JDK17>/bin/java.exe' --rounds 5 --seconds 6 --warmup 1000 --output build/reports/native-lease-interval
```

隐藏 GL Context，1920×1080、RGBA8/D32F、相同实际绘制清单、相机和资源；GL46、当前 GL-VK、区间 GL-VK、严格无 GL 的原生 VK 以随机顺序各跑五轮，两类负载共 40 个正式样本。Vulkan 图像在计时前后比较全部 RGBA 与深度，混合路径必须逐位一致。GL46 对照保留完整误差记录。没有窗口呈现、世界逻辑、实体或 HUD。

GPU 时间线、端到端 FPS、CPU P99、最长帧与整卡 GPU 占用分别保存；`vkQueueSubmit2`、Vulkan Fence Wait、分组 `glWaitSemaphoreEXT` / `glSignalSemaphoreEXT` / `glFlush` 的 CPU 耗时单独记录。GL 计时跨度包括 GPU 交接等待，CPU API 耗时不等于 GPU 等待跨度。原生 VK 的时间戳来自独立时钟，不能与 GL 时间戳直接相减。

两条 GL-VK 路径具有完全相同的实际工作，只有提交和租用区间变化。GL46 / Native 是同内容、同输出语义的架构参考：它们直接把透明地形绘入同一目标，没有 GL-VK 为阶段兼容保留的两次全屏合成与一次深度复制。因此不能用 Native 与混合路径的耗时差直接称作纯交接固定成本；本实验没有执行此前建议的“只增加最终颜色交接”的 D 对照。

`GlInterop.groupedCpuStats()` 属于执行 GL 的宿主 classloader。独立测试在同一个 loader 中采集；游戏如要显示这些计数，需要从宿主 loader 取值，不能误读隔离 Vulkan loader 的静态字段。

`NativePostBatchSmoke` 覆盖相同 RGBA/深度、两帧槽、嵌套区间、GL 回退后读写与重新接管、Shader 拒绝、帧结束漏关区间、带未提交工作销毁目标、尺寸变化和不配对退出。`PostChainTransformSmoke` 验证实际 Minecraft 字节码以及正常/异常退出。

正式结果、每帧记录、图像、计数断言、硬件采样及当时源码/class 归档见 `build/reports/native-lease-interval/`。本机 Vulkan validation 不可用，像素与功能检查不等于验证层检查。

## 2026-10-27 测量结果

RTX 3050 Laptop，五轮、每例 1000 帧预热与至少 6 秒计时，40 个正式样本完成 361696 帧；不剔除慢样本。

| 执行器 | 地形 FPS | 地形整卡 GPU | 地形加三后处理 FPS | 后处理整卡 GPU |
|---|---:|---:|---:|---:|
| GL46 | 2789 | 98.0% | 1771 | 97.3% |
| 当前 GL-VK | 1092 | 94.5% | 867 | 92.3% |
| 连续区间 GL-VK | 1124 | 98.4% | 915 | 97.7% |
| VK Native 离屏 | 2462 | 90.8% | 1670 | 88.0% |

同轮 FPS 变化中位数为地形 **+4.2%**、三后处理 **+6.2%**。首轮区间路径分别下降 40.0%、18.0%，均保留。正式五轮 CPU P99 中位数地形 1.610→1.925 ms、三后处理 2.157→2.536 ms，不能宣称五轮中长尾得到改善。当前混合路径还出现一次 119.377 ms 帧，原因尚未定位。

三后处理时，CPU QueueSubmit 平均开销的轮间中位数为 73.81→36.11 µs/帧，分组 glWait 为 54.33→12.86 µs、glSignal 为 89.55→51.24 µs、glFlush 为 4.48→1.54 µs。完整 GL GPU 时间线跨度为 1.144→1.058 ms。调用次数下降已经验证，但收益远小于从 867 FPS 提升到接近 GL46 所需要的幅度。

因首轮波动与 P99 退步，追加三组更充分预热的对照：每例预热 5000 帧、计时至少 12 秒、仅当前/区间混合路径、随机顺序；额外完成 65018 帧。

| 组 | 当前 GL-VK FPS | 区间 GL-VK FPS | 同组变化 |
|---|---:|---:|---:|
| 1 | 868.072 | 945.854 | +8.96% |
| 2 | 863.550 | 942.822 | +9.18% |
| 3 | 858.773 | 938.109 | +9.24% |

追加组整卡 GPU 为 94.7%→98.6%；CPU P99 轮间中位数 1.723→1.605 ms，所有样本的最长帧 5.722→3.480 ms。它支持充分预热后约 9% 的收益，但没有替代或删除正式五轮，也不能证明首轮回退或长帧已解决。追加结果见 `build/reports/native-lease-interval-tail/`。

完整颜色与深度均逐位一致。区间功能分别通过 VK 1.2、1.3、1.4 回归；旧共享图像/实体 overlay 路径、PostChain 实际字节码、配置迁移与原生模式拒绝门禁通过；完整 `build` 成功。验证检查显式允许加载层后仍返回 `VALIDATION=UNAVAILABLE`，因此没有验证层通过的结论。

目前保留显式实验接口，未在游戏默认开启跨阶段区间。下一步需要用真实阶段边界覆盖 GL 绘制、清除、复制和模组回调，接入可以证明连续的局部区间；不能仅凭本实验减少调用次数就删除游戏中的交接。
