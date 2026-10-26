# VulkanForge

Minecraft **1.20.1 / Forge 47.4.28 / Java 17** 客户端 Vulkan 渲染实验。

当前已经打通四条路径：

- 独立 Vulkan 1.4 窗口：交换链、动态渲染、缩放重建、三角形绘制。
- Minecraft 主窗口呈现（Windows）：可见窗口使用 `GLFW_NO_API`，创建 Win32 Vulkan Surface / Swapchain；原版主 framebuffer 经共享 GPU 图像和跨 API 信号量交给 `vkQueuePresentKHR`，Vulkan 在最终图像中追加受支持的手持物和 HUD。正常呈现过程不经过 CPU 像素读回。
- 游戏主窗口预览：同进程 Vulkan 绘制旋转三角形 → 导出共享 GPU 图像 → 原有 GL 上下文采样 → 主菜单 / HUD 显示。
- 世界不透明和镂空方块：采集原版烘焙模型、贴图 UV、染色、环境遮蔽与光照坐标 → 分区顶点缓冲 → Vulkan 图集/光照图采样、相机变换、alpha 丢弃、D32 深度测试 → 在原版 cutout 阶段之后合成。

**这是混合渲染原型。主窗口的最终呈现已交给 Vulkan；Windows 主窗口路径中的 WorldScene 已接入同一个 Vulkan 后端，solid、cutout、cutoutMipped 地形通过共享 GPU 纹理合成，正常地形帧不经过 CPU 像素读回或 FrameProtocol。** Vulkan 覆盖范围内的半透明方块与水也由 Vulkan 绘制（P6 第一步），常见实体、方块实体和告示牌文字（P7/P8）、粒子和雨雪（P9）以及云（P10）由 Vulkan 叠加层通道回放，天空在地形提交中由 Vulkan 先行绘制（P10）；范围外地形、Fabulous 模式下的半透明层、不支持的着色器仍在隐藏的 OpenGL 上下文中绘制。受支持的手持物与 GUI/HUD 在 framebuffer blit 后依次直接画进 Swapchain（P11），不再经 GL 合成。关闭主窗口 Vulkan 路径时保留旧的子进程读回预览作为兼容路径。

## 构建和启动

使用 JDK 17。若系统默认 `java` 为 Java 8 或较新 JDK，先在当前 PowerShell 会话中设置 `JAVA_HOME`，不要修改全局环境：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
.\gradlew.bat runClient
```

IntelliJ IDEA：导入根目录的 `build.gradle`，将 **Gradle JVM** 和 **Project SDK** 设为 Java 17。

产物：`build/libs/vulkanforge-0.1.0.jar`。放入 Forge 1.20.1 客户端的 `mods` 文件夹即可；Vulkan 测试程序和运行库已打包，无需另放 LWJGL jar。当前随包提供 Windows/Linux x86_64 原生库；Linux 路径尚未实机验证。

主窗口 Vulkan 呈现目前仅支持 **Windows x86_64**，要求同一 GPU 的 Vulkan 1.4、`VK_KHR_swapchain`、Win32 external-memory / external-semaphore，以及 GL 对应的两个 Win32 扩展。启动时按设备 UUID 匹配，并实际验证共享内存导入和 framebuffer；失败时使用原来的 OpenGL 窗口。其他平台继续使用原来的 GL 呈现。可在启动器 JVM 参数中加入 `-Dvulkanforge.directPresent=false`，重启后关闭主窗口 Vulkan 路径；开发环境：

```powershell
.\gradlew.bat '-Dvulkanforge.directPresent=false' runClient
```

主窗口接管在启动时完成；F8 控制地形/三角形预览，独立于主窗口的呈现方式。窗口缩放、全屏尺寸变化和 VSync 设置变化会重建 Swapchain；最小化时跳过呈现，恢复后继续。

启动期间继续显示 Forge 加载窗口；新窗口先隐藏，完成 Surface/Swapchain 和实际尺寸共享图像的准备，并等待 Vulkan 第一帧完成后才显示，避免提前显示空白窗口。

游戏中：

- **F8**：启停 Vulkan。在主菜单显示三角形预览，进入世界后自动切换为真实不透明和镂空地形。
- **F10**：在全视图合成与右上角小窗对照之间切换。
- 主菜单左下角 **Vulkan 窗口内预览 / Vulkan preview** 按钮也可启停。
- **F9**：打开独立 Vulkan 三角形窗口；该窗口按 Esc 关闭。
- 快捷键可以在原版按键设置中重新绑定。
- 没有满足 Vulkan 1.4 要求的设备时，预览显示失败状态并保存报告，原版游戏继续运行。

日志在游戏目录 `logs/vulkanforge/`，开发环境即 `run/logs/vulkanforge/`：

| 文件 | 内容 |
| --- | --- |
| `probe-report.txt` | 启动时的 loader / GPU / 功能检测与设备创建 |
| `bridge-report.txt` | 兼容路径的子进程离屏渲染、地形/读回复制 GPU 时间和退出结果；主窗口共享路径不启动该桥 |
| `triangle-report.txt` | 独立窗口测试 |
| `main-window-report.txt` | GPU 共享预检、Surface/Swapchain、WorldScene 与共享目标、地形/复制/呈现准备 GPU 时间、CPU 等待与资源关闭 |

## GPU 计时（P1）

时间戳查询按现有帧槽位轮转，在原有 fence 完成后读取，未添加 CPU 等待。报告启动时记录 `GPU_TIMESTAMPS`、队列有效位数和每 tick 的纳秒数；不支持时明确记为 `UNAVAILABLE`。每 300 帧及正常关闭时输出累计样本数、平均值和最大值，单位为毫秒：

| 指标 | 测量范围 | 报告 |
| --- | --- | --- |
| `GPU_TERRAIN_MS` | 地形动态渲染，包括颜色/深度清屏和 WorldScene 绘制 | `main-window-report.txt`，兼容路径在 `bridge-report.txt` |
| `GPU_TERRAIN_RELEASE_MS` | 地形颜色图像释放给 GL 的布局/队列所有权转换 | `main-window-report.txt` |
| `GPU_READBACK_COPY_MS` | 兼容路径中离屏颜色图像复制到 staging buffer | `bridge-report.txt`；共享路径不存在该复制 |
| `GPU_COPY_MS` | GL 共享图像到主窗口 Swapchain 的 Vulkan blit | `main-window-report.txt` |
| `GPU_PRESENT_PREPARE_MS` | 复制完成后的队列所有权释放和 PRESENT 布局转换 | `main-window-report.txt` |

三角形使用单独的 `GPU_TRIANGLE_MS`，不混入地形样本。查询结果按设备的 `timestampPeriod` 换算，并处理有效位回绕；未提交帧不计入样本。呈现准备不包含可选诊断截图复制。`CPU_PRESENT_MS` 仍单独记录 `vkQueuePresentKHR` 的 CPU 调用耗时；GPU 时间戳不能测量显示引擎扫描输出或输入到屏幕的延迟。各项记录独立区间耗时，不作为完整帧时间直接相加。性能对比时应关闭校验层，并保持分辨率、场景和 VSync 设置一致。

## 同进程地形（P2 / P3）

`WorldCapture` 经 `WorldSink` 将不可变网格、图集补丁和当帧相机直接交给主窗口后端。跨隔离加载器的边界只使用 JDK 对象/数组，网格与贴图数组无需二进制序列化。`WorldScene` 使用主窗口同一个 VkDevice 和图形队列，地形目标与 Minecraft 主 framebuffer 尺寸一致。

`SharedWorldRenderer` 使用两个独立颜色/深度目标和 fence。Vulkan 完成绘制后释放图像所有权并发出外部信号量；游戏现有 LWJGL 的 GL 回调等待后采样。GL 地形合成及小窗绘制结束后发出完成信号，Vulkan 下一次重用该图像前等待并取回所有权。每帧没有 RGBA 数组、管道传输、staging 读回或 `glTexSubImage2D` 地形上传。显式保存诊断截图时仍会下载像素；方块图集/光照图目前仍从 GL 读取并上传到 Vulkan。

网格替换、删除、图集更新和场景重置会先等待引用旧资源的地形 fence，防止提前销毁。缩放与关闭前结束 GL 采样、完成 GL 工作并等待地形 fence；F8 关闭会释放整个地形渲染器，重新开启时重建。原版 solid/cutout 绘制及深度仍保留，P4 接管尚未开始。

## 验证命令

```powershell
# 不启动 Minecraft，直接开发独立实验模块
.\gradlew.bat -p vulkan-lab runProbe
.\gradlew.bat -p vulkan-lab runTriangle

# 真实 GPU 集成检查：RGBA 像素、动画、二进制帧协议、正常退出和停止请求
.\gradlew.bat :vulkan-lab:smokeBridge

# 真实 GPU 世界管线：镂空阈值和深度、图集区域更新、光照图更新、相机、分区删除与场景重置
.\gradlew.bat :vulkan-lab:smokeWorld

# Windows 主窗口：呈现/故障恢复，以及同进程 45 个非空 Section 的像素、双缓冲、相机、缩放、删除、重置与关闭后重开
.\gradlew.bat :vulkan-lab:smokePresent

# 完整 Forge 检查：ASM 钩子、Vulkan 主窗口、世界、水池与染色玻璃（P6 半透明）、箱子/告示牌/牛（P7/P8 实体通道）、营火/火把粒子与降雨（P9）、天空/太阳/精美云（P10）、重载、昼夜光照、缩放、最小化恢复、全屏往返、切换世界，然后退出
# 退出超过 30 秒或主窗口报告缺少 FINAL_PRESENTED_FRAMES / VALIDATION_ERRORS=0 时判定失败；检查期间会短暂全屏，请保持游戏窗口在前台
# 测试源集不会被打入模组 JAR；会在 run/saves 留下测试世界和日志中的 PNG
.\gradlew.bat -PworldSmoke runClient

# P0 严格检查：先准备 Vulkan SDK 的 Bin 目录，或 Khronos 官方 Windows validation layer 的 DLL/JSON 目录
# 环境变量只影响当前 PowerShell 会话及其子进程，无需修改注册表或驱动
$env:VK_LAYER_PATH = 'C:\path\to\validation-layers'
$env:VK_VALIDATION_VALIDATE_SYNC = '1'
.\gradlew.bat -PvalidationSmoke -PworldSmoke :vulkan-lab:smokePresent :vulkan-lab:smokeBridge :vulkan-lab:smokeWorld runClient

# 交换链缩放检查
java -jar vulkan-lab/build/libs/vulkan-lab.jar --triangle --frames=90 --resize-smoke

# 安装 Vulkan SDK 校验层后，可要求其必须存在
java -jar vulkan-lab/build/libs/vulkan-lab.jar --probe --require-validation
```

普通 `build` 不要求 GPU 或 Vulkan 驱动。GPU 检查需要 Vulkan 1.4 硬件和驱动。日志中 `VALIDATION=UNAVAILABLE` 表示没有启用校验层；此时错误计数为零不代表通过了校验层检查。

`-PvalidationSmoke` 要求主窗口校验层实际启用，并在客户端退出后检查 `VALIDATION=ENABLED`、`VALIDATION_ERRORS=0`、共享地形计时与完整关闭标记；兼容路径额外检查地形桥。检查缺少校验层或退出超时会失败。同进程检查核对后端 PID 等于游戏 PID、复用原版网格、远处与垂直范围覆盖、渲染距离增减和无地形子进程启动。独立 GPU 像素测试仍使用固定 45 个非空分区。`smokePresent` 额外检查 acquire 后取消帧、GL 交接前/后故障恢复，以及仍持有已 acquire 图像时的关闭。同步验证通过 `VK_VALIDATION_VALIDATE_SYNC=1` 开启，正式性能测量时应关闭校验层。

## 半透明地形（P6 第一步）

`WorldCapture` 现在同时采集流体：水方块的 `RenderShape` 不是 `MODEL`，此前完全没有被采集，而 P4 跳过原版 solid 层后，覆盖范围内的岩浆会缺失。流体按 `ItemBlockRenderTypes.getRenderLayer(FluidState)` 分层：岩浆进入不透明网格，水与 `translucent` 层方块（染色玻璃、冰、史莱姆块、蜂蜜块等）进入 `Mesh.translucent`。

`WorldScene` 为每个含半透明几何的分区保存四边形中心，并为两个帧槽位各准备一份索引缓冲。槽位只在自身 fence 完成后重写索引，因此排序不会改动 GPU 正在读取的数据。与原版一致，相机移动超过 1 格后重新排序：每帧最多重排 16 个分区（最近的优先），尚未排序的槽位总是先排序再使用。绘制顺序为分区由远到近、分区内四边形由远到近，开启背面剔除（与原版 translucent 层相同，避免水面正反两面重复混合）。

`SharedWorldRenderer` 每个槽位共有四张共享图像：不透明颜色/深度，以及半透明颜色和独立深度。半透明深度先复制不透明深度，再记录最近的半透明表面；半透明颜色清为 (0,0,0,0)，以预乘 alpha `(ONE, ONE_MINUS_SRC_ALPHA)` 累积。四张图像每帧都经过同一套所有权转换和信号量往返，并在交换前结束 GL 租用。

GL 侧在 `AFTER_TRANSLUCENT_BLOCKS` 阶段合成，即原版实体和方块实体之后：使用预乘混合，并写入 `gl_FragDepth`，与 GL 深度做 LEQUAL 测试。实体位于水面前方时，会遮住该像素的半透明层；位于水下时，水面会混合在实体之上。原版 translucent 层只在同一帧确实会合成 Vulkan 结果的覆盖分区中跳过。

Fabulous 图形模式会把半透明层画进独立目标供透明后处理链使用，这种情况仍交给原版绘制。雾和水下视角的雾色尚未接入 Vulkan 地形；实体见 P7/P8，GUI 见 P11。

## 方块实体与实体（P7 / P8）

`coremods/entities.js` 只改写 `RenderType.end(BufferBuilder, VertexSorting)` 中唯一一次 `BufferUploader` 上传（按 owner/描述符匹配，不依赖映射名），并把 `this` 一并传给 `EntityDraws.draw`。从 Vulkan 地形合成后（`AFTER_CUTOUT_BLOCKS`）到 `AFTER_BLOCK_ENTITIES` 之间，满足以下全部条件的绘制会被复制下来交给 Vulkan，GL 不再绘制：

- 着色器属于 `rendertype_entity_{solid,cutout,cutout_no_cull,cutout_no_cull_z_offset,translucent,translucent_cull,smooth_cutout,no_outline}`、`rendertype_armor_cutout_no_cull` 或 `rendertype_text`；
- 顶点格式为 `NEW_ENTITY`（文字为 `POSITION_COLOR_TEX_LIGHTMAP`），模式为 QUADS/TRIANGLES，排序后的自定义索引也支持；
- GL 状态 Vulkan 可以复现：无混合或 translucent/默认混合，深度 LEQUAL/LESS，背面剔除，无多边形偏移、剪裁、逻辑运算或颜色写掩码；
- 采样器 0 和 1（贴图、受伤覆盖层）已经镜像到 Vulkan，采样器 2 是地形同步的同一张光照图。

其余绘制（发光附魔、阴影、轮廓、末影眼等）仍走 OpenGL，不会消失；世界检查报告会列出这些回退着色器的名称和次数。

`EntityTextures` 按 GL 纹理 id 镜像贴图：首次使用时异步 PBO 读回，期间该绘制留在 GL；最近 1 秒用过的贴图每 2 秒重新读回一次，像素变化才重新上传（如延迟下载的皮肤、地图、动画图集）。Vulkan 端每次上传都创建新图像，旧图像等使用它的帧完成后再销毁。

`EntityPass` 的 Vulkan 着色器移植自原版 `rendertype_entity_*` / `rendertype_text`：双光源漫反射、覆盖层与光照图 texelFetch、四种片元分支和线性/柱形雾。每次绘制的矩阵、颜色调制、光照方向和雾参数放在 256 字节的动态 uniform 中。顶点、索引和 uniform 使用每槽位环形缓冲，四边形使用与原版相同顺序的共享索引缓冲；管线按格式与混合/深度/剔除状态缓存。

地形提交时把不透明深度复制到每槽位私有的 Vulkan 深度图。`AFTER_BLOCK_ENTITIES` 时第二次提交：把它复制进共享叠加层深度，在其上回放实体，然后把以预乘 alpha 清屏后的颜色和深度交给 GL。GL 侧用与半透明相同的合成着色器，写入 `gl_FragDepth` 并与 GL 深度测试，因此 GL 回退的实体和范围外地形仍能正确遮挡。没有捕获到绘制的窗口不提交。与半透明一样，Fabulous 模式不启用。

尚未完成：附魔光效（`glint`）、实体阴影、发光轮廓、末影/蜘蛛眼（加法混合）、`translucentCullBlockSheet` 中的物品实体（这些在 `AFTER_BLOCK_ENTITIES` 之后才刷新）、破坏裂纹以及模组自定义着色器。

## 粒子与天气（P9）

粒子和雨雪不经过 `RenderType.end`，而是 `Tesselator.end()` → `BufferUploader.drawWithShader`。`coremods/entities.js` 也改写这一处调用（同样按 owner/描述符匹配），使捕获框架有两个入口。每帧最多三个捕获窗口：

| 窗口 | 打开 | 关闭并合成 | 内容 |
| --- | --- | --- | --- |
| ENTITIES | Vulkan 地形合成后（`AFTER_CUTOUT_BLOCKS`） | `AFTER_BLOCK_ENTITIES` | 实体、方块实体（P7/P8） |
| PARTICLES | `AFTER_TRIPWIRE_BLOCKS` | `AFTER_PARTICLES` | `ParticleEngine.render`，包括物品拾取等经 `RenderType` 的粒子 |
| WEATHER | `AFTER_PARTICLES` | `AFTER_WEATHER` | 雨和雪（云使用 `VertexBuffer`，不会被捕获，仍由 GL 先绘制） |

这些窗口之外的天空和 GUI 后续由 P10、P11 捕获，其余调用照常由 GL 绘制。粒子与雨雪都使用原版 `particle` 着色器和 `PARTICLE` 顶点格式（位置、UV、颜色、光照）；Vulkan 端新增 `particle.vert`，片元沿用实体着色器“染色后 0.1 丢弃”的分支，并保留原版雾距离（不含视图旋转）。粒子图集、方块图集和雨雪贴图通过同一个贴图镜像同步；雨雪 UV 超出 [0,1] 时，按 GL 的 WRAP 参数选择重复采样器。

三个窗口共用每槽位的一对共享叠加层图像。GL 合成后立即 `finishOverlay()` 发出完成信号量，下一个窗口的 Vulkan 提交等待它后清屏复用，因此每个窗口都是独立的“等待 → 绘制 → 信号 → GL 租用”往返。每个窗口重新从当帧地形深度开始，每个“槽位 × 窗口”有独立的命令缓冲、fence、顶点/索引/uniform 环形缓冲和描述符池，同一帧内的多次提交互不覆盖。

粒子在 Vulkan 中只与地形深度测试，水、实体等 GL 深度由合成时的深度测试处理：位于水面后的粒子会被剔除，与原版先画水再画粒子的行为一致。尚未覆盖：使用自定义着色器或 `CUSTOM` 渲染类型自行绘制的模组粒子。

## 天空与云（P10）

天空的绘制路径与实体不同：天空盘、星空和地平线下的暗盘是 `LevelRenderer` 启动时上传的静态 `VertexBuffer`，用自己的矩阵调用 `drawWithShader`；日出扇形、太阳和月亮直接调用 `BufferUploader`；云是变化时重建的 `VertexBuffer`。`coremods/entities.js` 只改写 `LevelRenderer` 内的这些调用点（按 owner/描述符匹配并检查数量：4 次上传、4 次绘制、至少 3 次直接上传）。`SkyBuffers` 只为这几块缓冲保留 CPU 副本；区块网格和即时绘制在别处上传，不会被复制。

| 窗口 | 打开 | 关闭 | 绘制位置 |
| --- | --- | --- | --- |
| SKY | `ViewportEvent.ComputeFogColor`（清屏和画天空之前） | `AFTER_SKY` | 同帧地形提交中，先于地形画进不透明目标 |
| WEATHER（P9 已有） | `AFTER_PARTICLES` | `AFTER_WEATHER` | 叠加层：云（精美云的“仅深度”预通道加颜色通道）与雨雪 |

天空不需要额外提交：Vulkan 地形目标清为雾色、alpha 0，天空先以不写深度的方式画入，再画地形。合成时天空像素的 Vulkan 深度为 1.0，只有在 GL 深度也是 1.0（没有 GL 回退地形）的位置通过测试，因此范围外的 GL 地形仍会挡住天空。只有上一帧完成了 Vulkan 地形合成（非小窗模式）时，才会捕获本帧天空；如果本帧地形提交没有执行，这一帧会只显示雾色清屏。

新增着色器移植：`position`（天空盘、星空、暗盘，带雾）、`position_color`（日出扇形）、`position_tex`（太阳、月亮）、`position_tex_color`（末地天空）、`position_tex_color_normal`（云，带雾），对应 `simple_*.vert` 和片元着色器的两个新分支（在乘颜色调制之前，按 alpha == 0 或 < 0.1 丢弃）。没有贴图的着色器使用后端内置的白图（现在为 16×16，同时用作 GUI 全亮光照图）。`TRIANGLE_FAN` 在捕获时转换为带索引的三角形列表。新增两种状态：太阳、月亮和星星使用的加法混合 `(SRC_ALPHA, ONE)`，在 Vulkan 中按“加 rgb·a、不改变覆盖率”实现；颜色写掩码全关（云的深度预通道）。

尚未覆盖：维度特效或模组自定义的天空/云渲染（`DimensionSpecialEffects.renderSky/renderClouds` 返回 true 时原版代码不执行）、Fabulous 模式的云目标。测试场景通过会话内设置开启“精美”云：开发环境的 `options.txt` 关闭了云；1.20.1 在渲染距离小于 4 时也会关闭云。

## 手持物与 GUI（P11）

HAND 从 `AFTER_LEVEL` 到 `GameRenderer.render` 的 GUI 深度清除，保存不可变实体绘制包，在最终 GL blit 之后直接画进 Swapchain，随后清除私有深度再画 GUI；不再增加一次世界叠加层提交或 Vulkan/GL 信号量往返。手持物光照图由最终呈现通道独立持有并随世界 lightmap 更新，避免世界重置早于呈现完成时销毁采样资源。GUI 则从第一次 GUI 深度清除捕获到 swap，包括 HUD、菜单、背包、加载界面和 toast；第二次清除（HUD → Screen）也记录为回放命令。支持 `GuiGraphics` blit、纯色/渐变、彩色和灰度字形、普通物品图标、scissor、准星反色和暗角乘法。

`GuiPass` 在 GL framebuffer blit 之后直接向 Swapchain image view 动态渲染，使用每槽位私有深度；默认 fullbright 光照图为 16×16，支持原版 `(240,240)` 坐标。GUI 顶点/索引在捕获时复制，原版 BufferBuilder 批次立即释放。遇到暂未镜像的贴图、自定义着色器或不支持的状态时，按保存的状态和原顺序回放到 GL，该帧余下 GUI 继续由 GL 绘制；若手持物尚待交换链绘制，也先回放手持物、清深度，再回放 GUI。诊断截图使用 `captureNext` 的最终 Swapchain 截图；原版 F2 的 GL 截图会遗漏直接绘制的 GUI 和手持物。

字体在 `FontTexture.add` 的实际字形上传后更新页版本；旧版本 PBO 快照不会使新字形提前进入 Vulkan。GL 仍异步读回整页，Vulkan 仅上传像素变化矩形，在 GPU 复制旧页后覆盖补丁；旧图像在上传与引用帧都结束后回收。GL 合成同时保存和恢复 `GlStateManager` 与 `BlendMode.lastApplied` 两层混合缓存。

## 性能优化（Issue #2）

对应 [VK11-14 / issue #2](https://github.com/gengyoubo/VulkanForge/issues/2)。当前设备基线为 Vulkan 1.4。优先处理以下实际瓶颈，功能是否默认启用取决于能力检查和验证；功能验证尚不能证明 FPS 提升。

| 优先级 | 当前实现 | 验证重点 |
| --- | --- | --- |
| P0 异步上传 | 8 个 staging/command 槽位、32 MiB 在途预算、timeline 完成值；繁忙时交互更新延后重试，保留 dirty 状态和已完成网格 | GPU 队列被阻塞时不复用在途暂存数据、不阻塞更新入口 |
| P0 Geometry Arena / 内存 | 不透明 Section 共用 device-local 顶点池；Buffer/普通图像在设备级内存块中分配、合并空闲范围，host-visible 内存块持久映射 | 扩容、替换、删除、范围复用、无重叠和完整回收 |
| P0 采集与重建 | 复用原版 ChunkRenderDispatcher 成功提交的完整网格；每帧 1.5 ms 软预算转换/发布，优先编辑、可见和附近分区，队列年龄防止饥饿；取消过期转换 | 快速移动、区块加载、距离增减、资源重载、旧 epoch 结果丢弃 |
| P1 GPU Driven | GPU 视锥测试、atomicAdd 压缩可见命令、GPU count；一次顶点池绑定和一次 `vkCmdDrawIndirectCount` 覆盖不透明 Section | 45 Section 像素、零可见数量、池扩容后几何保持正确 |
| P1 实体 / GUI 批处理 | 相邻且 uniform、纹理、管线、裁剪完全相同的绘制合并；默认每槽位持久缓存纹理和 uniform Descriptor，保留 push 路径作 A/B | 绘制顺序、透明、深度清除、纹理版本和 Descriptor 生命周期 |
| P1 GL / Vulkan 交接 | 地形四张图共用一对 semaphore；叠加层颜色/深度共用一对 semaphore；一次 GL wait/signal 包含该组所有纹理 | 所有权往返、独立窗口的深度顺序、多帧与关闭 |

上传仍在同一图形队列，使用资源屏障保证后续绘制看到新数据。首次图集也通过异步 PBO 读回；完整图集替换、场景重置、缩放、关闭和非交互压力测试仍保留必要等待。内存池区分 Buffer 与 optimal image，遵守各资源的对齐和 mandatory dedicated allocation；跨 GL 的导出共享图像继续使用专用分配。内存分配与候选扫描仍可能超过软预算；方块模型不再由 WorldCapture 重建。

`FrameGraph` 已接管最终呈现：`GL blit → Hand（可选）→ GUI（可选）→ Capture（可选）→ Present`。图像使用声明生成读写依赖、稳定拓扑顺序、layout/队列所有权屏障，并拒绝未初始化读取、未知依赖和环。世界通道的跨提交同步仍由现有代码管理。

| Vulkan 1.4 功能 | 接入策略 |
| --- | --- |
| Push Descriptor | 已实现 A/B 路径，优先核心入口，必要时使用等价 KHR 入口。本机高频切换测试中持久缓存普通 Descriptor 更快，因此默认 `CACHED_SET`；`-PpushDescriptorSmoke` 可测试 push 路径 |
| Host Image Copy | 后续先比较小光照图和动态补丁；需查询格式、layout 与性能属性，比较 CPU 时间、采样性能及 P95/P99，避免重新引入更新等待 |
| Dynamic Rendering Local Read | 后续在同一 Vulkan rendering 范围的半透明或后处理中做原型；GL 回退边界仍需显式交接 |
| Vertex Attribute Divisor | 后续用于共享网格的实体/机械动画及实例化粒子；先建立实例数据，当前捕获的展开顶点尚未接入 |

能力信息区分“支持”与“启用”：BDA、descriptor indexing、local read、host image copy 等目前仅查询。API 语义参考 Khronos 的 [push descriptor](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPushDescriptorSet.html)、[host image copy](https://docs.vulkan.org/features/latest/features/proposals/VK_EXT_host_image_copy.html) 和 [local read](https://docs.vulkan.org/features/latest/features/proposals/VK_KHR_dynamic_rendering_local_read.html)。

本机 RTX 3050 Laptop、关闭校验层的交替 A/B：同样 256 次绘制 / 32 张轮换贴图，预热后各 120 个样本；持久缓存 Descriptor 的 CPU 录制中位数约 0.102 ms、P95 0.215 ms，缓存写入信息的 push 路径约 0.144 ms、P95 0.324 ms。这是合成工作负载的 CPU 录制时间，不能换算为游戏 FPS 收益。可运行 `.\gradlew.bat :vulkan-lab:benchmarkBindings` 重测。

报告指标：`MESH_ARENA=[capacityVertices, uploads, grows] indirectBatches=...`；`ASYNC_UPLOADS=[submitted, completed, bytes, backpressureWaits, timelineEnabled]`；`GPU_MEMORY=[reservedBytes, liveBytes, peakBytes, allocations, blocks]`；`GUI_BINDINGS` / `WORLD_BINDINGS` 为 `[allocatedSets, pushCommands, uniformUpdates, bindingCacheHits, mergedDraws]`；`GL_VULKAN_READ_LEASES groups=... images=...` 表示分组前后的同步单位。`PRESENT_GRAPH` 记录 pass 和图像屏障数量。GPU 时间和帧率比较应关闭校验层、固定场景与 VSync。

```powershell
# 人为阻塞 GPU 队列，检查非阻塞上传准入、内存范围复用及关闭后无泄漏
.\gradlew.bat -PvalidationSmoke :vulkan-lab:smokeResources
# 间接绘制、顶点池扩容、GUI 合批 / push descriptors、分组交接与 Render Graph
.\gradlew.bat -PvalidationSmoke :vulkan-lab:smokePresent
# 对照验证 Vulkan 1.4 push descriptor 路径
.\gradlew.bat -PvalidationSmoke -PpushDescriptorSmoke :vulkan-lab:smokePresent
# 强制逐 Section 绘制、fence 上传槽位和普通 Descriptor，检查回退
.\gradlew.bat -PvalidationSmoke -PlegacyGpuSmoke :vulkan-lab:smokePresent
# 完整 Forge 场景，增加快速移动重建与新字形增量检查
.\gradlew.bat -PvalidationSmoke -PworldSmoke runClient
```

2026-10-26 本轮回归：资源压力、GPU Driven / push 与回退路径、世界和兼容桥像素检查、完整 Forge 场景及 `build` 均通过。新增远处/垂直 Section 覆盖、4→6→4 距离变化、退出世界缓存释放、手持物直画交换链及 GUI 回退顺序检查。主窗口启用 Khronos 与同步校验，完成 12978 帧呈现，`VALIDATION_ERRORS=0`；5485 次异步上传背压等待为零。手持物光照图按版本绑定后，同一回归流程的 uniform Descriptor 更新从 1928 次降至 6 次；这是缓存工作量计数，不代表 FPS 提升。报告位于 `run/logs/vulkanforge/world-client-smoke/report.txt` 与 `run/logs/vulkanforge/main-window-report.txt`。

## OpenGL 4.6 后端（Issue #4）

对应 [GL43-46 / issue #4](https://github.com/gengyoubo/VulkanForge/issues/4)。配置 `renderer.backend = "OPENGL"`、`opengl.version = "GL_4_6"`，重启后进入世界按 F8（或主菜单左下角按钮）开启。配置在窗口创建前直接读取 TOML（Forge 此时尚未加载客户端配置），所以修改需重启；`-Dvulkanforge.startup=OPENGL:GL_4_6` 可只对本次启动覆盖。目前已实现的组合只有 Vulkan 1.4 与 OpenGL 4.6：Vulkan 1.2/1.3 保留原版 GL 窗口，OpenGL 4.3–4.5 只请求对应上下文、地形仍走原版，配置界面标注“未实现”。OpenGL 后端不创建 Vulkan 主窗口，也不运行启动期 Vulkan 探测。

Forge 早期窗口按 4.6 → 3.2 依次尝试，已经拿到驱动支持的最高版本；只有关闭 `earlyWindowControl` 且上下文低于 4.6 时才按 4.6 core 重建窗口。所有绘制都在游戏自己的 GL 上下文中直接画进主 framebuffer 与原版深度，不需要合成、共享图像或纹理读回：直接采样原版方块图集和光照图。

| GL 版本 | 功能 | 当前用法 |
| --- | --- | --- |
| 4.3 | Compute Shader、SSBO、Multi Draw Indirect | `cull.comp`：原版可见性位图 + 视锥 + Hi-Z，按 solid / cutoutMipped / cutout 三层写 `DrawElementsIndirectCommand`；`hiz.comp` 构建深度金字塔 |
| 4.4 | Buffer Storage、Persistent Mapping | 不可变存储；上传暂存、每次绘制的 UBO、半透明与实体命令、实体顶点/索引/逐绘制记录都是持久映射环形缓冲，按段 fence |
| 4.5 | DSA | `glCreate*`、`glNamedBuffer*`、`glVertexArray*`、`glBindTextureUnit`、采样器对象；顶点池按 GPU 拷贝扩容 |
| 4.6 | Indirect Count、SPIR-V | 每层一次 `glMultiDrawElementsIndirectCount`（GPU 计数）；着色器构建期由 shaderc 编成 GL SPIR-V（`:vulkan-lab:glSpirv`），驱动拒绝时回退同一份 GLSL 4.60 |

### 地形

地形直接接收原版 BLOCK 格式四边形（`WorldSink.quads`），不再展开成三角形：每个四边形 128 字节（原 168 字节），共享一份 0 1 2 / 2 3 0 索引缓冲，按 `baseVertex` 定位。三层分别使用原版的 mip 过滤与 alpha 阈值（solid 不丢弃、cutoutMipped 0.5、cutout 0.1 且不采样 mip），并接入原版雾。Clip Control 没有使用：地形与原版共享深度缓冲，改深度范围会让两边深度不一致。

剔除依次为：

1. **原版可见性**：每帧读取 `LevelRenderer.renderChunksInFrustum`（视锥 + 原版遮挡图洪泛），按槽位位图上传；GL 4.6 只绘制原版本帧也会绘制的 Section。此前 `cull.comp` 只做视锥剔除，实际比原版画得多（测试场景 79 个槽位中原版只保留 44 个）。读取失败时退回仅视锥。
2. **视锥**：`cull.comp` 按 Section 包围盒测试。
3. **Hi-Z**：`AFTER_CUTOUT_BLOCKS`（全部不透明地形已写入深度、实体尚未绘制）用 `hiz.comp` 把主 framebuffer 深度做成 R32F 最大值金字塔；下一帧用**上一帧**相机投影包围盒，选择使矩形最多覆盖 2×2 texel 的层级，盒子最近深度比该区域最远深度更远才剔除。保守规则：跨近平面、转向超过 10°、移动超过 2 格、投影变化超过 2%、分辨率变化、世界切换时该帧不使用 Hi-Z。

半透明 Section 由远到近、Section 内四边形由远到近，一次 `glMultiDrawElementsIndirect`。上传时先以采集顺序写入（立即可画），之后按距离由近到远在每帧 32768 个四边形预算内排序；小幅移动后对上一次的顺序做插入排序（接近线性），移动超过 8 格才完整排序；远处 Section 的重排阈值按距离放宽；排序缓冲复用，索引直接写入持久映射暂存区。

上传批处理：网格从 Java 数组直接写入持久映射暂存环形缓冲（不再经 `memAlloc` 中转），复制命令排队，源与目标都连续时合并（同一 Section 的三层、加载时连续分配的 Section），在每次地形绘制前统一发出。环形缓冲只在游标离开某段时才放 fence：`glFenceSync` 在本机每次 20–47 µs，此前每个通道每帧都会创建。

### 实体与方块实体

Vulkan 地形之后到 `AFTER_BLOCK_ENTITIES` 之间，`NEW_ENTITY` 格式的 `rendertype_entity_*` / `armor_cutout_no_cull` / `entity_shadow` 绘制被复制进持久映射环形缓冲；256 字节逐绘制记录（矩阵、ColorModulator、光照方向、雾）放在 SSBO，每条间接命令的 `baseInstance` 是记录下标，SSBO 每次刷新只绑定一次。不透明绘制按 GL 状态与贴图分组（首次出现顺序），混合绘制保持原顺序并放在之后，每组一次 `glMultiDrawElementsIndirect`；连续 QUADS 使用静态四边形索引，不在 CPU 写索引。其他绘制（发光、文字、输出到其他 framebuffer 等）先刷新已捕获部分再由原版绘制，保持画家顺序。Fabulous 模式不启用实体通道。

GL 状态不再用 `glGet*` 查询：`GlStateCache` 按类型反射读取原版 `GlStateManager` 与 `ShaderInstance` 的缓存（生产环境字段名为 SRG），创建时与真实 GL 状态比对，不一致或布局不符则退回查询；`-Pgl46Verify` 会逐次比对（检查中 79752 次全部一致）。顶点数组不恢复，而是令 `BufferUploader` 失效，原版每次绘制前都会重新绑定。

方块实体网格缓存：`coremods/block_entities.js` 改写 `BlockEntityRenderDispatcher.setupAndRender` 中唯一的渲染器调用。目前只接管原版 `ChestRenderer` 本身（不含子类），以 BlockState + 方块实体类为键：首次用单位矩阵运行一次渲染器，取得方块空间网格和该 RenderType 的 GL 状态，上传到设备本地缓存；之后每个箱子只提交一个实例（PoseStack 位姿与法线矩阵、按 `BrightnessCombiner` 计算的光照、覆盖层），同一网格的全部实例一次实例化间接绘制。盖子正在开合的箱子、无法缓存的 RenderType 仍走原版。资源重载时清空缓存。

### 检查与数据

```powershell
# 平坦世界：水、玻璃、树叶、牛群、箱子、带孔石墙；GL 4.6 开 → 关（原版）→ 再开 → 飞行 → 180° 转向
.\gradlew.bat -Pgl46Smoke runClient
# 逐次比对 GL 状态缓存与 glGet（正确性检查，计时不具代表性）
.\gradlew.bat -Pgl46Smoke -Pgl46Verify runClient
# A/B 开关，逗号分隔：glsl, noHiZ, noVanillaVisibility, noBlockEntityCache, noEntities
.\gradlew.bat -Pgl46Smoke -Pgl46Flags=noBlockEntityCache runClient
```

报告：`logs/vulkanforge/gl46-report.txt`（上下文、着色器路径、状态缓存、首帧 GL 错误、关闭统计），检查结果与截图在 `logs/vulkanforge/gl46-smoke/`。2026-10-26 本机 RTX 3050 Laptop / NVIDIA 610.78、渲染距离 4 的测试场景（以下 CPU 时间指提交 GL 命令的 CPU 耗时，不是 GPU 时间，也未测 FPS）：

| 指标 | 结果 |
| --- | --- |
| 与原版截图差异 | 平均 0.13（0–255），超过 48 的像素 0.01–0.02% |
| 每帧 Section | 槽位 79 → 原版可见 44 → 视锥 42 → Hi-Z 再剔除 18（43%，带孔石墙专门构造为原版遮挡图无法剔除的情形，开阔地形收益会小得多）；180° 转向帧正确跳过 Hi-Z |
| 实体合批 | 每次 MDI 平均 5.25 个绘制，splits 0，fallbacks 0；限制主要是贴图种类，跨贴图需 bindless |
| fence 优化前 → 后（每帧均值） | 实体刷新 110.9 → 36.2 µs，不透明地形 110.1 → 40.1 µs，半透明 53.3 → 15.6 µs |
| 静态索引 | 实体捕获约 44 → 7 µs/帧（无阴影时） |
| 每帧 p50 / p95 | 不透明地形 31 / 60 µs，半透明 8 / 16 µs，实体捕获 19 / 39 µs，实体刷新 31 / 72 µs |
| 箱子网格缓存 | 26 个箱子共用 1 个缓存网格、每帧 1 次实例化绘制；渲染器调用每个 2.52 → 1.40 µs |
| 上传批处理 | 51–79 个 Section 网格合并为 22–24 次 GPU 复制 |

## 减少 OpenGL 依赖

| 优先级 | 路径 | 当前状态 |
| --- | --- | --- |
| P0 | 覆盖范围外地形 | 已取消固定相机立方体；使用实际 ViewArea 的水平距离与全部垂直层，逐步上传原版已经编译的非空 Section |
| P0 | Vulkan 结果的 GL 合成 | 手持物已迁到最终交换链，与 GUI 共用呈现提交；地形、实体、透明与天气的中途 GL 深度合成仍保留 |
| P1 | 重复区块构建 | 已复用原版上传前网格，保留原版构建调度；Vulkan 转换/发布在单独的有界帧预算内推进 |
| P1 | 图集、光照、实体纹理 | 仍使用异步 PBO 镜像；后续从 CPU 上传源接入，保留无法捕获上传源时的读回 |
| P1 | 特殊 RenderType / Glint / 阴影 | 继续逐项移植，当前不支持的状态仍走 GL |
| P2 | 裂纹、轮廓、特效、Fabulous 和后处理 | 后续增加对应 Vulkan Pass，保持原版顺序和深度语义 |
| P2 / 长期 | 自定义 Shader、独立渲染器、GL 资源生命周期 | 通用捕获兼容与专用适配并行，逐步替换资源所有者 |

网格快照只从成功的原版 RebuildTask 提交取得；index-only 半透明排序不会误当作顶点更新。原版 RenderChunk 池换坐标、ViewArea 重建/释放、世界切换都会淘汰旧数据。GL 跳过绘制前同时核对原版 compiled 对象、Section 坐标和 Vulkan 已接纳的快照，未接纳的 Section 继续由 GL 绘制。CPU 上传副本缓存限额 128 MiB，已发布网格逻辑预算 192 MiB，最多 65534 个非空 Section；超过限制的部分保持 GL 路径，不能据此宣称所有场景已完全脱离 GL。缓存快照和转换输出有独立的生命周期，预算不等于进程总内存上限。

Forge 检查使用远处标记方块、旧范围之外的底层地形，以及 4→6→4 渲染距离变化验证覆盖；已接管数量只统计非空 Section，不能与早期包含空分区的固定 45 个计数直接比较。`nativeMeshStats` 为 `[copies, successfulCommits, missingSnapshots, indexOnlyUpdates, cachedRawBytes, viewAreaSections, readySnapshots]`。最终呈现报告增加 `HAND_SWAPCHAIN_PASSES`。

## 接入结构

- `src/main/java/github/com/gengyoubo/vulkanforge/client/ClientEvents.java`：Forge 生命周期、世界/屏幕/HUD 事件、按键。
- `META-INF/coremods.json` / `coremods/main_window.js`：Forge ASM coremod，仅替换 Window 中的 Forge 窗口创建/位置、GL 上下文绑定/销毁调用，以及 RenderSystem 中的 GLFW swapBuffers 调用。按 owner、名称、完整描述符匹配并检查数量，不依赖 Mixin 或 Minecraft 方法名映射。
- `NativeMainWindow`：窗口生命周期和反射边界；内嵌 Vulkan 后端使用以平台加载器为父的独立 URLClassLoader，仅交换原始数值、数组和 JDK 回调接口。
- `gl-interop/` / `GlInterop`：所有 GL memory object、framebuffer、semaphore 和 blit 调用都在游戏现有的 LWJGL 中完成，通过 JDK 回调供 Vulkan 后端使用；不在游戏线程上加载第二套 GL 绑定或切换不同版本的 GL 调度表。实验模块复用源码，独立测试时编译到其自己的 LWJGL 版本。
- `MainWindowBackend` / `SharedPresentImage`：Win32 Surface、GPU UUID 匹配、专用导出图像、GL memory object / semaphore 导入、显式队列所有权转换、Y 翻转、Swapchain acquire/submit/present、缩放和退出释放。正常帧无 CPU 像素拷贝；显式请求截图时才读回 Swapchain。
- `SharedWorldRenderer`：同一个 VkDevice/队列内的 WorldScene、双缓冲共享地形颜色图像及独立深度、GPU 时间戳和 GL 采样生命周期。
- `TerrainMeshes` / `WorldCapture`：在原版 Section 上传前复制不可变 BLOCK 顶点，在 RebuildTask 成功提交后才发布完整多层快照；不再重复遍历方块、调用模型或构建流体。覆盖跟随原版 ViewArea 的渲染距离与全部世界高度，原版遮挡/构建调度继续决定哪些网格可用。1.5 ms 软预算分帧转换、异步上传；未完成、过期、超预算或不支持的网格保持 GL 绘制。
- `TerrainPresenter`：在 cutout 阶段后合成 Vulkan 颜色，保留原版 GL 深度，后续原版渲染继续执行；恢复改动的 GL 状态。
- `TextureCapture`：首次读取方块图集；后续方块图集和 16×16 光照图使用可复用 PBO / GL fence 异步读回，零超时轮询，完成后才映射。每 100 ms 请求检查一次变化，图集按 32×32 区域轮询，每次最多发送 8 个变化区域；队列满时保留差异以便重试。恢复纹理绑定和 pixel-pack 状态，重载/关闭时回收 PBO 与 fence。
- `VulkanPreview` / `SharedFrameTexture`：同进程路径只引用后端拥有的 GL 纹理，TextureManager 不负责删除共享图像；`FrameTexture` 仅用于兼容路径的 RGBA 上传。
- `OffscreenBridge`：仅用于兼容路径，后台双向通信、有界命令队列和三缓冲最新帧邮箱；相机更新立即唤醒写线程，只发送最新状态，旧场景帧不会进入新场景。关闭 stdin 请求 Vulkan 进程释放资源，超时强制回收。
- `bridge-protocol/`：两个模块共享的有界命令与帧协议。
- `vulkan-lab/`：LWJGL 3.3.6，Vulkan 实例、设备、离屏图像、同步、内存与交换链。
- `WorldScene`：分区 GPU 缓存、图集上传、采样描述符、带相机相对坐标的世界绘制。

主窗口共享地形按游戏实际 framebuffer 尺寸、在当帧 cutout 阶段绘制，相机直接传递；旧兼容桥仍固定 **640×360、最高 60 FPS**，每帧读回并上传约 0.88 MiB。最终呈现采用两帧轮转，每帧独立的共享图像、命令缓冲、acquire semaphore 和 fence，仅复用该槽位时等待；呈现 semaphore 按 Swapchain 图像保存。两个等待信号量都在 BLIT 阶段等待，布局转换和队列所有权获取的第一同步范围同为 BLIT，保证排在 acquire / GL 完成之后。Surface 在全屏或最小化过渡中报告 0×0 时跳过该帧并保留旧 Swapchain；尺寸比较基于请求尺寸，避免被 Surface 钳制的尺寸导致每帧重建。若某帧在 acquire 后、提交前出错，会用空提交消耗 acquire 信号量、重建 fence/semaphore 并标记 Swapchain 重建，避免退出时卡在 fence 或残留未等待的信号量。报告记录 CPU fence 等待和 present 调用耗时。

世界图像清屏 alpha 为零，只有 Vulkan 方块像素覆盖原版颜色。图集动画与原版光照图增量同步，不保证呈现原版每一个动画帧；动画区域较多时按轮询预算分批更新。镂空阈值采用原版 0.1，图集目前只采样 mip level 0；光照采样坐标和线性过滤与 1.20.1 原版一致。雾和后处理尚未接入 Vulkan。原版 Solid/Cutout 接管、GPU 视锥剔除、批量间接绘制和异步增量上传已接入；后续继续扩大可见范围并改善重建调度。当前仍是功能原型，未承诺帧率提升。

2026-10-26 本机 Windows / RTX 3050 Laptop 验证：GPU 世界管线检查通过；Forge 客户端成功复用原版非空分区、树叶/花等镂空模型、1024×512 图集和原版光照图。相机、方块更新、资源重载与动画贴图更新检查通过；测试场景切换夜晚后，地形平均亮度约从 137 降至 24，切回白天恢复。检查图片与报告位于 `run/logs/vulkanforge/world-client-smoke/`。

主窗口地形/三角形预览位于同进程的隔离加载器中，避免新旧 LWJGL 类和 native 库混入同一个加载器。独立测试窗口、能力探测和旧兼容预览仍使用子进程。打包的 Gradle wrapper 来自 Forge 官方 MDK；LWJGL 及其原生依赖许可证随实验模块 jar 保留。
