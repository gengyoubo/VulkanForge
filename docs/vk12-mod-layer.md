# VK-12：从优化模组的提交路径入手

2026-10-29。以项目实际使用的四个 JAR 为准核对字节码和反编译结果，并查阅上游项目；没有把其他分支的新功能当成当前版本已有行为。依赖路径、SHA-256 和检查的类保存在 `build/reports/vk12/dependency-audit.json`；反编译文件只在忽略的 `build/vk12-audit/`，没有打入模组或复制为项目源码。

## 源码核对

| 模组 | 项目依赖 | 本轮确认 |
| --- | --- | --- |
| Embeddium | 5681725 / 0.3.31+mc1.20.1 | DefaultChunkRenderer 按 Region 填充 MultiDrawBatch；不透明与排序透明使用不同索引资源与遍历顺序 |
| Chloride | 8462759 | 实体数量上限路径先复制全部候选，再按原 DISTANCE_COMPARATOR 完整稳定排序；距离剔除另在 EntityRenderDispatcher 执行 |
| MagnesiumExtras | 6041697，运行时 mod ID 为 sodiumextras | 也在 EntityRenderDispatcher 做距离剔除；配置缓存和白名单独立于 Chloride |
| Flerovium | 8284677 | 包含角度相关的 OcclusionCuller 覆写和 VertexBufferWriter 物品模型路径；**此 JAR 没有 RenderSectionManagerMixin** |

因此，用户贴文中的 `flerovium$touched / needsUpdate` 跳过图搜索逻辑不能用于解释当前 JAR。VulkanForge 继续使用 Embeddium/Flerovium 选择出来的可见内容，不复制或替换其遮挡算法。Chloride 和 MagnesiumExtras 的距离公式有相似部分，但配置、白名单及特殊兼容分支并不统一，本轮没有自动关闭任一套剔除。

上游参考：[Embeddium](https://github.com/FiniteReality/embeddium)、[Chloride](https://github.com/SrRapero720/chloride)、[MagnesiumExtras 1.20.1](https://github.com/txnimc/MagnesiumExtras/tree/1.20.1)、[Flerovium](https://github.com/MoePus/Flerovium)。实现判断优先依据本地实际版本，不能把这些仓库当前默认分支当作相同版本。

## 已实现

以下三个提交/筛选实验默认关闭。可用 `-PnativeModSubmission` 一并启用，或分别使用 `-PnativeRegionBridge`、`-PnativeDynamicBatching`、`-PnativeChlorideTopK`。详细计量由 `-PnativeProfile` 独立控制。实际 A/B 未证明稳健的 FPS 收益，因此最终默认保留原路径。

**Region 批次入口。** 在原生引导中改写实际 `executeDrawBatch`，接收原 Tessellation 与 MultiDrawBatch。`NativeEmbeddiumBridge` 只在首次遇到 Tessellation 时解析并缓存布局，随后直接向适配器提供顶点/索引缓冲 ID、属性和原批次数组地址，避免逐批 beginTessellating、VAO 绑定/解绑、GL 入口 Object[]/装箱与 VAO 状态反查。缓冲版本、脏区、shader/uniform、混合/深度、纹理及 framebuffer 仍由已有原生状态管理，保留透明顺序和在途版本寿命。Tessellation 的创建尚未移除，也没有宣称完全绕过所有 GL 风格状态模拟；提交给隔离 Vulkan 后端的 JDK 对象包仍保留。

**动态实体/物品批次。** 只合并相邻、同目标、同 shader、同输入布局/索引类型、同纹理、uniform、viewport、scissor、深度偏移、常量属性及混合/深度状态的实体类包。每份原始顶点和索引直接批量复制到帧槽缓冲；20 字节的间接命令记录各段偏移及原 baseVertex，不在 CPU 上逐索引重写，也不创建合并后的大顶点数组。支持时使用 MDI，不支持时依次发出原子绘制，共用已绑定的状态。保留原始逻辑 draw 计数，另记 `dynamicMergedDraws` 和 `dynamicGpuDraws`。shader 使用 VertexID/DrawID/PrimitiveID 等相关内建变量时保守跳过，跨 uniform、索引类型、清屏、屏障或其他命令也不合并。

**Chloride 稳定 Top-K。** 只替换已核实的数量上限方法中的 `List.sort`。使用同一个 DISTANCE_COMPARATOR，以输入序号处理平局，返回与原完整稳定排序相同的前 K 个实体。堆大小为 K，未入选的候选不分配堆节点；最终只排序 K 项。没有改为平方距离比较器，也没有修改白名单、上限和距离参数。N≤K 时保留完整排序。

**模组层计量。** 在 Mixin 合并后的 Minecraft 类中记录两套剔除回调的次数、取消数、CPU 时间及同一帧同一实体经过两套回调的次数；不能只改 Mixin 类的类加载事件，因为 Mixin 通常读取资源字节而不加载该类。另对 Embeddium 图搜索、构建结果处理、Region 上传记录次数、结果数和时间。钩子仅在 `nativeProfile` 打开时安装，正常运行不逐实体计时。实际可见 Section 数通过 Embeddium 的公开查询采集。

`regionBridgeSubDraws` 是批次子绘制数，不等于唯一 Section 数；`regionBridgeIndices / 3` 为该桥的三角形提交量，不是 GPU 剔除之后实际产生像素的三角形数。`visibleSections` 是绝对值，区间差为 0 表示可见数量未变；应读取时间线的绝对采样。两套回调都被调用也不代表两套距离判断都启用，需结合场景配置与取消数判断。

## 正确性验证

构建、原生引导与 CPU 缓冲测试通过；Top-K 对随机数据、重复距离、K=0、K>N 等情形逐项核对原稳定排序结果。实际 Vulkan 测试对比合批与逐包路径的混合像素，覆盖索引偏移、负 baseVertex、不同索引宽度边界、uniform 边界、零步长回退及逻辑 draw 数。八个逻辑绘制在 MDI 合批路径为七次 GPU 绘制命令，兼容路径为八次，读回像素一致、校验错误为零。

## A/B 结果与选择

每个配置独立 JVM，预热后两个 6 秒区间，1280×720、固定超平坦世界与相机，同一套模组，VSync 关闭、不限帧。所有项开启同样的详细计量。压力场景为 180 头牛、180 个带唯一标签避免合并的掉落物、实体上限 64，并每半秒切换 16 个分区中的方块。

| 指标 | 普通旧路径 | 普通实验路径 | 压力旧路径 | 压力实验路径 |
| --- | ---: | ---: | ---: | ---: |
| 两区间 FPS | 780.49 / 842.57 | 774.14 / 809.35 | 420.80 / 562.21 | 558.62 / 194.14 |
| 可见 Section 绝对数 | 41 | 41 | 47 | 47 |
| 逻辑 draw / 帧 | 106 | 106 | 147.08 | 146.86 |
| 地形子绘制 / 帧 | 69 | 69 | 110.08 | 109.86 |
| 实体类逻辑 draw / 帧 | 12 | 12 | 16 | 16 |
| 实体类 GPU 绘制命令 / 帧 | 12 | 12 | 16 | 8 |
| 渲染线程分配 / 帧 | 675,052 B | 676,227 B | 720,407 B | 770,504 B |
| 适配入口 + Region 桥 / 帧 | 0.4390 ms | 0.4455 ms | 0.7918 ms | 0.9067 ms |

普通场景没有确认收益，实验路径也未降低整个渲染线程的分配。压力实验第二区间有明显慢帧，GPU SM 时钟均值约 1146 MHz，其他稳定区间约 1845 MHz；旧路径压力第一段也只有约 1606 MHz。这可能包含系统负载/功耗或 CPU 阻塞的影响，低频也可能是低 GPU 工作量的结果，不能据此把异常完全归因于驱动或代码。数据没有随机交错配对，也未锁频，保留全部区间，不挑选较好的区间来宣称提速。

已经能确认的变化是：压力实验的 Top-K 每帧处理 361 个候选并保留 64 个；动态 MDI 每帧合并 8 个兼容包；两个压力版本都没有拆帧，可见 Section 数一致。区块重建/上传仍在发生，图搜索与上传结果计量非零，但更新完成时刻并不完全一致，不能把地形 draw 的微小差别当作 API 收益。

普通场景两套实体剔除回调每帧都经过 61 个实体，Chloride 约 0.0019 ms/帧、MagnesiumExtras 约 0.0041 ms/帧。即便存在重叠，这约 0.006 ms/帧的样本成本也不支持优先替换或合并其白名单/剔除逻辑。

测量期间生产源码保持不变，摘要在 `benchmark-source-hashes.json`。测量后增加了实验开关的保守默认值及零步长边界保护；最终构建和验证记录独立保存于 `release-validation/`。

## 对 Vulkan 能力表的判断

Device-local 常驻缓存、局部 staging 上传、CPU 生成的 MDI、按帧槽轮转的动态缓冲、Synchronization2 和上传 Timeline 都已有实现。尚未完成的是原生 Embeddium 路径上的 GPU 命令生成及 GPU 遮挡剔除。

下一步更值得先计量两个复制链路：一是更新缓冲的 CPU 镜像写时复制，二是局部上传时仍存在的旧 GPU Arena 整块复制。缩小更新/版本粒度需要结合在途资源的最后读写时刻，不能直接覆盖旧数据。动态数据侧应继续检查 `VertexBufferWriter → CPU 镜像 → 映射缓冲` 的复制次数；新增一个 Ring Buffer 对象本身不会消除它们。

Render Graph 是后端组织方式，Synchronization2 是表达依赖的 API，后者不会自动减少 barrier 或附件切换。可先在保留绘制顺序的前提下建立轻量资源使用记录。Timeline 已用于上传批次完成与回收，后续价值在于把完成值关联到实际资源最后一次使用，而不是仅将现有 fence 改名。GPU 命令生成应在稳定、可常驻的 Section/Draw 记录之后接入；GPU 遮挡剔除再按大视距场景实测决定，避免重复已有 CPU 剔除而只增加 compute/深度层级成本。

启用 Khronos 同步校验的完整游戏回归已验证 Region 桥实际被调用，以及两套剔除回调和图搜索计量实际非零；覆盖世界、背包、缩放、重载、Fabulous、返回菜单和退出，`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。普通测试场景本身可能没有可合并的相邻实体包，不能把“已实现动态合批”直接解释成该场景已减少实体 draw。

## 复现

```powershell
# 正确性检查：使用已安装的校验层，仅修改当前会话环境
$env:VK_LAYER_PATH = 'C:\VulkanSDK\1.4.363.0\Bin'
$env:VK_VALIDATION_VALIDATE_SYNC = '1'
.\gradlew.bat -PnativeModSubmission -PnativeGame -PnativeGameSmoke -PnativeProfile -PvalidationSmoke build smokeNativeBootstrap smokeNativeBuffers :vulkan-lab:smokeNativeDynamicBatch :vulkan-lab:smokeNativeDynamicLegacy :vulkan-lab:smokeNativeMrt runClient

# 普通场景；A/B 两次使用不同目录，加入 -PnativeLegacyModSubmission 选择旧入口
.\gradlew.bat -PnativeModSubmission -PgameFps -PfpsMode=VK -PnativeProfile '-Pfps.rounds=2' '-Pfps.seconds=6' '-Pfps.output=build/reports/vk12-new/native-normal' runClient

# 测试源集的更新/实体压力：实体上限只在该 JVM 修改，不写入用户配置
.\gradlew.bat -PnativeModSubmission -PgameFps -PfpsMode=VK -PnativeProfile '-Pfps.cows=180' '-Pfps.items=180' '-Pfps.entityLimit=64' '-Pfps.updates=true' '-Pfps.warmup=15' '-Pfps.rounds=2' '-Pfps.seconds=6' '-Pfps.output=build/reports/vk12-new/native-stress' runClient
```

性能运行强制关闭 Vulkan 校验。详细计时本身有开销，应与计时关闭的 FPS 对照分开使用。原始结果、场景/模组配置、时间线、NVIDIA 采样、截图和日志位于 `build/reports/vk12/`。本轮没有推进 GPU-driven culling，也没有修改上游模组 JAR。
