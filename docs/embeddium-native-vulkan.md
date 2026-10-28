# Embeddium 地形接入纯 Vulkan

接入目标为 Minecraft 1.20.1 / Forge 47.4.28、Embeddium `0.3.31+mc1.20.1`（CurseForge 文件 5681725）与 VulkanForge 的 `VK` 模式。保留 Embeddium 的区块构建、压缩顶点格式、区域 arena、可见性及透明索引排序，由 VulkanForge 记录 Vulkan 地形绘制，并通过 Vulkan Swapchain 呈现。

```text
Embeddium RenderSectionManager / ChunkBuilder / RenderRegionManager
  → CPU arena 版本与区域多重绘制批次
  → Embeddium 原始 GLSL → SPIR-V、常驻 Vulkan 网格
  → vkCmdDrawIndexed（保留 index offset、baseVertex 和批次顺序）
  → Vulkan Swapchain / vkQueuePresentKHR
```

## 使用

保留正常的 Embeddium 模组安装，选择 `VK` 并重启。原生模式要求本轮重新构建的主模组与 `vulkanforge-native-bootstrap.jar`；只替换主模组、仍使用旧引导 jar 不会获得全部兼容改写。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
# 或使用独立测试目录直接启动 VK
.\gradlew.bat -PnativeGame runClient
```

开发依赖默认包含当前选定的 Embeddium 版本，`-PwithoutEmbeddium` 可检查未安装 Embeddium 的原生路径。发布模组没有引入对 Embeddium Java API 的硬引用；Embeddium 无需打包进主模组。此接入不等于已验证 GL、GL46、GL_VK 或 Oculus 的组合。

## 修复的冲突

开发启动中的 `m_85704_`、`m_91383_` 目标缺失来自 SRG refmap 与官方映射环境不一致。`runClient` 现在启用 refmap 重映射，使用 ForgeGradle 的 `createSrgToMcp/output.srg`。这两个开发 JVM 参数无需加入普通发布启动器。

原生引导增加了 Embeddium 包内实际 GL 调用点的 CPU 转换。Embeddium 的世界渲染及 CPU 优化 mixin 保留，依赖 WGL 上下文的 `workarounds` mixin 在 VK 下过滤。其插件动态提供的 mixin 列表也在原选择器中处理，避免只修改 `shouldApplyMixin` 无效。

GL BufferStorage 与持久映射 staging 路径不适用于无 GL 上下文的设备。原生模式同时关闭对应能力选择和 `MappedStagingBuffer.isSupported`，让 Embeddium 选择普通 staging 接口；不修改用户保存的 Embeddium 选项。真正的 device-local 上传、在途资源与 fence 由 Vulkan 后端管理。

适配器支持区域缓冲复制、普通范围映射及显式刷新，正确保留源/目标绑定和子区间偏移。已经被绘制快照引用的 arena 在再次写入时进行写时复制，避免排队中的绘制读到后来更新的数据。CPU staging fence 表示同步 CPU 拷贝已完成；它不代替 Vulkan 队列与网格版本的 GPU fence。

区域多重绘制完整保留各命令的索引字节偏移及 `baseVertex`。当前将批次成员依次记录为 Vulkan 索引绘制，还没有改成 Vulkan MDI。未变化的区域几何沿用常驻缓存；更新版本目前仍上传整个对应 arena 缓冲，尚未实现 GPU 子区间更新。

着色器保留 Embeddium 原始压缩顶点、材质、镂空、纹理、光照与雾逻辑。Vulkan 输入类型严格匹配：压缩光照的 unsigned16 先作为 `uvec2` 取值，再转为原 shader 使用的 int32，避免有符号短整数重解释。转换放在原条件编译分支内，非压缩的 `uint` 分支保持独立。显式长度的 shader 源码和 float 数组/scalar vector uniform 也纳入转换。

## 检查

```powershell
.\gradlew.bat smokeNativeBootstrap smokeNativeBuffers
.\gradlew.bat :vulkan-lab:smokeNativeGeometry :vulkan-lab:smokeNativeShader -PvalidationSmoke
.\gradlew.bat -PnativeGame -PnativeGameSmoke -PnativeSmokeHidden -PnativeDiagnostics -PvalidationSmoke runClient
.\gradlew.bat -PwithoutEmbeddium -PnativeGame -PnativeGameSmoke -PnativeSmokeHidden -PvalidationSmoke runClient
```

GPU 检查需先将 `VK_LAYER_PATH` 指向已安装的 Khronos 校验层，并设置 `VK_VALIDATION_VALIDATE_SYNC=1`。隐藏窗口只用于功能检查，不用于 FPS 测量。

实际世界检查除菜单、世界、背包、缩放、资源重载、Fabulous、退出及关闭外，还断言：

- 实际渲染器为 `SodiumWorldRenderer` 且有可见区块。
- 适配器收到 Embeddium 多重绘制批次。
- Vulkan 后端记录了 Embeddium 地形 shader 的真实绘制。
- 窗口为 `GLFW_NO_API`，没有 OpenGL 上下文或禁止入口调用，Vulkan 校验错误为零。

证据在 `logs/vulkanforge/native-game-smoke/embeddium.txt` 和 `native-game-report.txt`；后端计数名为 `embeddiumTerrainDraws`，适配器计数名为 `embeddiumMultiDrawBatches`。截图来自实际 Swapchain。上述功能检查不代表性能提升，不替代完整资源包、维度、第三方 shader 或所有 Embeddium 扩展的覆盖。

2026-10-29 在 RTX 3050 Laptop GPU 上，启用 Khronos 同步校验的完整世界检查通过。世界取证时实际渲染器为 `SodiumWorldRenderer`，可见 section 为 4，收到 2376 个多重绘制批次、记录 9470 次 Embeddium Vulkan 地形绘制；这些是该取证时刻的累计值。关闭时共呈现 1527 帧，记录 60220 次原生绘制，`VALIDATION_ERRORS=0`、`FORBIDDEN_GL_CALLS=0`、`OPENGL_CONTEXTS=0`、`CLOSED=true`。证据及世界、背包、缩放、重载、Fabulous 截图归档在 `build/reports/embeddium-native-20261029/with-embeddium/`。
