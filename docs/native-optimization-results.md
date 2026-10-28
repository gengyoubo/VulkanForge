# 原生 VK：常驻几何与提交优化

2026-10-29，本轮只运行优化后的 VK，直接与已保存的历史结果比较，没有重新运行旧版本、原版 GL 或 GL46。实际游戏全程使用 Vulkan 与 Swapchain，`presentMode=0`，没有 OpenGL 上下文或 GPU 调用。

## 实现

- `NativeGeometryCache` 以逻辑缓冲 ID + 版本保存 device-local 顶点/索引，未变化的地形不再每帧写入。更新生成不可变新版本，删除及旧版本通过已有帧 fence 延迟释放；256 MiB 数据预算计入在途版本，满载时回到按帧上传，不丢绘制。
- `NativeDeviceState` 缓存属性布局与不可变 uniform 条目，移除每 draw 的索引切片分配。映射写入结束生成新 CPU 版本，避免影响已经排队的绘制。
- `NativeShaderProgram` 使用数值管线键，移除逐 draw 的数组字符串化；复用 uniform 打包暂存空间，并清零后写入各 draw 的独立偏移。
- `NativeGameBackend` 在同次提交中复用相同 shader/纹理组合的描述符，去除连续重复的管线、视口、裁剪、深度偏移和索引绑定。每个原始 draw 仍按原顺序执行，没有减少视距、跳过实体或重排透明绘制。

## 相同分辨率的前后对比

RTX 3050 Laptop GPU，MC 1.20.1 / Forge 47.4.28 / JDK 17。固定种子、相机与测试场景，视距 8、模拟距离 5、Fancy、mipmap 0、云关闭、60 头 NoAI 牛、HUD 与手持物。VSync 关闭且不限帧。每项预热后在同一 JVM 测三个 10 秒区间；下表取区间中位数。

| 分辨率 | 旧 VK FPS | 优化后 VK FPS | 提升 | 旧 1% Low | 优化后 1% Low |
| --- | ---: | ---: | ---: | ---: | ---: |
| 854×480 | 231.07 | 703.22 | +204.3% | 86.27 | 296.22 |
| 1280×720 | 223.39 | 634.65 | +184.1% | 100.38 | 260.54 |
| 1920×1080 | 233.17 | 487.49 | +109.1% | 103.09 | 207.08 |

优化后 480p 三轮为 704.77 / 700.30 / 703.22 FPS，720p 为 639.62 / 634.65 / 633.10 FPS，1080p 为 487.49 / 498.07 / 478.80 FPS。完整帧间隔包括游戏逻辑、资源准备、命令记录、驱动与最终呈现，并核对 Vulkan 实际呈现计数。性能运行关闭校验层及 CPU/GPU 详细计时；1% Low 使用最慢 1% 帧的平均耗时取倒数。

对应之前的 720p 测试，历史原版 GL 为 698.85 FPS、GL46 为 715.05 FPS；本轮优化后 VK 为 634.65 FPS。三者场景参数相同，但 GL/GL46 没有重测，且没有确立画面完全等价，因此只作为历史参考，不将其称作本轮后端胜负排名。

480p/1080p 历史基线为 `build/reports/native-diagnosis-20261028/854x480` 与 `1920x1080-retry`；原先失败的 `1920x1080` 不参与比较。720p 历史参考来自 `build/reports/game-fps-20261028/`，该次没有新增诊断计数，但使用相同呈现帧统计与场景设置。未锁定 CPU/GPU 频率，也没有交错配对运行，因此这里是历史基线比较。480p GPU 平均 SM 时钟从 1495 变为 1845 MHz，1080p 从 1848 变为 1845 MHz，不能把所有变化归给某一项修改。

## 工作量与资源开销

以下以 1080p 三个区间的总计数 / 总帧数统计；MiB 为 1024² 字节。

| 指标 | 旧版本 | 优化后 |
| --- | ---: | ---: |
| 总 draw / 帧 | 184.98 | 185.00 |
| 地形 draw / 帧 | 150 | 150 |
| 实体/物品分类 draw / 帧 | 12 | 12 |
| 当帧顶点 + 索引拷贝 | 5.578 MiB | 0.517 MiB |
| 其中地形顶点 + 索引拷贝 | 5.061 MiB | 0 |
| 描述符分配/更新 / 帧 | 184.98 | 18.00 |
| 整个渲染线程 Java 堆分配 / 帧 | 2.522 MiB | 1.106 MiB |

总几何流式拷贝减少约 90.7%，渲染线程堆分配减少约 56.1%。优化后稳定计时区间的常驻网格上传量为 0，预算回退次数为 0；后端全程报告仍记录了启动时的实际网格上传，常驻几何约 5.62 MiB，并非省略地形上传。动态几何及动画纹理仍有更新。

`terrainVertexBytes` / `terrainIndexBytes` 现在表示实际流式写入；`residentUploadBytes` 单独统计常驻版本上传。适配器中的 `unchangedTerrainBufferDraws` / `unchangedTerrainReferencedBytes` 只表示未更新缓冲被再次引用，不代表再次上传。JSON 的 `nativeDiagnostics` 是区间差值，常驻内存等 gauge 应读取 `backend.txt` 中的绝对值，不能把差值 0 解释为没有显存占用。

## 分项计时

另跑 1080p 一个 10 秒区间，开启 CPU/GPU 计时。旧样本 2116 帧，优化后 4603 帧；这组启用计时的 FPS 不混入上方表格。均为每帧毫秒。

| 指标 | 旧版本 | 优化后 |
| --- | ---: | ---: |
| 适配器入口墙钟时间（含下游） | 0.540 | 0.278 |
| 其中 draw 适配 | 0.316 | 0.094 |
| 场景 flush（含子项） | 1.187 | 0.299 |
| 其中几何拷贝及 uniform 写入 | 0.501 | 0.087 |
| 其中描述符准备 | 0.071 | 0.021 |
| 场景 QueueSubmit | 0.046 | 0.033 |
| QueuePresent CPU 墙钟时间 | 1.650 | 0.414 |
| 场景 fence 等待 | 0.009 | 0.292 |
| GPU 场景时间戳跨度 | 1.050 | 0.315 |
| GPU 最终采样/呈现时间戳跨度 | 0.0479 | 0.0481 |

各项有包含和重叠关系，不能相加。没有改写呈现调度；Present 时间下降、场景 fence 等待增加，说明等待分布也发生了改变，不能把 Present 差值当作独立优化收益。GPU 场景计时不包含独立上传提交。当前仍是单份场景资源，后续若优化帧并行，需一起处理在途附件、描述符、动态缓冲及资源释放。

## 验证及范围

实际 GPU 的 `smokeNativeGeometry` 验证缓冲更新前后的读回内容、4 字节对齐、在途旧版本存活、延迟删除、预算满载和恢复。`smokeNativeShader` 验证管线复用、输入接口及 std140 uniform 打包/隔离。两项均启用 Khronos 同步校验，并由隔离加载器确认没有加载 GL/GLFW/互操作类。

完整构建和实际游戏检查通过，覆盖世界、背包、窗口缩放、资源重载、Fabulous、退出世界、返回菜单与关闭。`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。480p 优化前后截图中的地形、树叶、水、玻璃、箱子、HUD 与手持物可见；牛的姿态会随游戏时间变化。720p VK 的手持物位置与 GL 参考仍有差异，本轮没有修复该问题。这里没有宣称与原版 GL 达到逐像素等价，也没有用 Fabric 的 913 FPS 计算优化倍数。

原始结果位于 `build/reports/native-optimized-20261029/`：各分辨率子目录含 `results.json`、逐帧 CSV、场景参数、Swapchain 截图、后端与启动日志；汇总为 `comparison.json` / `profile-comparison.json` / `720p-reference-comparison.json`，整卡采样为 `telemetry.csv` 与 `telemetry-720.csv`。源文件摘要与基准 Git 版本也保存在同一目录，测量结束核对渲染器源码没有变化。实际游戏校验的结果与截图在 `world-validation/`。

```powershell
# 设置已安装的校验层路径，并设置 VK_VALIDATION_VALIDATE_SYNC=1 后运行正确性检查
.\gradlew.bat :vulkan-lab:smokeNativeGeometry :vulkan-lab:smokeNativeShader -PvalidationSmoke
.\gradlew.bat -PnativeGame -PnativeGameSmoke -PnativeProfile -PvalidationSmoke build runClient
# 性能运行只开计数；每次使用新目录保留旧结果
.\gradlew.bat -PgameFps -PfpsMode=VK -PnativeDiagnostics '-Pfps.width=1920' '-Pfps.height=1080' '-Pfps.output=build/reports/native-optimized-new/1080p' runClient
```
