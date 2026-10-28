# 原生 Vulkan 游戏渲染

实验性 `VK` 模式已经接入 Minecraft 1.20.1 / Forge 47.4.28 的实际游戏循环。地形、实体、粒子、天空、手持物和 GUI 使用 Vulkan 绘制，最终由 Vulkan Swapchain 呈现；整个原生路径不创建 OpenGL 上下文，不执行 OpenGL GPU 调用，不使用 GL/Vulkan 共享图像。默认配置仍为 `GL_VK`，需要选择 `VK` 并重启。

## 启动

使用 JDK 17、Windows x86_64。Gradle `runClient` 自动在 Forge 启动前加载 `vulkanforge-native-bootstrap.jar`；引导读取工作目录下的 `config/vulkanforge-client.toml`，仅在模式为 `VK` 时启用。普通启动使用 `run` 目录中保存的配置：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

已有 IntelliJ 运行配置应执行 `.\gradlew.bat genIntellijRuns` 更新。可以在游戏配置页选择 VK 后“保存并重启”；引导参数会随进程重启保留。无需 F8。

开发时可直接在独立 `run-native` 目录启动原生模式，避免修改普通游戏配置：

```powershell
.\gradlew.bat -PnativeGame runClient
.\gradlew.bat -PnativeGame -PnativeApi=V1_2 runClient
.\gradlew.bat -PnativeGame -PnativeApi=V1_3 runClient
```

默认原生 API 为 `V1_4`。普通游戏也支持 JVM 覆盖 `-Dvulkanforge.startup=VK:V1_3`；Gradle 会将该参数转交客户端。

外部启动器需要两个构建产物：将 `build/libs/vulkanforge-0.1.0.jar` 放入 `mods`，将独立的 `build/libs/vulkanforge-native-bootstrap.jar` 放在固定位置，并加入启动器的 **JVM 参数**：

```text
-javaagent:C:\absolute\path\vulkanforge-native-bootstrap.jar
```

若路径含空格，应按启动器要求对完整参数加引号。引导默认读取进程工作目录；启动器的工作目录与游戏目录不同时，用 `-javaagent:<引导jar>=<游戏目录>` 指定配置目录。缺少引导时会报告 `REJECTED_MISSING_NATIVE_BOOTSTRAP`，不会尝试隐藏 GL 上下文或回退 GL。旧报错中“backend is not connected”的无条件拒绝已移除。

## 游戏渲染路径

```text
JVM premain → 按配置启用原生引导 → 禁用 Forge 的早期 GL 加载窗口
Minecraft Window → GLFW_NO_API → Win32 Surface
CPU 纹理、顶点/索引、原版着色器源码、uniform 与绘制状态
  → NativeDeviceState（CPU 资源及状态）
  → NativeGameBackend / NativeShaderProgram
  → SPIR-V、Vulkan 纹理、缓冲、管线与私有颜色/深度附件
  → 按原顺序绘制与后处理
  → Vulkan Swapchain → vkQueuePresentKHR
```

原生引导在类加载时将游戏中的 GL 入口调用改写为 CPU 状态/资源操作；这些操作由 Vulkan 后端执行。真实 LWJGL OpenGL 类的初始化和函数入口被改写为立即失败，并记录尝试，防止遗漏调用偷偷进入驱动。GLFW 窗口创建强制 `GLFW_NO_API`，绑定 OpenGL 上下文也会失败。Forge 的早期 GL 加载窗口在创建前禁用。

纹理直接来自 `NativeImage` 等 CPU 数据，顶点/索引来自游戏的 CPU 缓冲，不从 GL 下载资源。着色器使用 Minecraft 展开后的真实 GLSL，通过显式输入/输出位置、uniform 布局和深度范围变换编译为 SPIR-V。按优化后的 SPIR-V 输入接口建立顶点布局，避免未使用的属性类型冲突；管线缓存按实际布局与状态复用，不按区块 VBO 名称创建重复管线。

地形顶点与索引按逻辑缓冲 ID 和版本常驻 device-local Vulkan 缓冲，仅在数据更新后上传。更新版本与删除的旧资源在已有帧 fence 完成后释放，已排队绘制保留其原版本。常驻几何数据预算为 256 MiB，包含尚在途的旧版本，不含分配器对齐与块开销；预算不足时继续使用当帧上传路径，不丢弃绘制。动态几何仍按帧上传。每次提交中，相同程序与纹理的描述符复用，uniform 使用独立动态偏移；绘制顺序不变。

绘制保留深度、混合、镂空、剔除、视口与裁剪状态，私有 framebuffer 负责 GUI、世界和 Fabulous 后处理。内部附件保留游戏纹理坐标约定，最终采样通道统一调整显示方向。普通帧不做 CPU 像素读回；显式截图和世界缩略图通过 Vulkan staging buffer 读取。

`NativeGameBackend` 与游戏通过 JDK 类型/数组跨隔离加载器通信，使用自带 LWJGL 3.3.6；游戏仍使用 Forge 的 LWJGL。原生路径不进入 `MainWindowBackend`、`GlInterop` 或共享图像分配器。先前独立的 `NativeWindowBackend / NativeWorldRenderer` 仍用于底层无 GL 地形与 WSI 检查。

## 设备与同步

要求所选 Vulkan 1.2、1.3 或 1.4、`dynamicRendering`、`synchronization2`、Win32 Surface 与 Swapchain。Vulkan 1.2 使用对应 KHR 功能扩展。另外要求 `VK_EXT_surface_maintenance1`、`VK_KHR_get_surface_capabilities2`、`VK_EXT_swapchain_maintenance1` 及 `swapchainMaintenance1` 功能，用于安全回收显示资源。不要求 GL external-memory/external-semaphore；不支持必要功能时直接报错。

设备选择前创建实际窗口 Surface，以确认队列可呈现。交换链 acquire 等待和首次布局转换均在 `COLOR_ATTACHMENT_OUTPUT` 建立依赖；私有图像复用等待其最后采样提交完成。呈现完成 Semaphore 按交换链图像分配，WSI presentation fence 保证缩放/关闭前显示系统已释放资源。零尺寸跳过提交，缩放及 VSync 改变重建交换链，取消帧也消费已 acquire 的信号量。

## 验证

无需游戏的引导与真实 GPU 着色器检查：

```powershell
.\gradlew.bat smokeNativeBootstrap smokeNativeModeGate smokeRenderModeConfig
.\gradlew.bat :vulkan-lab:smokeNativeGeometry :vulkan-lab:smokeNativeShader
.\gradlew.bat :vulkan-lab:smokeNativeWindow
```

`smokeNativeBootstrap` 实际加载并调用改写后的测试类，验证保存配置、模式/API、GL 禁止入口和强制无 API 窗口；`smokeNativeShader` 检查优化后的输入类型、缺失输入拒绝和管线复用。独立窗口检查在禁止 GL/互操作类加载的隔离环境中逐像素验证地形与 Swapchain，范围标记为 `NATIVE_BACKEND_ONLY`。

`smokeNativeGeometry` 从实际 GPU 读回更新前后的缓冲版本，验证上传对齐、旧版本存活、延迟删除、预算压力与恢复；`smokeNativeShader` 也检查 std140 打包、相邻 draw 的 uniform 隔离和临时数组清零。

完整实际游戏检查会自动启动 Forge、创建隔离的测试世界，渲染不透明地形、树叶、水、玻璃、牛、箱子、营火粒子、手持物与 HUD；然后打开背包、缩放窗口、重载资源、切换 Fabulous、保存退出世界、回到主菜单并正常关闭：

```powershell
.\gradlew.bat -PnativeGame -PnativeGameSmoke runClient
# 按普通 run/config 中保存的 VK 模式检查启动与主菜单
.\gradlew.bat -PnativeGameSmoke -PnativeMenuOnly runClient
```

自动游戏检查仅在显式传入 `-PnativeGameSmoke` 时启用，不随模组发布。隔离测试会重置 `run-native/options.txt` 并创建测试存档。

已安装 Khronos 校验层时，可设置 `VK_LAYER_PATH` 指向其目录，设置 `VK_VALIDATION_VALIDATE_SYNC=1` 启用同步校验，再在命令中加 `-PvalidationSmoke` 强制要求层实际启用。`VALIDATION=UNAVAILABLE` 不代表通过校验层检查。

2026-10-27，在本机 RTX 3050 Laptop GPU 上，Vulkan 1.4 的完整游戏检查及普通保存配置启动检查均通过，使用了实际校验层与同步校验：`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`，并在游戏退出后确认 `CLOSED=true`。完整游戏检查记录了数千次原生地形与实体/物品绘制。独立原生窗口的 Vulkan 1.2、1.3、1.4 检查也已通过；这不等同于所有 API 的完整游戏覆盖。

游戏目录中的报告：

| 路径 | 内容 |
| --- | --- |
| `logs/vulkanforge/vk-native-compatibility.json` | 成功初始化时为 `NATIVE_ACTIVE`，更新旧的拒绝报告 |
| `logs/vulkanforge/native-game-report.txt` | 原生后端、校验层、实际呈现/绘制/上传次数和关闭结果 |
| `logs/vulkanforge/native-game-smoke/result.txt` | 显式自动检查的范围、GL 禁止入口结果、计数与成功/失败 |
| `logs/vulkanforge/native-game-smoke/*.png` | 从实际 Swapchain 捕获的菜单、世界、背包、缩放、重载和 Fabulous 图像 |

## 当前限制

Embeddium 0.3.31 的地形接入、原生引导要求与验证方法见 [Embeddium 纯 Vulkan 接入](embeddium-native-vulkan.md)。

实际分辨率筛选、静态地形重复上传与 CPU/GPU 分项的测量结果，见 [原生 VK 性能诊断](native-performance-diagnosis.md)。本轮常驻几何与提交优化的结果见 [原生 VK 优化对比](native-optimization-results.md)。当前兼容执行器仍保留原版逐 draw 提交结构。

- 这是实验性后端，仍保留原版 CPU 状态转换与逐 draw 提交；静态地形已常驻显存，动态几何仍按帧上传。纹理目前仅使用基础 mip，远处纹理过滤尚未达到原版完整效果。
- 当前验证范围为原版、Forge 与本模组；未验证任意第三方模组、自定义 GLSL、资源包和维度组合。
- 未支持的 stencil、logic-op、像素缓冲等操作或着色器类型会明确失败。原生路径不会用 OpenGL 绘制这些内容。
- 原生游戏目前仅支持 Windows x86_64。Vulkan 校验层是开发验证选项，正常启动不要求安装。
