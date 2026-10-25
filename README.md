# VulkanForge

Minecraft **1.20.1 / Forge 47.4.28 / Java 17** 客户端 Vulkan 渲染实验。

当前已经打通四条路径：

- 独立 Vulkan 1.4 窗口：交换链、动态渲染、缩放重建、三角形绘制。
- Minecraft 主窗口呈现（Windows）：可见窗口使用 `GLFW_NO_API`，创建 Win32 Vulkan Surface / Swapchain；原版主 framebuffer 经共享 GPU 图像和跨 API 信号量交给 `vkQueuePresentKHR`，包含世界、手持物和 HUD。正常呈现过程不经过 CPU 像素读回。
- 游戏主窗口预览：同进程 Vulkan 绘制旋转三角形 → 导出共享 GPU 图像 → 原有 GL 上下文采样 → 主菜单 / HUD 显示。
- 世界不透明和镂空方块：采集原版烘焙模型、贴图 UV、染色、环境遮蔽与光照坐标 → 分区顶点缓冲 → Vulkan 图集/光照图采样、相机变换、alpha 丢弃、D32 深度测试 → 在原版 cutout 阶段之后合成。

**这是混合渲染原型。主窗口的最终呈现已交给 Vulkan；Windows 主窗口路径中的 WorldScene 已接入同一个 Vulkan 后端，solid、cutout、cutoutMipped 地形通过共享 GPU 纹理合成，正常地形帧不经过 CPU 像素读回或 FrameProtocol。** Vulkan 覆盖范围内的半透明方块与水也由 Vulkan 绘制（P6 第一步），常见实体、方块实体和告示牌文字（P7/P8），以及粒子和雨雪（P9）由 Vulkan 叠加层通道回放；天空、云、范围外地形、Fabulous 模式下的半透明层、不支持的着色器、手持物和 HUD 仍在隐藏的 OpenGL 上下文中绘制，然后统一由主窗口 Swapchain 呈现。关闭主窗口 Vulkan 路径时保留旧的子进程读回预览作为兼容路径。

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

# 完整 Forge 检查：ASM 钩子、Vulkan 主窗口、世界、水池与染色玻璃（P6 半透明）、箱子/告示牌/牛（P7/P8 实体通道）、营火/火把粒子与降雨（P9）、重载、昼夜光照、缩放、最小化恢复、全屏往返、切换世界，然后退出
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

`-PvalidationSmoke` 要求主窗口校验层实际启用，并在客户端退出后检查 `VALIDATION=ENABLED`、`VALIDATION_ERRORS=0`、共享地形计时与完整关闭标记；兼容路径额外检查地形桥。检查缺少校验层或退出超时会失败。同进程检查核对后端 PID 等于游戏 PID、45 个分区和无地形子进程启动。`smokePresent` 额外检查 acquire 后取消帧、GL 交接前/后故障恢复，以及仍持有已 acquire 图像时的关闭。同步验证通过 `VK_VALIDATION_VALIDATE_SYNC=1` 开启，正式性能测量时应关闭校验层。

## 半透明地形（P6 第一步）

`WorldCapture` 现在同时采集流体：水方块的 `RenderShape` 不是 `MODEL`，此前完全没有被采集，而 P4 跳过原版 solid 层后，覆盖范围内的岩浆会缺失。流体按 `ItemBlockRenderTypes.getRenderLayer(FluidState)` 分层：岩浆进入不透明网格，水与 `translucent` 层方块（染色玻璃、冰、史莱姆块、蜂蜜块等）进入 `Mesh.translucent`。

`WorldScene` 为每个含半透明几何的分区保存四边形中心，并为两个帧槽位各准备一份索引缓冲。槽位只在自身 fence 完成后重写索引，因此排序不会改动 GPU 正在读取的数据。与原版一致，相机移动超过 1 格后重新排序：每帧最多重排 16 个分区（最近的优先），尚未排序的槽位总是先排序再使用。绘制顺序为分区由远到近、分区内四边形由远到近，开启背面剔除（与原版 translucent 层相同，避免水面正反两面重复混合）。

`SharedWorldRenderer` 每个槽位共有四张共享图像：不透明颜色/深度，以及半透明颜色和独立深度。半透明深度先复制不透明深度，再记录最近的半透明表面；半透明颜色清为 (0,0,0,0)，以预乘 alpha `(ONE, ONE_MINUS_SRC_ALPHA)` 累积。四张图像每帧都经过同一套所有权转换和信号量往返，并在交换前结束 GL 租用。

GL 侧在 `AFTER_TRANSLUCENT_BLOCKS` 阶段合成，即原版实体和方块实体之后：使用预乘混合，并写入 `gl_FragDepth`，与 GL 深度做 LEQUAL 测试。实体位于水面前方时，会遮住该像素的半透明层；位于水下时，水面会混合在实体之上。原版 translucent 层只在同一帧确实会合成 Vulkan 结果的覆盖分区中跳过。

Fabulous 图形模式会把半透明层画进独立目标供透明后处理链使用，这种情况仍交给原版绘制。雾和水下视角的雾色尚未接入 Vulkan 地形；实体见 P7/P8，GUI 仍使用 OpenGL。

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

窗口之外的 `Tesselator` 用户（GUI、天空等）照常由 GL 绘制。粒子与雨雪都使用原版 `particle` 着色器和 `PARTICLE` 顶点格式（位置、UV、颜色、光照）；Vulkan 端新增 `particle.vert`，片元沿用实体着色器“染色后 0.1 丢弃”的分支，并保留原版雾距离（不含视图旋转）。粒子图集、方块图集和雨雪贴图通过同一个贴图镜像同步；雨雪 UV 超出 [0,1] 时，按 GL 的 WRAP 参数选择重复采样器。

三个窗口共用每槽位的一对共享叠加层图像。GL 合成后立即 `finishOverlay()` 发出完成信号量，下一个窗口的 Vulkan 提交等待它后清屏复用，因此每个窗口都是独立的“等待 → 绘制 → 信号 → GL 租用”往返。每个窗口重新从当帧地形深度开始，每个“槽位 × 窗口”有独立的命令缓冲、fence、顶点/索引/uniform 环形缓冲和描述符池，同一帧内的多次提交互不覆盖。

粒子在 Vulkan 中只与地形深度测试，水、实体等 GL 深度由合成时的深度测试处理：位于水面后的粒子会被剔除，与原版先画水再画粒子的行为一致。尚未覆盖：使用自定义着色器或 `CUSTOM` 渲染类型自行绘制的模组粒子、云（P10）。

## 后续阶段（P10–P11）

两个阶段都复用“捕获 → 镜像贴图 → Vulkan 回放 → 带深度合成”框架，主要工作是增加捕获点和着色器移植：

- **P10 天空与云**：天空使用 `VertexBuffer.drawWithShader`（`position`、`position_tex`、`position_color` 着色器），云使用 `rendertype_clouds`。需要镜像静态 `VertexBuffer` 网格（天空、星星、云在重建时上传一次），在地形之前绘制到同一个 Vulkan 场景目标中；这样地形清屏不再需要原版天空。
- **P11 手持物与 GUI**：手持物经 `RenderType.end`（可直接扩展 P8 捕获窗口）。GUI 由 `GuiGraphics` 产生，包括 `position_color`、`position_tex`、文字和物品图标。由于最终呈现已在 Vulkan 交换链，GUI 适合在 blit 之后直接画进交换链图像，不再经过 GL 合成。字体字形页需要随动态上传增量镜像。

## 接入结构

- `src/main/java/github/com/gengyoubo/vulkanforge/client/ClientEvents.java`：Forge 生命周期、世界/屏幕/HUD 事件、按键。
- `META-INF/coremods.json` / `coremods/main_window.js`：Forge ASM coremod，仅替换 Window 中的 Forge 窗口创建/位置、GL 上下文绑定/销毁调用，以及 RenderSystem 中的 GLFW swapBuffers 调用。按 owner、名称、完整描述符匹配并检查数量，不依赖 Mixin 或 Minecraft 方法名映射。
- `NativeMainWindow`：窗口生命周期和反射边界；内嵌 Vulkan 后端使用以平台加载器为父的独立 URLClassLoader，仅交换原始数值、数组和 JDK 回调接口。
- `gl-interop/` / `GlInterop`：所有 GL memory object、framebuffer、semaphore 和 blit 调用都在游戏现有的 LWJGL 中完成，通过 JDK 回调供 Vulkan 后端使用；不在游戏线程上加载第二套 GL 绑定或切换不同版本的 GL 调度表。实验模块复用源码，独立测试时编译到其自己的 LWJGL 版本。
- `MainWindowBackend` / `SharedPresentImage`：Win32 Surface、GPU UUID 匹配、专用导出图像、GL memory object / semaphore 导入、显式队列所有权转换、Y 翻转、Swapchain acquire/submit/present、缩放和退出释放。正常帧无 CPU 像素拷贝；显式请求截图时才读回 Swapchain。
- `SharedWorldRenderer`：同一个 VkDevice/队列内的 WorldScene、双缓冲共享地形颜色图像及独立深度、GPU 时间戳和 GL 采样生命周期。
- `WorldCapture`：相机附近 **3×3×5 个 16³ 分区**，每 50 ms 最多开始重建一个；模型采集按约 1 ms 的软预算分到多个渲染帧中，只有网格变化才发送。移动时增删分区，世界/维度切换和资源重载时取消未完成采集并重置场景。单个模组模型和最终字节转换仍可能超过预算，因此不保证严格的耗时上限。
- `TerrainPresenter`：在 cutout 阶段后合成 Vulkan 颜色，保留原版 GL 深度，后续原版渲染继续执行；恢复改动的 GL 状态。
- `TextureCapture`：首次读取方块图集；后续方块图集和 16×16 光照图使用可复用 PBO / GL fence 异步读回，零超时轮询，完成后才映射。每 100 ms 请求检查一次变化，图集按 32×32 区域轮询，每次最多发送 8 个变化区域；队列满时保留差异以便重试。恢复纹理绑定和 pixel-pack 状态，重载/关闭时回收 PBO 与 fence。
- `VulkanPreview` / `SharedFrameTexture`：同进程路径只引用后端拥有的 GL 纹理，TextureManager 不负责删除共享图像；`FrameTexture` 仅用于兼容路径的 RGBA 上传。
- `OffscreenBridge`：仅用于兼容路径，后台双向通信、有界命令队列和三缓冲最新帧邮箱；相机更新立即唤醒写线程，只发送最新状态，旧场景帧不会进入新场景。关闭 stdin 请求 Vulkan 进程释放资源，超时强制回收。
- `bridge-protocol/`：两个模块共享的有界命令与帧协议。
- `vulkan-lab/`：LWJGL 3.3.6，Vulkan 实例、设备、离屏图像、同步、内存与交换链。
- `WorldScene`：分区 GPU 缓存、图集上传、采样描述符、带相机相对坐标的世界绘制。

主窗口共享地形按游戏实际 framebuffer 尺寸、在当帧 cutout 阶段绘制，相机直接传递；旧兼容桥仍固定 **640×360、最高 60 FPS**，每帧读回并上传约 0.88 MiB。最终呈现采用两帧轮转，每帧独立的共享图像、命令缓冲、acquire semaphore 和 fence，仅复用该槽位时等待；呈现 semaphore 按 Swapchain 图像保存。两个等待信号量都在 BLIT 阶段等待，布局转换和队列所有权获取的第一同步范围同为 BLIT，保证排在 acquire / GL 完成之后。Surface 在全屏或最小化过渡中报告 0×0 时跳过该帧并保留旧 Swapchain；尺寸比较基于请求尺寸，避免被 Surface 钳制的尺寸导致每帧重建。若某帧在 acquire 后、提交前出错，会用空提交消耗 acquire 信号量、重建 fence/semaphore 并标记 Swapchain 重建，避免退出时卡在 fence 或残留未等待的信号量。报告记录 CPU fence 等待和 present 调用耗时。

世界图像清屏 alpha 为零，只有 Vulkan 方块像素覆盖原版颜色。图集动画与原版光照图增量同步，不保证呈现原版每一个动画帧；动画区域较多时按轮询预算分批更新。镂空阈值采用原版 0.1，图集目前只采样 mip level 0；光照采样坐标和线性过滤与 1.20.1 原版一致。雾和后处理尚未接入 Vulkan。后续需要接管原版 Solid/Cutout pass 及深度交接、扩大可见范围、复用网格重建调度并实现 GPU culling。纹理更新仍采用同步 Vulkan 上传，更新前等待旧地形绘制完成；P5 尚未实现异步增量上传与资源延迟回收。当前仍是功能原型，未承诺帧率提升。

2026-10-26 本机 Windows / RTX 3050 Laptop 验证：GPU 世界管线检查通过；Forge 客户端成功采集 45 个分区、树叶/花等镂空模型、1024×512 图集和原版光照图。相机、方块更新、资源重载与动画贴图更新检查通过；测试场景切换夜晚后，地形平均亮度约从 137 降至 24，切回白天恢复。检查图片与报告位于 `run/logs/vulkanforge/world-client-smoke/`。

主窗口地形/三角形预览位于同进程的隔离加载器中，避免新旧 LWJGL 类和 native 库混入同一个加载器。独立测试窗口、能力探测和旧兼容预览仍使用子进程。打包的 Gradle wrapper 来自 Forge 官方 MDK；LWJGL 及其原生依赖许可证随实验模块 jar 保留。
