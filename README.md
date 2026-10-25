# VulkanForge

Minecraft **1.20.1 / Forge 47.4.28 / Java 17** 客户端 Vulkan 渲染实验。

当前已经打通四条路径：

- 独立 Vulkan 1.4 窗口：交换链、动态渲染、缩放重建、三角形绘制。
- Minecraft 主窗口呈现（Windows）：可见窗口使用 `GLFW_NO_API`，创建 Win32 Vulkan Surface / Swapchain；原版主 framebuffer 经共享 GPU 图像和跨 API 信号量交给 `vkQueuePresentKHR`，包含世界、手持物和 HUD。正常呈现过程不经过 CPU 像素读回。
- 游戏主窗口预览：Vulkan 离屏绘制旋转三角形 → staging buffer 读回 → 后台传输 RGBA → 游戏线程上传纹理 → 主菜单 / HUD 显示。
- 世界不透明和镂空方块：采集原版烘焙模型、贴图 UV、染色、环境遮蔽与光照坐标 → 分区顶点缓冲 → Vulkan 图集/光照图采样、相机变换、alpha 丢弃、D32 深度测试 → 在原版 cutout 阶段之后合成。

**这是混合渲染原型。主窗口的最终呈现已交给 Vulkan；solid、cutout、cutoutMipped 地形仍通过独立进程的 CPU 读回桥合成，尚未迁移到同进程 GPU 共享。** 原版区块绘制保留以提供深度；天空、范围外地形、透明层、实体、粒子、手持物和 HUD 在隐藏的 OpenGL 上下文中绘制，然后统一由主窗口 Swapchain 呈现。

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

主窗口接管在启动时完成；F8 控制地形/三角形桥，独立于主窗口的呈现方式。窗口缩放、全屏尺寸变化和 VSync 设置变化会重建 Swapchain；最小化时跳过呈现，恢复后继续。

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
| `bridge-report.txt` | 游戏窗口预览的离屏渲染与退出结果 |
| `triangle-report.txt` | 独立窗口测试 |
| `main-window-report.txt` | GPU 共享预检、主窗口 Surface、Swapchain 尺寸/呈现模式、帧数和资源关闭 |

## 验证命令

```powershell
# 不启动 Minecraft，直接开发独立实验模块
.\gradlew.bat -p vulkan-lab runProbe
.\gradlew.bat -p vulkan-lab runTriangle

# 真实 GPU 集成检查：RGBA 像素、动画、二进制帧协议、正常退出和停止请求
.\gradlew.bat :vulkan-lab:smokeBridge

# 真实 GPU 世界管线：镂空阈值和深度、图集区域更新、光照图更新、相机、分区删除与场景重置
.\gradlew.bat :vulkan-lab:smokeWorld

# Windows 主窗口呈现管线：GPU 共享、四象限颜色/方向、缩放、VSync 和零尺寸跳过
.\gradlew.bat :vulkan-lab:smokePresent

# 完整 Forge 检查：ASM 钩子、Vulkan 主窗口、世界、重载、昼夜光照、缩放及最小化恢复，然后退出
# 测试源集不会被打入模组 JAR；会在 run/saves 留下测试世界和日志中的 PNG
.\gradlew.bat -PworldSmoke runClient

# 交换链缩放检查
java -jar vulkan-lab/build/libs/vulkan-lab.jar --triangle --frames=90 --resize-smoke

# 安装 Vulkan SDK 校验层后，可要求其必须存在
java -jar vulkan-lab/build/libs/vulkan-lab.jar --probe --require-validation
```

普通 `build` 不要求 GPU 或 Vulkan 驱动。GPU 检查需要 Vulkan 1.4 硬件和驱动。日志中 `VALIDATION=UNAVAILABLE` 表示没有启用校验层；此时错误计数为零不代表通过了校验层检查。

## 接入结构

- `src/main/java/github/com/gengyoubo/vulkanforge/client/ClientEvents.java`：Forge 生命周期、世界/屏幕/HUD 事件、按键。
- `META-INF/coremods.json` / `coremods/main_window.js`：Forge ASM coremod，仅替换 Window 中的 Forge 窗口创建/位置、GL 上下文绑定/销毁调用，以及 RenderSystem 中的 GLFW swapBuffers 调用。按 owner、名称、完整描述符匹配并检查数量，不依赖 Mixin 或 Minecraft 方法名映射。
- `NativeMainWindow`：窗口生命周期和反射边界；内嵌 Vulkan 后端使用以平台加载器为父的独立 URLClassLoader，仅交换原始数值、数组和 JDK 回调接口。
- `gl-interop/` / `GlInterop`：所有 GL memory object、framebuffer、semaphore 和 blit 调用都在游戏现有的 LWJGL 中完成，通过 JDK 回调供 Vulkan 后端使用；不在游戏线程上加载第二套 GL 绑定或切换不同版本的 GL 调度表。实验模块复用源码，独立测试时编译到其自己的 LWJGL 版本。
- `MainWindowBackend` / `SharedPresentImage`：Win32 Surface、GPU UUID 匹配、专用导出图像、GL memory object / semaphore 导入、显式队列所有权转换、Y 翻转、Swapchain acquire/submit/present、缩放和退出释放。正常帧无 CPU 像素拷贝；显式请求截图时才读回 Swapchain。
- `WorldCapture`：相机附近 **3×3×5 个 16³ 分区**，每 50 ms 最多开始重建一个；模型采集按约 1 ms 的软预算分到多个渲染帧中，只有网格变化才发送。移动时增删分区，世界/维度切换和资源重载时取消未完成采集并重置场景。单个模组模型和最终字节转换仍可能超过预算，因此不保证严格的耗时上限。
- `TerrainPresenter`：在 cutout 阶段后合成 Vulkan 颜色，保留原版 GL 深度，后续原版渲染继续执行；恢复改动的 GL 状态。
- `TextureCapture`：首次读取方块图集；后续方块图集和 16×16 光照图使用可复用 PBO / GL fence 异步读回，零超时轮询，完成后才映射。每 100 ms 请求检查一次变化，图集按 32×32 区域轮询，每次最多发送 8 个变化区域；队列满时保留差异以便重试。恢复纹理绑定和 pixel-pack 状态，重载/关闭时回收 PBO 与 fence。
- `VulkanPreview` / `FrameTexture`：只在游戏渲染线程处理纹理上传和绘制；关闭时释放纹理与 native buffer。
- `OffscreenBridge`：后台双向通信、有界命令队列和三缓冲最新帧邮箱；相机更新立即唤醒写线程，只发送最新状态，旧场景帧不会进入新场景。关闭 stdin 请求 Vulkan 进程释放资源，超时强制回收。
- `bridge-protocol/`：两个模块共享的有界命令与帧协议。
- `vulkan-lab/`：LWJGL 3.3.6，Vulkan 实例、设备、离屏图像、同步、内存与交换链。
- `WorldScene`：分区 GPU 缓存、图集上传、采样描述符、带相机相对坐标的世界绘制。

地形桥的离屏目标固定为 **640×360、最高 60 FPS**，每帧读回并上传约 0.88 MiB；最终呈现按主窗口 framebuffer 尺寸执行。主窗口采用两帧轮转，每帧独立的共享图像、命令缓冲、acquire semaphore 和 fence，仅复用该槽位时等待；呈现 semaphore 按 Swapchain 图像保存。报告记录 CPU fence 等待和 present 调用耗时。世界图像清屏 alpha 为零，只有 Vulkan 方块像素覆盖原版颜色。图集动画与原版光照图增量同步，不保证呈现原版每一个动画帧；动画区域较多时按轮询预算分批更新。镂空阈值采用原版 0.1，图集目前只采样 mip level 0；光照采样坐标和线性过滤与 1.20.1 原版一致。雾、后处理和半透明排序尚未接入 Vulkan。后续需要把地形管线迁入同进程后端以消除地形读回和相机传输延迟、扩大可见范围、复用原版网格重建调度，并逐步替换原版 pass。当前仍是功能原型，未承诺帧率提升。

2026-10-26 本机 Windows / RTX 3050 Laptop 验证：GPU 世界管线检查通过；Forge 客户端成功采集 45 个分区、树叶/花等镂空模型、1024×512 图集和原版光照图。相机、方块更新、资源重载与动画贴图更新检查通过；测试场景切换夜晚后，地形平均亮度约从 137 降至 24，切回白天恢复。检查图片与报告位于 `run/logs/vulkanforge/world-client-smoke/`。

地形预览和独立窗口仍在子进程中运行；主窗口后端位于同进程的隔离加载器中，避免新旧 LWJGL 类和 native 库混入同一个加载器。打包的 Gradle wrapper 来自 Forge 官方 MDK；LWJGL 及其原生依赖许可证随实验模块 jar 保留。
