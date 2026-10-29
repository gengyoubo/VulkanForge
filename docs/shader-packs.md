# VulkanForge 光影包加载器

VulkanForge 自己负责光影包解析、条件预处理、选项、uniform 表达式、通道图、资源与游戏内选择界面，不委托 Iris/Oculus 执行。GL 与纯 VK 共用同一套加载器；纯 VK 通过原生后端绘制，并由 Vulkan Swapchain 呈现，不创建 OpenGL 上下文。

光影包放到实例的 `shaderpacks/`，选择保存在 `config/vulkanforge-shaders.properties`。GL/VK 模式切换后需重启，包的选择可以在游戏内应用。普通启动器的原生 bootstrap 配置见 [安装说明](shader-runtime-install.md)。

2026-10-29 修复：补齐 1.20.1 的 `endFlashIntensity=0`，并让新版包引用的不存在生物群系键返回未匹配；VK uniform 反射根据优化后的 SPIR-V 活动成员计算，未使用的源代码声明不会被误报为缺失输入。不是给任意未知变量统一填零。

输入测试直接使用原始 `ComplementaryReimagined_r5.9.3.zip` 和生产加载器的输入表：

```powershell
.\gradlew.bat --offline smokeShaderPackUniformInputs
.\gradlew.bat --offline -PownPackSmoke -PownPackMode=VK -PwithoutEmbeddium -PvalidationSmoke runClient
```

原包 SHA-256 为 `fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`。报告位于 `build/reports/own-pack/`。`build/reports/oculus/` 是较早的外部前端参考测试，不能作为 VulkanForge 自有加载器完成的证据。`-PirisReference` / `-PoculusSmoke` 只用于参考实现的开发测试，生产运行包不包括 Oculus。

## VulkanForge 自有加载器

自有加载器不依赖 Iris/Oculus 类，GL 与纯 VK 共用同一套解析、计划和执行代码；纯 VK 下其 GL 风格调用由启动引导转换为 Vulkan。选择保存在 `config/vulkanforge-shaders.properties`，诊断写入 `logs/vulkanforge/shader-pack-compatibility.txt`。

已实现：

- 目录/ZIP、include、条件预处理、`shaders.properties` 预处理、选项与 profile、维度覆盖、`program.*.enabled` 条件。
- `prepare → deferred → composite → final` 全屏链，按 Iris 语义执行 colortex 主/备图像翻转：写入的缓冲默认翻转，`flip.<程序>.<缓冲>` 可覆盖；帧末仍在备图像上的持久（`Clear=false`）缓冲复制回主图像。
- MRT（`DRAWBUFFERS`/`RENDERTARGETS`，最多 8 个）、全部声明的颜色格式（含 HDR、SNORM、整型）、`ClearColor`、`size.buffer.*` 相对尺寸、`MipmapEnabled`、`blend.<程序>`（整程序因子或逐缓冲 `off`）、`scale.<程序>` 视口缩放。
- `noisetex`（`noiseTextureResolution`，固定种子）与 `texture.noise`、`texture.<阶段>.<采样器>` 和 `customTexture.<名称>` 的 PNG（包内路径或 `命名空间:路径`，读取 `.mcmeta` 的 blur/clamp）。
- `uniform.*`/`variable.*` 表达式（含 `smooth`），输入包括相机矩阵、时间天气、`eyeBrightness(Smooth)`、失明/黑暗/夜视、生物群系与 `BIOME_*`/`PPT_*` 常量、手持光源等；加载时用零值试算，未知输入在提交前报错。旧版 `gl_Fog` 映射为当前雾参数。

- `gbuffers_*` 世界通道：每个程序编译为游戏的 core `ShaderInstance`，由 `GameRenderer` 的 core shader getter（descriptor 匹配的 coremod）在世界帧内替换，按渲染阶段（天空、地形、实体、方块实体、半透明、粒子、天气、手）和标准回退层级选择程序，游戏照常逐次绘制设置矩阵、区块偏移、颜色调制、反照率和光照贴图。旧版内建（`ftransform`、`gl_MultiTexCoord*`、`gl_TextureMatrix`、`gl_NormalMatrix` 等）与 Iris core 名称（`vaPosition`、`modelViewMatrix`、`normalMatrix` 等）映射到实际顶点格式；`texture`/`gtexture`/`tex`、`lightmap` 采样器映射到游戏纹理；`alphaTest.*`、世界程序的 `blend.*` 与逐缓冲 `off`、`clouds=off` 已实现。
- 世界通道写入与场景共享深度的 MRT gbuffer：不透明几何写入 prepare 后的图像，`deferred` 在方块实体之后、半透明地形之前执行，之后的几何写入 deferred 后的图像。`depthtex1/2` 是半透明前的深度快照，`depthtex0` 在 composite 中是完整深度。无 PBR 资源包时 `normals`/`specular` 为平面法线/零。
- 阴影贴图：天空之后、地形之前，用 `shadow`（及 `shadow_solid/cutout/water` 回退）重新绘制区块层，正交投影按 `shadowDistance`，视图沿光源方向并按 `shadowIntervalSize` 对齐，`sunPathRotation` 生效；输出 `shadowcolor0/1`（默认白色清除，可声明格式），`shadowtex1` 为不含半透明的深度，`shadowHardwareFiltering[01]` 开启深度比较采样。`sunAngle`/`shadowAngle`/`sunPosition`/`shadowLightPosition`、`near`、`far`（渲染距离方块数）按参考语义计算。

未实现并在预检时明确拒绝：shadowcomp、计算/几何/细分着色器、raw/动态自定义纹理、`iris.features.required`；Fabulous 图形与世界通道同时使用时运行期拒绝。安装 Embeddium 时，包含地形程序的光影会被拒绝：Embeddium 的区块渲染器绕过游戏 core shader，地形不会使用光影程序。

以下运行但使用固定替代值，并以 `APPROXIMATED_*` 写入兼容报告：`mc_Entity`/`at_midBlock` 等方块元数据为“未映射”（需要扩展区块顶点格式），`entityId`/`blockEntityId`/`currentRenderedItemId`/`entityColor` 为未映射（需要按实体拆分批次）；阴影投射体只来自为玩家视锥剔除后的区块，且阴影贴图不绘制实体；手部绘制前不清除深度（手与世界做深度测试）；`oldLighting`、`separateAo`、`vignette`、`underwaterOverlay` 等原版渲染设置不生效。`heldItemId` 来自 `item.properties`。

Complementary 的默认配置（含阴影，shadowcomp 在默认 `COLORED_LIGHTING=0` 下关闭）可通过预检。安装 Embeddium 时仍会因地形程序被拒绝；开发环境用 `-PwithoutEmbeddium` 启动。该组合尚未完成游戏内回归。

```powershell
.\gradlew.bat --offline smokeShaderPack smokeOwnLoader
.\gradlew.bat --offline -PvalidationSmoke :vulkan-lab:smokeOwnShaderCompiler
.\gradlew.bat --offline -PshaderPackSmoke -PshaderPackMode=GL runClient
.\gradlew.bat --offline -PshaderPackSmoke -PshaderPackMode=VK -PvalidationSmoke runClient
```

`smokeOwnLoader` 对真实 Complementary 检查翻转调度、预检边界与 `POTATO` 档位；`smokeOwnShaderCompiler` 用离屏 Vulkan 把该包启用的全屏程序编译为 SPIR-V，`-PshaderPack.world` 时也按完整实体顶点格式编译全部世界程序与阴影程序（2026-10-29：30 个程序，校验错误 0）。游戏内测试新增天空世界通道（检查像素与世界绘制计数），并在有 Embeddium 时检查地形程序被拒绝、无 Embeddium 时检查地形像素。较早的 `build/reports/shader-packs` 记录不代表当前实现。

## 参考与许可证

- [Oculus 官方发布与许可](https://modrinth.com/mod/oculus)、[源码](https://github.com/Asek3/Oculus)：仅作行为与效果参考，不是自有加载器的运行时依赖。
- [Iris ShaderPackScreen](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/gui/screen/ShaderPackScreen.java)：早期简易 GUI 的布局参考。
- [sulkanShaders](https://github.com/mravatins/sulkanShaders)：Vulkan 通道与资源生命周期参考；它的资源输入格式不直接替代 Iris/OptiFine 包。
- [Khronos 顶点兼容扩展](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_legacy_vertex_attributes.html)。

本次没有集成 DLSS 或帧生成，界面也不显示虚假支持项。
