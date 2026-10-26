# 渲染参考源码与 VulkanForge 对照

核对日期：2026-10-27。这里记录已读源码、适用版本和本项目的实施判断，避免把其他项目的功能描述当成本项目已经实现的功能。源码链接固定到本次读取的提交。

## 版本选择

| 项目 | 读取的分支 / 提交 | 版本核对与用途 |
| --- | --- | --- |
| ImmediatelyFast | `1.20` / `ba8d2f46978e7f2ea9ea19b2af1843536ddda28c` | gradle.properties 的构建目标是 1.20.4，声明支持范围包含 1.20.1；参考合批设计，映射和注入点需按 Forge 1.20.1 重新核对 |
| Embeddium | `20.1/forge` / `dee0ebde6aea8b158613cd3b8ea5cdf0c1f6a9bf` | Minecraft 1.20.1、Forge 47.3.0、Embeddium 0.3.31 |
| Flywheel | `1.20.1/0.6` / `7ec4a460fb8416c32abe04da6efd8f0965835201` | Minecraft 1.20.1、Forge 47.1.3、Flywheel 0.6.11 |
| Nvidium | `dev` / `f2028b2ba9a5dc95d69e73a9eaeadcb447367d7d` | 构建目标是 1.21；本次仅参考 GPU 数据组织与生命周期，不作为 1.20.1 可直接移植的实现 |
| Iris | `1.20.1` / `f8d7bea2ab82d21737e7aa8e4b2fb3a429078106` | 参考渲染阶段和 Shader 兼容边界；此处没有验证 Oculus 或 Shader Pack 与 VulkanForge 的兼容性 |

版本依据：[ImmediatelyFast](https://github.com/RaphiMC/ImmediatelyFast/blob/ba8d2f46978e7f2ea9ea19b2af1843536ddda28c/gradle.properties)、[Embeddium](https://github.com/FiniteReality/embeddium/blob/dee0ebde6aea8b158613cd3b8ea5cdf0c1f6a9bf/gradle.properties)、[Flywheel](https://github.com/Engine-Room/Flywheel/blob/7ec4a460fb8416c32abe04da6efd8f0965835201/gradle.properties)、[Nvidium](https://github.com/MCRcortex/nvidium/blob/f2028b2ba9a5dc95d69e73a9eaeadcb447367d7d/gradle.properties)。

## ImmediatelyFast：先确定合批边界

`BatchableBufferSource` 使用分层缓冲和稳定顺序，并为村民皮肤叠层、马匹装饰、文字等处理特殊顺序。`BatchingBuffers` 则按 HUD、物品模型、物品覆盖层划定窗口，提供强制刷新；`BufferBuilderPool` 复用构建器并回收闲置资源。这说明“非混合”本身不足以证明任意绘制都可以重排。

源码：[BatchableBufferSource](https://github.com/RaphiMC/ImmediatelyFast/blob/ba8d2f46978e7f2ea9ea19b2af1843536ddda28c/common/src/main/java/net/raphimc/immediatelyfast/feature/core/BatchableBufferSource.java)、[BatchingBuffers](https://github.com/RaphiMC/ImmediatelyFast/blob/ba8d2f46978e7f2ea9ea19b2af1843536ddda28c/common/src/main/java/net/raphimc/immediatelyfast/feature/batching/BatchingBuffers.java)、[BufferBuilderPool](https://github.com/RaphiMC/ImmediatelyFast/blob/ba8d2f46978e7f2ea9ea19b2af1843536ddda28c/common/src/main/java/net/raphimc/immediatelyfast/feature/core/BufferBuilderPool.java)。

对应本项目：`Gl46Entities` 的 fallback、输出目标改变、文字/Glint 等不支持绘制都是边界。此次发现并修复 `split()` 仅检查普通 `pending` 的缺口：只有缓存方块实体实例时也必须先刷新，再交给原版绘制，否则缓存绘制会跨越回退边界。

验证：`smokeEntityBatchBoundary` 在隐藏 FBO 中植入一个真实驻留缓存网格，再通过 `take()` 送入不支持的绘制。旧实现得到 `flushes=0, splits=0`，回归失败；修复后要求先出现缓存实例的红色像素，再出现外部回退的蓝色像素，结束通道不得再次提交旧实例。该测试没有模拟完整箱子 renderer，覆盖的是生产缓存绘制与回退提交边界。

告示牌应独立处理：ImmediatelyFast 的 `SignTextCache` 缓存的是文字图集槽位，并有过期回收和资源重载失效；不能简单把文字当作箱子几何网格缓存。源码：[SignTextCache](https://github.com/RaphiMC/ImmediatelyFast/blob/ba8d2f46978e7f2ea9ea19b2af1843536ddda28c/common/src/main/java/net/raphimc/immediatelyfast/feature/sign_text_buffering/SignTextCache.java)。

## Flywheel：持久实例、脏更新与显式删除

`InstancedMaterial` 按模型键共享 instancer；`InstanceData` 显式标记脏数据与删除；`GPUInstancer` 只重写变化的实例，扩容时全量重建；`InstanceManager` 分离 tick、逐帧动画、世界更新和光照监听，并处理移除和原点移动。

源码：[InstancedMaterial](https://github.com/Engine-Room/Flywheel/blob/7ec4a460fb8416c32abe04da6efd8f0965835201/src/main/java/com/jozufozu/flywheel/backend/instancing/instancing/InstancedMaterial.java)、[InstanceData](https://github.com/Engine-Room/Flywheel/blob/7ec4a460fb8416c32abe04da6efd8f0965835201/src/main/java/com/jozufozu/flywheel/api/InstanceData.java)、[GPUInstancer](https://github.com/Engine-Room/Flywheel/blob/7ec4a460fb8416c32abe04da6efd8f0965835201/src/main/java/com/jozufozu/flywheel/backend/instancing/instancing/GPUInstancer.java)、[InstanceManager](https://github.com/Engine-Room/Flywheel/blob/7ec4a460fb8416c32abe04da6efd8f0965835201/src/main/java/com/jozufozu/flywheel/backend/instancing/InstanceManager.java)。

对应本项目：当前共享网格已经存在，但每次可见调用仍写入完整实例矩阵。值得设计持久实例身份、静态世界变换和逐帧可见 ID 列表，按模型/动画/光照变化分别更新。不能直接把当前 PoseStack 矩阵视为静态数据，它会随相机变化。机械轴、齿轮等需要显式模型与动画适配器；不宜对任意自定义方块实体做一次录制后永久复用。

## Embeddium：上传发布、排序更新与扩展边界

`RenderRegionManager` 按 region 收集上传，区分完整网格与 index-only 重排，并处理 arena 地址变化。`RenderSectionManager` 区分重要更新、普通重建和排序任务，过滤过时构建结果，还通过 `ChunkMeshEvent` 考虑扩展几何，不能仅因区块全是空气就断定没有网格。

源码：[RenderRegionManager](https://github.com/FiniteReality/embeddium/blob/dee0ebde6aea8b158613cd3b8ea5cdf0c1f6a9bf/src/main/java/me/jellysquid/mods/sodium/client/render/chunk/region/RenderRegionManager.java)、[RenderSectionManager](https://github.com/FiniteReality/embeddium/blob/dee0ebde6aea8b158613cd3b8ea5cdf0c1f6a9bf/src/main/java/me/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager.java)。

对应本项目：继续复用原版已构建数据，保留 index-only 与完整上传的区别；将“收到更新、排队、GPU 接纳、可跳过原版”作为分开的状态。下一轮应记录队列年龄、每帧发布字节和过时结果丢弃数，关联移动加载时的 P99，避免单纯增大预算。

## Nvidium：数据局部性与提交/回收协议

`RegionManager` 聚合 8×4×8 个 Section 的元数据，脏 region 集中提交；删除时清除元数据，避免旧槽位继续绘制。`UploadingBufferStream` 将上传分配与 fence 关联，完成后回收；其容量耗尽路径会强制完成 GL 工作。这里可参考生命周期协议，但不应直接采用阻塞路径作为 Vulkan P99 优化。

源码：[RegionManager](https://github.com/MCRcortex/nvidium/blob/f2028b2ba9a5dc95d69e73a9eaeadcb447367d7d/src/main/java/me/cortex/nvidium/managers/RegionManager.java)、[UploadingBufferStream](https://github.com/MCRcortex/nvidium/blob/f2028b2ba9a5dc95d69e73a9eaeadcb447367d7d/src/main/java/me/cortex/nvidium/util/UploadingBufferStream.java)。

对应本项目：先在高视距场景量化分层剔除、元数据布局和脏区域上传；Mesh Shader 路径要另做能力检测和回退。GL_NV 的 shader 与命令不能直接视作 VK_NV 路径的实现，Vulkan 的资源布局和同步仍须独立验证。

## Iris：阶段和目标都是渲染契约的一部分

`WorldRenderingPipeline` 明确区分阴影、手持物、透明前后与最终合成阶段，提供目标状态监听；其 core-shader 兼容文档解释了任意自定义 shader 与 Shader Pack 不能普遍自动合并的原因，并建议提供回退。

源码：[WorldRenderingPipeline](https://github.com/IrisShaders/Iris/blob/f8d7bea2ab82d21737e7aa8e4b2fb3a429078106/src/main/java/net/irisshaders/iris/pipeline/WorldRenderingPipeline.java)、[Core shader compatibility](https://github.com/IrisShaders/Iris/blob/f8d7bea2ab82d21737e7aa8e4b2fb3a429078106/docs/development/compatibility/core-shaders.md)。

对应本项目：Framebuffer 所有权、附件布局、渲染阶段、顶点格式和 Shader 行为需一起描述。仅恢复 GL 状态不能保证 Shader Pack 输出正确；外部扩展顶点格式和未知 shader 继续回退，不以名称相同作为兼容依据。

## 后续实施顺序与衡量方式

1. 先守住 fallback 与有序绘制边界，再细分合批中断原因，记录每帧复制字节、提交数与 CPU P99。
2. 方块实体扩展采用分件模型和持久实例，优先验证动画、相机移动、光照变化、资源重载及删除；文字单独缓存。
3. Bindless 作为可选能力，处理纹理代次、采样器与 GPU 退役后，再测试跨纹理合批收益。
4. Vulkan 用移动加载场景测队列年龄和跨 API 交接 P99，再决定上传预算或 region 布局改动。

本轮没有复制上述项目的实现代码，也没有新增这些模组作为运行依赖。除缓存实例 fallback 边界修复外，以上是源码对照与后续设计，不是已交付功能或 FPS 收益声明。
