# GL Tail 真实游戏消融与分段计时

这是诊断功能，默认关闭。A/B/C/D 使用实际 Forge 1.20.1 渲染循环，不从离屏地形 FPS 推算游戏 FPS。

| 例 | 实际变化 | 解释边界 |
|---|---|---|
| A | 正常 GL Tail 地形、GL 兼容绘制及输出 | 同组基准 |
| B | 不录制 VK opaque / translucent Draw；保留剔除、附件清除、深度复制、GL 合成和共享图像交接 | 画面和负载改变，是省略绘制的诊断下界；没有隔离出纯交接成本 |
| C | 仅跳过 GameRenderer 可选屏幕效果调用中的原版 desaturate 链 | 不跳过 Fabulous 透明、轮廓等必要链，不跳过未知模组链；没有可选效果时跳过次数应为零 |
| D | 关闭 Vulkan 地形接管，执行普通原版 GL 地形 | GL Tail 的 Vulkan 设备仍初始化但不提交地形；不是完全没有 Vulkan 设备的纯 GL 启动，也不是自定义 GL46 GPU Driven 模式 |

后处理控制分两组：`BASE_GAME` 不加载可选效果；`OPTIONAL_POST` 显式加载原版 `minecraft:shaders/post/desaturate.json`（color_convolve → blit，两次全屏 Pass）。第二组的 C 验证这条效果的成本，不代表真实游戏中任意后处理都能省略。

## 自动配对测试

```powershell
python tools/benchmark-tail-game.py --java-home '<JDK17目录>' --rounds 3 --seconds 5 --warmup 3 --output build/reports/tail-game-ablation

# 分析已保存的数据，不重新启动游戏
python tools/benchmark-tail-game.py --summarize-only --output build/reports/tail-game-ablation
```

脚本先编译并检查实际 Minecraft 字节码，再归档源码/class、启动参数及 SHA256；随后启动真实游戏，创建固定种子平坦测试世界，固定相机和静态场景，1920×1080 无边框、视距 8、Fancy、云关闭、保留 HUD/手持物。VSync 关闭，FPS 限制设置为 Minecraft 的 260（该值不调用帧率限制器）。每轮打乱 A/B/C/D 顺序，每例先预热再计时。两组各三轮，默认共 24 个正式样本。

这是新建的受控真实游戏场景，不能描述为用户原有约 250 FPS 现场的重放。A/D 是实际渲染实现对照，未统一两者的 Shader、顶点格式、剔除实现或实际 Draw ID，不能称为纯 API 效率比较。

截图只在预热开始时诊断，正式计时不读回图像、不排空 GPU。B 的地形 Draw 为零、D 的 Vulkan 地形提交为零且 GL 地形 Draw 非零、C 的执行/跳过次数均逐例断言。记录相机、尺寸、窗口焦点、实际地形/交接/上传计数；资源上传提交不冒充地形提交。不丢弃慢帧。

报告包含 `summary.md/json`、`runs.json`、随机顺序、逐例 `frames.csv/profile.txt/result.json/screen.png`、`scene.txt`、GL 上下文、源码/class 归档、GPU 整卡温度/频率/占用采样、已知 Java GPU 工作负载并发检测和最终资源关闭报告。运行目录必须是新的，避免覆盖既有正式样本。

## 手动现场诊断

```powershell
.\gradlew.bat '-Dvulkanforge.glTailCompatibility=true' '-Dvulkanforge.tailProfile=true' '-Dvulkanforge.glTailDiagnostics=true' '-Dvulkanforge.glTailCase=A' '-Dvulkanforge.interopProfile=true' runClient
```

将 A 改成 B/C/D 可选择对应诊断；D 会让地形保持原版 GL。手动 C 仍只允许省略明确列出的 desaturate 可选链，其他效果不会被误跳过。诊断开关不应作为常规性能优化长期启用。

手动 profile 从进入世界时开始，正常退出时写入 `run/logs/vulkanforge/tail-profile/`；会包含进入世界的加载帧。受控测试由控制器在预热之后单独开始/停止。最多保留 131072 个原始 CPU 帧，超出会明确记录 `cpu_overflow`，不能把截断数据当成完整长时采样。

## CPU / GPU 计时定义

- `cpu_game_render`：GameRenderer.render 的入口到正常/异常出口。
- `cpu_native_render_call`：宿主进入后端的调用，含反射边界；`cpu_shared_render_mean_ms` 是 SharedWorldRenderer.render 本身的累计墙钟时间差值。
- `cpu_gl_opaque_composite` / `cpu_gl_translucent_composite`：TerrainPresenter 对应合成入口到出口。
- `cpu_gl_post_chain`：实际 PostChain.process 的累计调用时间，含所有真实执行的链；C 在调用点省略的可选链不伪造一次已执行 Pass。
- `cpu_swap_buffers`：NativeMainWindow.swapBuffers，包含帧末租约归还、信号/flush 和 GLFW swap。
- `cpu_render_to_swap`：GameRenderer 入口到 swap 返回；`cpu_frame_period` 是连续入口间隔，还包含两次渲染之间的 Minecraft 其他 CPU 工作。所有 CPU 区间都可能包含驱动或 GPU 等待，不能直接称为 CPU 计算量。

GL GPU 端使用 8 个查询槽和每槽最多 32 个事件的时间戳。每帧先轮询旧槽的 `GL_QUERY_RESULT_AVAILABLE`，只有已完成帧才读取结果；不使用同步 query 读取、不增加 `glFinish` / fence wait / flush。槽忙或事件超限时保留 CPU 帧、明确记录 GPU 丢弃；测量结束不等待最后几个 GPU 帧，原始 CSV 用 `NaN` 标记不可用。

`gl_span_*` 是 GL 时钟的可见跨度；跨 API 依赖、CPU 命令供给与驱动调度也可能包含在其中。特别是 Vulkan 调用周围的 GL span 不能当作 Vulkan 绘制本体时间，swap 周围的 span 也不是屏幕扫描或输入延迟。嵌套的 GameRenderer / 合成 / Post / 帧跨度不相加。

Vulkan opaque、translucent、剔除、深度复制来自自己的 GPU 时间戳。新增 `phaseProfileSnapshot()` 只轮询已完成 fence 并读取已完成查询，保持帧槽原有等待与生命周期；没有为采样调用原来会 `waitAll()` 的 `phaseProfile()`。快照差值可能包含少量同配置预热边界帧，报告完成样本数，不伪装为与每个 GL 帧精确配对。

显式单列 Vulkan fence wait、command record、queue submit，以及 GL wait/signal/flush 的 CPU 调用耗时。这些数据可以确认已测等待点是否占主要 CPU 墙钟；未被这些 API 覆盖的驱动阻塞不能据此认定为纯 CPU 计算，更不能把 GL/VK 两个时钟相减推导纯 semaphore 延迟或 GPU idle。

## 回归

```powershell
.\gradlew.bat --no-parallel smokeTailDiagnosticsTransform smokePostChainTransform :vulkan-lab:smokeGlTail build
```

ASM 检查对实际 GameRenderer 和 PostChain 字节码进行 JVM 栈/帧验证，并覆盖计时转换与已有 PostChain 转换的两种顺序。正式计时和像素诊断不等同于通过 Vulkan 同步验证层；状态以最终后端报告为准。
