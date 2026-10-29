# VK-11：提交、Arena 与同步诊断

2026-10-29，MC 1.20.1 / Forge 47.4.28 / JDK 17 / RTX 3050 Laptop。已完成截图表格中的诊断、提交分配优化、预算回退修复和 Arena 脏区改进。当前数据不支持优先修改 GPU Pipeline。

## 表格逐项结果

| 优先级 | 项目 | 结果 |
| --- | --- | --- |
| P0 | 普通场景 CPU/GPU 耗时 | 实际超平坦游戏采集逐帧周期、CPU 分项、GPU 时间戳和每 120 帧累计计数；普通场景没有拆帧 |
| P0 | 大负载 `frameSplits` | 拆分常驻预算、绘制数量、顶点上传三种原因；修复等待也无法腾出足够空间时的重复拆帧；加入受控压力测试 |
| P1 | `NativeDeviceState.submit()` 分配 | 状态、视口/裁剪、深度偏移、纹理/采样器数组复用不可变快照；未变化的脏区使用共享空数组；移除两份临时版本数组；相同 uniform 不再重建条目快照 |
| P1 | Present 与等待 | 分开测量 acquire、呈现槽 fence、WSI fence、QueueSubmit 与 QueuePresent；保留资源复用所需等待 |
| P2 | Arena 脏区 | 超过 64 个范围时合并最小间隙；后端四字节对齐、合并重叠范围并处理末尾填充；保留旧 GPU 版本的在途寿命 |
| P2 | 针对实际瓶颈优化 GPU Pipeline | 本轮不改 shader/管线：普通场景 GPU 场景跨度远小于完整帧周期，CPU 提交与兼容层更值得继续优化 |

## 普通场景

1280×720、视距 8、模拟距离 5、Fancy、云关闭、mipmap 0、60 头 NoAI 牛、水/玻璃/箱子/树叶、固定种子与相机、HUD 和手持物开启，VSync 关闭、不限帧。VK 诊断开启 CPU/GPU 计时，预热后每区间 10 秒。GPU 时间只在已有槽位 fence 完成后收集。

| 指标 | 修改前（1 区间） | 最终版本（3 区间中位数） |
| --- | ---: | ---: |
| 诊断 FPS | 884.84 | 858.35 |
| 渲染线程堆分配 / 帧 | 705,768 B | 677,878 B |
| 渲染线程 CPU / 帧 | 0.962 ms | 1.117 ms |
| 兼容入口墙钟时间（含下游） / 帧 | 0.371 ms | 0.422 ms |
| 场景 flush（含子项） / 帧 | 0.171 ms | 0.195 ms |
| GPU 场景跨度 / 帧 | 0.183 ms | 0.143 ms |
| GPU 呈现通道跨度 / 帧 | 0.0248 ms | 0.0224 ms |
| QueuePresent CPU / 帧 | 0.153 ms | 0.0588 ms |
| 场景 fence 等待 / 帧 | 0.00211 ms | 0.00175 ms |
| 呈现槽 fence 等待 / 帧 | 0.000707 ms | 0.000450 ms |
| WSI presentation fence 等待 / 帧 | 0.000863 ms | 0.00104 ms |
| `frameSplits` | 0 | 0 |

堆分配约下降 4.0%，仅指整个渲染线程 Java 堆。最终三轮为 861.56 / 858.35 / 848.49 FPS；中间一次版本（最后的脏区合并改进前）三轮中位数为 893.76 FPS。未锁频、未随机交错、基线只有一个区间，**本轮不能宣称普通场景 FPS 提升**。Present 耗时变化也不能单独归因于提交分配优化。

约 1.17 ms 的完整帧周期与 0.143 ms 的 GPU 场景跨度、接近一个逻辑 CPU 的渲染线程执行时间，更支持 CPU 侧限制吞吐的判断。GPU 跨度不包含独立纹理上传；这些 CPU/GPU 区间有嵌套和重叠，不能逐项相加。`adapterDrawInclusiveNs` 本轮增加了 Embeddium multi-draw 入口，修改前后的该字段口径不同，不直接比较。

另关闭详细计时，保持同一套模组与场景，分别跑一个 10 秒区间：GL 948.59 FPS，VK（仅开计数）893.02 FPS，VK 约低 5.9%。这不是无模组原版 GL 对照，也不是多轮随机交错排名。VK 该区间有 56.86 ms 的最长帧，GL 为 4.62 ms；单个区间的 1% Low 不能用于稳定性定论。结合前面的分项，后续应优先检查兼容入口、CPU 数据准备、分配/GC 和 Present 波动；没有足够证据把这个差距归因于 GPU Pipeline。

## 常驻预算与脏区修复

实际游戏另测视距 16，预热 20 秒后计量一个 10 秒区间：734.38 FPS、333 draw/帧（其中地形 296）、GPU 场景 0.248 ms/帧、渲染线程 CPU 1.230 ms/帧、场景 fence 等待 0.00199 ms/帧。`frameSplits=0`、常驻回退为 0。更大视距增加了工作量，但该场景没有复现反复强制同步；不能据此断言所有大型存档都不会触发预算压力。

CPU/GPU 区间图保存在 `build/reports/vk11/cpu-gpu-intervals.png`，上图为普通场景首区间，下图为视距 16；两者都更支持继续检查 CPU 固定工作，而非直接修改 GPU Pipeline。

旧路径在 `geometry.get()` 失败后总会尝试回收、拆分当前帧并等待。当活动工作集本身已经超过预算、没有足够退役版本可释放时，这些等待无法解决问题。

现在分别跟踪已分配与已退役字节。只有释放退役版本后能够容纳新缓冲才进入等待/拆帧；否则直接走现有流式上传。仍保留 16384 个绘制包与 256 MiB 顶点上传的单提交上限。拆帧回收前还会完成上传批次，覆盖没有场景命令但仍有新上传引用资源的边界。

受控 GPU 压力测试将常驻预算设为 16 MiB，轮流绘制两个 12 MiB 顶点 Arena，跨三帧循环；检查回退仍绘制正确像素，预算压力不产生额外拆帧。另以 16500 个绘制包验证超出绘制上限仍正确拆分。这个测试用于复现预算边界，不等同于用户具体的“巨大化”存档。

CPU 测试验证 65 个分散的小写入仍保留 64 个有效范围，每个脏字节都被覆盖；不再仅因范围数超限就标记整块上传。GPU 读回另验证非四字节写入、对齐后范围合并、29 字节缓冲的末尾填充、旧版本保留和预算恢复。小脏区更新仍需复制旧 GPU 缓冲生成不可变新版本，尚未消除设备内整块复制。

新增诊断：`splitResidentBudget`、`splitDrawLimit`、`splitVertexLimit`、`splitInclusiveNs`、`residentUnreclaimable`、`residentReclaimableBytes`、`residentBudgetBytes`。后三者中的内存字段是绝对 gauge，应从 `backend.txt` 读取；不要将区间差为零解释为未分配内存。

## 验证与复现

完整 `build`、`smokeNativeBuffers`、`smokeNativeGeometry`、`smokeNativeMrt` 和实际游戏检查通过。GPU 正确性检查要求 Khronos validation layer，开启同步校验；游戏覆盖世界、背包、窗口缩放、资源重载、Fabulous、返回菜单和退出，`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。保存的普通场景截图可见地形、水、玻璃、箱子、树叶、牛、HUD 与手持物；未声明与 GL 逐像素等价。

```powershell
# 使用 JDK 17，并将已安装校验层加入当前会话
$env:VK_LAYER_PATH = 'C:\VulkanSDK\1.4.363.0\Bin'
$env:VK_VALIDATION_VALIDATE_SYNC = '1'
.\gradlew.bat -PnativeGame -PnativeGameSmoke -PnativeProfile -PvalidationSmoke build smokeNativeBuffers :vulkan-lab:smokeNativeGeometry :vulkan-lab:smokeNativeMrt runClient

# 性能诊断关闭校验；对每次运行使用新的输出目录
.\gradlew.bat -PgameFps -PfpsMode=VK -PnativeProfile '-Pfps.rounds=3' '-Pfps.width=1280' '-Pfps.height=720' '-Pfps.output=build/reports/vk11-new/normal' runClient
python tools/analyze-native-profile.py build/reports/vk11-new/normal
```

原始数据保存在 `build/reports/vk11/`。`before-normal/` 为修改前诊断，`after-normal/` 为中间版本，`final-normal/` 为最终生产代码；`validation/` 为实际游戏校验。每次诊断保存 `results.json`、场景参数、截图、逐帧 CSV 和 `timeline-N.csv`；分析脚本生成 `profile-summary.json` 及 `timeline-N-intervals.csv`。时间线为每 120 帧计数采样，GPU 值在 fence 完成后延迟到达，不能把它当作逐命令的 GPU 队列追踪。分析时不把重叠区间相加。
