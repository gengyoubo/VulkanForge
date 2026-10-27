# 原生 Vulkan 游戏渲染的当前状态

目标是整个 Minecraft/Forge 游戏进程不创建 OpenGL 上下文、不调用 OpenGL，用 Vulkan 绘制世界并经 Vulkan Swapchain 呈现。

**尚未实现完整游戏目标。** 本次完成的是独立的无 GL 地形/窗口后端，Minecraft 的 `VK` 游戏启动仍由覆盖检查拒绝。`GL_VK / INTERLEAVED` 虽然通过 Vulkan 呈现，仍有隐藏 GL 上下文，不能作为此目标的完成结果。没有修改默认模式或解除该检查。

## 已实现的原生窗口后端

`NativeWindowBackend` 提供只包含 JDK 类型的调用边界：构造时传入调用方拥有的 Win32 `HWND/HINSTANCE`、尺寸和 Vulkan 版本；用 `WorldProtocol.pack` 提交 CPU 网格、图集、光照图和相机；用 `beginFrame` 在输入采样前 acquire，再用 `frame` 绘制和呈现。调用者负责窗口、事件循环和输入；后端不使用 GLFW。

路径为：

```text
CPU 网格/纹理/相机
  → NativeWorldRenderer / VulkanSceneCore
  → 私有 Vulkan 颜色和深度图像（不透明、镂空、排序透明、可选后处理）
  → Vulkan 全屏采样通道
  → Vulkan Swapchain / vkQueuePresentKHR
```

设备选择前创建 Win32 Surface，以便选择实际支持该窗口呈现的队列。此路径不调用 GL UUID 探测，不启用 Win32 external-memory/external-semaphore 扩展，不创建共享图像，不导出图像句柄，不复制到 GL framebuffer。普通帧无 CPU 像素读回；只有显式的测试截图使用 staging buffer。

呈现有两个帧槽。复用地形颜色图像前等待对应的**最终采样提交 fence**，覆盖最后一次采样读取；仅等待地形 fence 不够。呈现完成 Semaphore 按 Swapchain 图像分配，在重新 acquire 同一图像后复用。地形与呈现使用同一条 Vulkan queue，通过图像 barrier 建立写入到采样的依赖。

另为每张 Swapchain 图像维护 `VK_EXT_swapchain_maintenance1` 的 **WSI presentation fence**，缩放和关闭先等待它们，再销毁呈现 Semaphore 和 Swapchain。GPU 提交 fence、`vkDeviceWaitIdle` 都不能单独证明显示系统已释放这些资源，见 [Khronos 的呈现 Semaphore 生命周期说明](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)。此后端因此额外要求 `VK_EXT_surface_maintenance1`、`VK_KHR_get_surface_capabilities2`、`VK_EXT_swapchain_maintenance1` 及 `swapchainMaintenance1` 功能；不支持时明确拒绝，既有混合/离屏设备路径不增加此要求。

缩放重建 Swapchain 并只替换私有附件，保留世界网格、图集和相机。零尺寸不提交帧。取消已 acquire 帧、着色器录制失败和持有已 acquire 图像时关闭，都消费 acquire Semaphore 并归还图像。VSync 修改于下一次 acquire 重建 Swapchain。

原生图像均为 Vulkan 顶部原点，最终采样不使用 GL 路径的 Y 翻转。优先选择 UNORM Swapchain；在仅有 sRGB 格式时，最终着色器先解码显示颜色，避免附件再次编码造成颜色变亮。截图支持 RGBA/BGRA 两种 UNORM/sRGB 格式。

## 仍未迁移的游戏入口

| 入口 | 当前依赖与剩余工作 |
| --- | --- |
| Forge `ImmediateWindowHandler.load` | 模组窗口钩子运行前即可创建加载用 GL 上下文，需要无 GL 的早期窗口/启动方案。仅在 Minecraft 窗口创建时换成 `GLFW_NO_API` 不足以满足整个进程无 GL。 |
| Minecraft `Window`、`GLX`、`RenderSystem` 初始化 | 仍创建 GL capabilities、初始化 GL 调试和默认状态，需要原生窗口及设备入口。 |
| `TextureUtil`、`NativeImage`、`CpuTextures` | 现有 CPU 上传追踪器仍执行 GL 上传和查询。需原生纹理对象、CPU 上传及生命周期管理，不能用 GL 读回启动原生模式。 |
| `VertexBuffer`、`TerrainMeshes` | 原版网格捕获仍关联 GL VBO 的创建/状态，回退路径仍可上传 GL。需直接发布编译完成的 CPU 网格及原生资源生命周期。 |
| 实体、天空、粒子、手持物、GUI 与后处理 | 现有回放依赖 GL 状态、纹理镜像或共享 RenderTarget；需原生状态、附件与完整有序绘制。不能用空实现或漏画绕过。 |
| 游戏主循环与资源重载 | `NativeWindowBackend` 尚未接入 Minecraft。需覆盖主菜单、进出世界、重载、切换维度、窗口事件和正常退出的真实游戏检查。 |

覆盖报告将后端标记为 `IMPLEMENTED_NOT_CONNECTED`，剩余项包含 `NATIVE_GAME_RENDER_LOOP` 和 `MINECRAFT_RESOURCES_WITHOUT_GL`。该状态不意味着 `VK` 可游玩。

## 验证

JDK 17，Windows x86_64：

```powershell
.\gradlew.bat :vulkan-lab:smokeNativeWindow
.\gradlew.bat :vulkan-lab:smokeNativeWindow '-PvulkanVersion=1.2' '-Pvulkanforge.smokeReportDir=build/reports/native-window-1.2'
.\gradlew.bat :vulkan-lab:smokeNativeWindow '-PvulkanVersion=1.3' '-Pvulkanforge.smokeReportDir=build/reports/native-window-1.3'
.\gradlew.bat smokeNativeModeGate smokeRenderModeConfig
```

`smokeNativeWindow` 在禁止加载 `org.lwjgl.opengl.*` 和 `GlInterop` 的隔离 ClassLoader 中运行，只创建 `GLFW_NO_API` 测试窗口，并检查不存在当前 OpenGL 上下文。它逐像素检查实际 Swapchain 截图中的地形、透明混合、镂空、方向、颜色、纹理补丁、后处理奇偶输出、缩放、删除/重置和重新打开；另检查零尺寸、VSync、帧取消、错误恢复、持有 acquire 时关闭。此测试不启动 Minecraft，结果明确写入 `SCOPE=NATIVE_BACKEND_ONLY`。

结果与截图保存在 `build/reports/native-window/` 或指定目录。`VALIDATION=UNAVAILABLE` 时，只能说明 GPU 像素及生命周期检查通过，不能声称通过了 Vulkan 校验层检查。已配置校验层的环境可加 `-PvalidationSmoke` 强制要求实际启用。

2026-10-27，本机 RTX 3050 Laptop GPU 上的 Vulkan 1.2、1.3、1.4 原生窗口检查均通过，包含 presentation fence 的关闭/重建路径；测试进程的 GL/互操作类加载尝试为零。`smokePresent` 既有混合呈现回归、`smokeNativeModeGate`、`smokeRenderModeConfig` 以及完整 `build` 通过。该环境未启用 Vulkan 校验层。上述结果均不构成完整 Minecraft 无 GL 游戏验证。
