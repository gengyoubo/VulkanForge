# GL-VK：GL 尾部兼容执行计划

实验开关：`-Dvulkanforge.glTailCompatibility=true`，默认关闭，重启生效。只适用于 Windows 的 GL_VK 模式，同 GPU 的外部内存/信号量互操作能力仍然必需。GL 模式保持原实现；VK 模式的完整游戏覆盖门禁不变。

## 第一阶段的实际分工

保留 Forge 创建的可见 OpenGL 窗口和原版最终 framebuffer blit / GLFW swap。Vulkan 后端仅用于同进程地形，不创建 Surface / Swapchain，不在 GUI 完成后回到 Vulkan 做最终呈现。

```text
GL 清除 / 天空及原有前置内容
    ↓
VK 一个地形提交：剔除 → 不透明 → 必要的透明深度初始化 → 半透明
    ↓ 一次 VK→GL 组交接：不透明与半透明的颜色 / 深度，共四张图
GL 不透明合成 → 原位置的实体 / 方块实体 / Forge 绘制
    → GL 半透明合成 → 粒子 / 天气 / 手持物 / 后处理 / HUD / 物品栏
    → 原版最终 blit → GL swap
    ↓ 帧末 GL→VK 归还输出图像，供帧槽下次复用
```

透明合成保留在实体之后；没有将 Forge 绘制搬到透明地形后面。所有非地形绘制在第一版中保留 GL，包括大量实体和粒子；后续只有在批量收益、阶段顺序和资源依赖都能证明时，才把连续批次接回 Vulkan。

主 RenderTarget 和后处理目标保持 GL 私有。图像在 GL 兼容阶段已经由 GL 持有，因此本计划中的后处理直接使用原版 GL，既不捕获 Shader，也不引入 GL→VK→GL 往返。手持物和 GUI 同样直接执行原版 GL 绘制，保留自定义 RenderType、Shader、物品图标与 Forge 回调的位置。

Fabulous 的透明目标/后处理保持 GL：现有接管合同无法对私有 GL 透明目标证明等价时，透明地形按原路径绘制。本版仍可能在 VK 地形批次内生成未消费的透明结果；不将 Fabulous 描述为完整的连续 Vulkan 快速路径。

## 与 Final Color 实验的区别

第一版导出两份地形的颜色与深度，而不是只导出最终颜色；还保留两次 GL 全屏合成和一次必要深度复制。这样可以在原有实体与透明阶段之间插入 GL 内容，且无需中途重新取得共享主目标的所有权。

先前 `FINAL_COLOR_GL` 的 1106 FPS 不能作为此计划的实测性能。它使用不同的资源图，省略了兼容中间阶段，且没有中途 Forge GL 绘制。本版没有新增性能提升承诺；默认保持现有交错模式。

稳定地形帧的预期实际计数：

| 指标 | GL 尾部计划 |
|---|---:|
| Vulkan 地形提交 | 1 / 帧 |
| GL semaphore 组调用 | 2 / 帧：一次等待、一次归还 |
| 组内图像引用 | 8 / 帧：四张图各两个方向 |
| opaque → translucent 深度复制 | 1 / 帧 |
| 为 Vulkan 实体准备的 sceneDepth 复制 | 0 |
| 主 / 后处理目标的共享图像 | 0 |
| Vulkan 合成、实体 overlay、手持物、HUD、后处理提交 | 0 |
| Vulkan Surface / Swapchain / Present | 0 |

共享输出帧槽复用仍有必要的 Vulkan fence 和 GL→VK 外部 semaphore；不删除真实依赖，不新增逐帧 `glFinish`。尺寸重建、资源销毁和关闭保留必要的等待。没有采用前一轮未显示明确吞吐收益的提前输入 flush。

## 使用与验证

```powershell
# 手动启动实验计划；不修改 TOML 默认模式
.\gradlew.bat '-Dvulkanforge.glTailCompatibility=true' runClient

# 真实 GPU：GL 插入前后深度遮挡、两帧槽、缩放、实际计数和拒绝 Vulkan 再进入
.\gradlew.bat --no-parallel :vulkan-lab:smokeGlTail :vulkan-lab:smokeNativePostBatch build

# 完整 Forge：临时世界、地形/水/玻璃/牛/箱子、原版对照、手持物与物品栏、缩放/全屏、F8、重载与 Fabulous
.\gradlew.bat --no-parallel -PglTailSmoke runClient
```

`glTailCompatibility` 显式选择此计划，优先于旧的 `directPresent=false` 呈现选项。初始化失败仍回到原 GL 窗口，测试会明确检查实际启用的计划，不把失败回退算成 Vulkan 测试通过。

报告：

- `build/reports/gl-tail-smoke/`：独立 GPU 功能和计数检查。
- `run/logs/vulkanforge/gl-tail-smoke/`：完整游戏报告与诊断截图；世界颜色/深度在手持物与 HUD 清深度之前比较。
- `run/logs/vulkanforge/gl-tail-report.txt`：实际 GL swap 帧数、计划和关闭状态。
- `run/logs/vulkanforge/main-window-report.txt`：`HYBRID_EXECUTION_PLAN=GL_TAIL`、地形计时/交接、资源关闭，以及 `FINAL_PRESENTED_FRAMES=0`（Vulkan Present 为零）。

完整游戏的 GL swap 帧数通过 `NativeMainWindow.presentedFrames()` 返回；它不再等同于 Vulkan swapchain 帧数，调用者可用 `glTailCompatibility()` 区分。Vulkan acquire / swapchain generation 指标在该计划下均为零。诊断截图读回仅发生在显式请求时，不属于正常输出路径。

后续阶段以实际资源读写清单建立连续 Vulkan 区间、私有中间目标和成本记录。调度器不能仅依据“Shader 支持 Vulkan”或 Draw 数量选择后端，也不能把 Gate span 当成固定切换成本。

## 2026-10-27 首轮功能结果

RTX 3050 Laptop：独立 GPU 检查通过，验证 GL 插入内容在半透明地形前后两种深度关系、两个帧槽、尺寸重建、一次地形提交、一次双向归还周期和拒绝 Vulkan 再进入；保留一次透明深度复制，省去实体 sceneDepth 复制和 overlay 共享图像。

完整 Forge GL 尾部检查通过，实际执行 24637 次 GL swap，Vulkan Present 为零且正常关闭。稳定区间 864 个地形帧逐帧平均计数严格为一次 VK 提交和两次 GL 组调用。主目标/后处理目标没有共享导出，实体/天空/手持物/HUD 没有进入 Vulkan 捕获或回放。F8 关/开、物品栏、窗口缩放/全屏、资源重载和 Fabulous 的 GL 兼容路径均通过。

世界在手持物/HUD 清除深度之前与原版 GL 对照：RGB 平均绝对差 1.820 / 255，任一 RGB 通道差超过 48 的像素比例 1.610%，深度差超过 2e-4 的比例 1.620%。这不是逐位一致，也不是所有模组的兼容性证明；测试报告保留真实误差。独立 GPU 的前后遮挡测试另用明确预期颜色和深度断言。

GL 尾部完整游戏的最终后端与呈现报告归档在 `run/logs/vulkanforge/gl-tail-smoke/backend.txt` 和 `presentation.txt`，避免下一次启动覆盖证据。普通 `main-window-report.txt` 是最近一次启动的报告。

本轮是功能与交接计数验收，未进行 GL 尾部与默认混合模式的同场景配对 FPS / P99 测量，保持默认关闭。验证层报告不可用，未声称通过 Vulkan 同步验证。
