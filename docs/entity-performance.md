# Vulkan 大量实体的分项诊断与首轮优化（2026-10-27）

本轮已增加捕获、合批、命令录制、GPU 执行与分配统计，并去掉已确认的一次中间几何复制。测试覆盖合成 Draw 数增加时的回放成本；尚未复现用户具体的牛群、箱子、告示牌或模组机器场景，因此不宣称已消除所有大量实体卡顿。

## 已修改的生产路径

- `EntityPass.batch()` 保留原始顶点数组引用，合批后分段写入同一个 Vulkan 映射缓冲，不再创建并填充合并后的大 `byte[]`。相邻顺序、Uniform 相等条件、纹理和状态限制保持原有语义。
- 多边形偏移参数加入合批边界，避免不同偏移的 Draw 被合并。
- 阴影和裂纹需要 24 字节顶点布局时，直接从原始 Buffer 拷贝所需字段，去掉“先完整复制、再打包”的临时数组。转换检查覆盖 NEW_ENTITY / BLOCK 与两种 variant，并验证字节内容和源位置。
- 未开启 polygon offset 时，不再查询其两个无效参数。

进入捕获阶段的每个 Draw 仍有自己的顶点 `byte[]`。本轮没有加入 Vulkan 静态箱子缓存或 SSBO/MDI；不同 Uniform 的 Draw 仍独立提交。

## 测量方法与热身修正

RTX 3050 Laptop。每个合成 Draw 为 96 个四边形、384 个 NEW_ENTITY 顶点，即 13824 字节。计数 0、50、100、200、500 表示 Draw 包数量，不能直接换算成相同数量的完整实体。

捕获测试使用真实 `ShaderInstance`、`BufferBuilder.RenderedBuffer` 和 `EntityDraws.capture()`，但不包含模型构建、游戏模拟、纹理镜像或 GPU 回放。回放测试用预先生成的包调用真实 `EntityPass`，在 256×256 无 Surface 的目标中绘制；排除捕获、帧 fence 等待和屏幕呈现。

首次短预热出现明显 JIT 阶段变化，50/100 Draw 甚至比 200 Draw 更慢。该批数据仅保留为诊断，不能用它认定持续瓶颈在 GL 状态查询。正式测量先以 500 Draw 全局预热 300 帧，再对每种数量预热 100 帧、采集 200 帧；每项 5 个独立 JVM，数量顺序打乱，两种回放方式逐帧随机交错。以下数值均为各轮分位数的中位数。

## 捕获成本

512 MiB 最大诊断堆，已充分热身：

| Draw 数 | 捕获 P50 ms | 捕获 P99 ms | 每帧捕获分配 |
| --- | ---: | ---: | ---: |
| 50 | 0.087 | 0.157 | 0.72 MB |
| 100 | 0.256 | 0.996 | 1.44 MB |
| 200 | 0.349 | 1.180 | 2.88 MB |
| 500 | 1.302 | 6.150 | 7.20 MB |

500 Draw 时，顶点复制本身约为每帧 6.91 MB。充分热身后，不能仅按 `glGet*` 调用数认定状态查询是首要瓶颈。捕获仍有明显分配与尾延迟，后续帧 Arena / 网格缓存值得继续做。

## 去掉合批中间数组的 A/B

另用 1 GiB 最大堆、2 MiB G1 区域复核；该组 GC 日志未出现 Humongous Allocation。两种模式均开启相同诊断，唯一 A/B 开关为“合并数组后上传 / 原数组分段上传”。

相同 Uniform：

| Draw 数 | 原拼接方式 P50 / P99 ms | 分段写入 P50 / P99 ms |
| --- | ---: | ---: |
| 50 | 0.427 / 2.181 | 0.172 / 0.786 |
| 100 | 0.686 / 3.836 | 0.239 / 1.537 |
| 200 | 2.387 / 8.663 | 0.668 / 3.144 |
| 500 | 8.626 / 22.014 | 3.277 / 7.948 |

500 Draw 的回放线程分配从约 6916 KB/次降到约 5.94 KB/次；合批的额外几何复制从 6912000 字节降为 0，实际上传顶点量保持一致。CPU 录制 P50 降低约 62%，P99 降低约 64%。这是该合成负载的录制分项收益，不是完整游戏 FPS 提升。

不同 Uniform 的 500 Draw 保留逐 Draw 路径，P50 为 2.138 → 2.262 ms，没有改善。不能用这轮优化声称已解决不同矩阵/材质导致的逐 Draw 提交成本。

两种路径在所有数量、两种 Uniform 模式、五轮的最终图像 CRC32 均一致。稳态区间没有新增 Pipeline 或缓冲扩容。GPU 时间单独通过 timestamp 记录，原始结果在：

- `build/reports/entity-scaling-steady/`：捕获与回放分项，512 MiB 最大堆，含 GC 压力。
- `build/reports/entity-scaling-large-region/`：1 GiB 最大堆、2 MiB G1 区域的回放复核。
- `build/reports/entity-scaling/`：未全局预热的初始批次，仅用于说明 JIT 影响。

两种堆配置都不是用户游戏堆参数的假定，不能据此推断实际游戏的 GC 类型。捕获与回放是两个独立实验，不把它们相加来推算游戏 FPS。

## 在实际卡顿场景采集

启用 Vulkan 后运行：

```powershell
.\gradlew.bat '-Dvulkanforge.entityProfile=true' runClient
# A/B：恢复合批中间数组，其他条件相同
.\gradlew.bat '-Dvulkanforge.entityProfile=true' '-Dvulkanforge.entityLegacyBatch=true' runClient
```

发行 jar 的启动器中加入相同 `-D` JVM 参数即可。计时默认关闭；低成本计数仍会记录。

| 输出 | 含义与位置 |
| --- | --- |
| `ENTITY_WORLD_CAPTURE_MS` | `run/logs/latest.log`，每 300 个 Vulkan 地形帧报告最近一组世界捕获 P50/P99；不包含模型构建、GUI 捕获或游戏模拟 |
| `ENTITY_CAPTURE` | 关闭/切换渲染器时的累计捕获次数、时间、几何复制字节、转换字节、状态查询时间和包构造时间，以及回退计数 |
| `ENTITY_PERFORMANCE` | 后端报告中的累计包数、录制时间、合批时间、上传字节、中间数组复制字节、Pipeline 数、缓冲扩容和 Draw 数 |
| `ENTITY_RECORD_CPU_MS` | 最近最多 1024 次世界 EntityPass 调用的 CPU P50/P99，单位是每通道，不是整个游戏帧 |
| `WORLD_BINDINGS` | Descriptor 分配、push、Uniform 描述符更新、复用命中、合并 Draw 数 |
| `GPU_ENTITY_OVERLAY_MS` | 世界实体/文字/粒子等 overlay 通道的 GPU timestamp 均值与最大值；覆盖普通 overlay 与 native composite，排除共享目标交接和 Swapchain |

记录文件可在 `run/logs/vulkanforge/` 中按上述标签搜索；关闭渲染器会输出完整后端汇总。GPU 查询仅在已有帧槽 fence 完成后收集，不新增 GPU 等待。状态查询时间是包构造时间的子项，两者又包含在世界捕获中；不能相加。

实际复现应把普通实体、原版方块实体、模组机器分开，并固定相机、分辨率、渲染距离及纹理预热。将捕获/录制/GPU 时间与帧 P99、回退通道数、纹理镜像和 GC 日志关联后，再决定下一步做帧 Arena、持久模型实例还是不同 Uniform 的 SSBO/间接提交。

## 验证范围

Vulkan 1.2 / 1.3 / 1.4 的离屏实体像素、帧槽重用、合批结果与 GPU 计时检查通过。额外检查不同 polygon offset 不合批、显式 16 位索引走原路径。该环境报告 `VALIDATION=UNAVAILABLE`，功能与像素通过不能替代 Vulkan 校验层验证。

```powershell
.\gradlew.bat --no-parallel '-Dvulkanforge.entityProfile=true' :vulkan-lab:smokeVulkanOffscreen build
.\gradlew.bat offscreenBenchmarkArgs
python tools/benchmark-entity-scaling.py --java <JDK17路径>/bin/java.exe
python tools/benchmark-entity-scaling.py --java <JDK17路径>/bin/java.exe --heap 1g --region 2m --only replay --output build/reports/entity-scaling-large-region
```
