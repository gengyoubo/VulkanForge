# GL-VK：GL 尾部兼容执行计划

GL-VK 现已默认使用 GL 尾部兼容计划。游戏 Mods → VulkanForge → 配置中的“GL-VK 执行计划”可选择 GL 尾部兼容或原交错模式，保存后重启生效。对应配置：

```toml
[renderer]
mode = "GL_VK"
glVkPlan = "GL_TAIL"
```

旧配置缺少 `glVkPlan` 时迁移为 `GL_TAIL`；显式保存的 `INTERLEAVED` 会保留。只适用于 Windows 的 GL_VK 模式，同 GPU 的外部内存/信号量互操作能力仍然必需。GL 模式忽略此计划；VK 模式的完整游戏覆盖门禁不变。诊断开关与执行计划独立，正常启动不启用消融或新增查询。

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

先前 `FINAL_COLOR_GL` 的 1106 FPS 不能作为此计划的实测性能。它使用不同的资源图，省略了兼容中间阶段，且没有中途 Forge GL 绘制。原交错模式仍可通过 `glVkPlan = "INTERLEAVED"` 选择。

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

表中的提交计数统计地形执行器；网格、图集和光照更新仍可通过现有上传批次独立提交，需另读 `ASYNC_UPLOADS` / 上传统计，不能把“一次地形提交”描述为动态游戏整帧只有一次 Vulkan QueueSubmit。

## 使用与验证

```powershell
# 普通启动读取保存的 GL_VK / GL_TAIL 配置，无需特殊 JVM 参数
.\gradlew.bat runClient

# 真实 GPU：GL 插入前后深度遮挡、两帧槽、缩放、实际计数和拒绝 Vulkan 再进入
.\gradlew.bat --no-parallel :vulkan-lab:smokeGlTail :vulkan-lab:smokeNativePostBatch build

# 完整 Forge：临时世界、地形/水/玻璃/牛/箱子、原版对照、手持物与物品栏、缩放/全屏、F8、重载与 Fabulous
.\gradlew.bat --no-parallel -PglTailSmoke runClient
# 验证完全由保存的配置选择计划，不提供 JVM 渲染器/计划覆盖
.\gradlew.bat --no-parallel -PglTailSmoke -PglTailFromConfig runClient
# 配置菜单保存往返与无覆盖启动
.\gradlew.bat --no-parallel -PconfigSmoke runClient
```

旧 JVM 开关 `-Dvulkanforge.glTailCompatibility=true/false` 继续作为单次启动覆盖，优先于 TOML，且不改写保存值。启动快照通过布尔参数把选中计划传给隔离后端及地形执行器，不使用写入全局 JVM 属性来同步配置。GL_TAIL 本身使用 GL 呈现；`directPresent=false` 只在原交错计划中关闭 Vulkan 主窗口。初始化失败仍回到原 GL 窗口，测试会明确检查实际启用的计划，不把失败回退算成 Vulkan 测试通过。

报告：

- `build/reports/gl-tail-smoke/`：独立 GPU 功能和计数检查。
- `run/logs/vulkanforge/gl-tail-smoke/`：完整游戏报告与诊断截图；世界颜色/深度在手持物与 HUD 清深度之前比较。
- `run/logs/vulkanforge/gl-tail-report.txt`：实际 GL swap 帧数、计划和关闭状态。
- `run/logs/vulkanforge/main-window-report.txt`：`HYBRID_EXECUTION_PLAN=GL_TAIL`、地形计时/交接、资源关闭，以及 `FINAL_PRESENTED_FRAMES=0`（Vulkan Present 为零）。

完整游戏的 GL swap 帧数通过 `NativeMainWindow.presentedFrames()` 返回；它不再等同于 Vulkan swapchain 帧数，调用者可用 `glTailCompatibility()` 区分。Vulkan acquire / swapchain generation 指标在该计划下均为零。诊断截图读回仅发生在显式请求时，不属于正常输出路径。

后续阶段以实际资源读写清单建立连续 Vulkan 区间、私有中间目标和成本记录。调度器不能仅依据“Shader 支持 Vulkan”或 Draw 数量选择后端，也不能把 Gate span 当成固定切换成本。

## 2026-10-27 首轮功能结果

RTX 3050 Laptop：Vulkan 1.2 / 1.3 / 1.4 独立 GPU 检查通过，验证 GL 插入内容在半透明地形前后两种深度关系、两个帧槽、尺寸重建、一次地形提交、一次双向归还周期和拒绝 Vulkan 再进入；保留一次透明深度复制，省去实体 sceneDepth 复制和 overlay 共享图像。API 版本报告分别在 `build/reports/gl-tail-smoke-vk12/`、`gl-tail-smoke-vk13/` 和 `gl-tail-smoke-vk14/`。

完整 Forge GL 尾部最终复测通过，实际执行 23487 次 GL swap，Vulkan Present 为零且正常关闭。稳定区间 788 个地形帧逐帧平均计数严格为一次 VK 提交和两次 GL 组调用。主目标/后处理目标没有共享导出，实体/天空/手持物/HUD 没有进入 Vulkan 捕获或回放。F8 关/开、物品栏、窗口缩放/全屏、资源重载和 Fabulous 的 GL 兼容路径均通过。

世界在手持物/HUD 清除深度之前与原版 GL 对照：RGB 平均绝对差 1.799 / 255，任一 RGB 通道差超过 48 的像素比例 1.610%，深度差超过 2e-4 的比例 1.620%。这不是逐位一致，也不是所有模组的兼容性证明；测试报告保留真实误差。独立 GPU 的前后遮挡测试另用明确预期颜色和深度断言。

GL 尾部完整游戏的最终后端与呈现报告归档在 `run/logs/vulkanforge/gl-tail-smoke/backend.txt` 和 `presentation.txt`，避免下一次启动覆盖证据。普通 `main-window-report.txt` 是最近一次启动的报告。

以上是初版功能与交接计数验收。后续真实游戏消融和计时探针对照见 [游戏消融记录](tail-game-ablation.md)；配置接入后 GL_TAIL 成为 GL-VK 默认计划。验证层报告不可用，未声称通过 Vulkan 同步验证。

原默认交错模式另以 Vulkan 1.3 跑完整 `worldSmoke` 回归，通过实体/粒子/天气/手持物/HUD、GL 回退顺序、Fabulous、资源重载、缩放/最小化恢复/全屏和世界切换。报告保存在 `build/reports/gl-tail-default-regression/`，没有用 GL 尾部模式替代该回归。

## 配置接入后的复测

游戏配置已接入 `renderer.glVkPlan`，当前开发游戏目录保存 `mode = "GL_VK"`、`glVkPlan = "GL_TAIL"`。配置页可保存两种执行计划，旧配置迁移、CLI 临时覆盖、模式/版本切换与扩展保留均通过检查；显式计划通过基本类型参数传入后端和地形执行器，不依赖 JVM 属性同步。

`-PglTailSmoke -PglTailFromConfig runClient` 无 JVM 渲染器/计划覆盖启动成功，完整功能回归通过。稳定区间 918 个地形帧均为每帧一次地形提交、两次 GL 组调用；RGB 平均绝对差为 1.797 / 255，深度差超过 2e-4 的像素比例为 1.620%，没有声称零误差。手持物/HUD/物品栏、窗口缩放/全屏、F8 重开、资源重载与 Fabulous 回退通过，最终 25800 次 GL swap、Vulkan Present 为零，资源正常关闭。

正常 A 的性能复测命令：

```powershell
python tools/benchmark-tail-game.py --java-home '<JDK17目录>' --rounds 3 --seconds 5 --warmup 3 --cases A --phases BASE_GAME --cpu-only --from-config --output build/reports/gl-tail-config-retest
```

1920×1080 无边框、视距 8、Fancy、云关闭、VSync 关闭、取消帧率上限；保留 HUD/手持物。关闭新增 GL 时间戳查询，保留 CPU 分段和原有 VK 时间戳。没有 B/C/D 省略或替换，总计 9179 个测量帧。

| 组 | FPS | 帧周期 CPU P99 ms |
|---|---:|---:|
| 1 | 584.10 | 4.2154 |
| 2 | 606.85 | 3.7719 |
| 3 | 627.03 | 3.0811 |

FPS 中位数 606.85。三个样本均验证从配置选择计划、每帧一次地形提交与两次 GL 组调用；省略地形/跳过可选后处理均为零。完整原始帧、参数、温度/频率、源码/class 归档及报告在 `build/reports/gl-tail-config-retest/`，已知 Java GPU 工作负载并发检测为空。不是用户原有世界的同场景重放，不将与此前样本的差异直接归因于本次配置接入。验证层仍不可用。
