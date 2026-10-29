# 光影包加载与游戏内选择

从“视频设置”或 VulkanForge 配置页的“光影包”进入。选择页的布局和交互参考 Iris 的 1.21.1 分支，并按 Forge 1.20.1 的 GUI API 独立实现；没有加入 Iris 运行依赖，也没有复制 Iris 的 GUI 代码、纹理或品牌标识。

## 界面行为

- 顶部为“光影包 / 选择”，列表第一行为独立的启用开关；空文件夹显示“光影：无光影包”和 Download Shaders。
- Download Shaders 通过链接确认页打开 Modrinth 的光影分类，不自动下载或安装光影。
- ZIP 和包含 `shaders/` 的文件夹可拖入窗口；后台验证并复制，重名时报错，保留原文件，不覆盖已安装包。文件夹内容变化约一秒内刷新。
- 点选一个包只修改待应用的选择，并准备启用。底部第一排为“打开光影包文件夹 / 光影包设置”，第二排为“取消 / 应用 / 完成”。
- 取消丢弃尚未应用的选择；应用成功后留在页面；完成成功后返回上一页。Esc 与完成相同。已经执行的应用不会被后续取消撤销。
- 进入光影包设置前先应用所选包，避免打开另一个包的设置。Ctrl+R 重新加载当前运行的包。
- IO 在后台执行，GPU 编译在渲染线程执行，成功后在下一帧边界切换。编译失败继续使用原包并显示错误；完成失败时留在选择页。
- 背景使用原版全景图或当前世界，再执行原版 blur 程序。原生 VK 将该程序编译成 SPIR-V 并在 Vulkan 中执行，不创建 GL 上下文。

选择和选项保存在游戏目录的 `config/vulkanforge-shaders.properties`，包放入同目录下的 `shaderpacks/`。关闭光影会保留最后成功使用的包名称，方便再次启用。

## 当前执行范围

GL 和纯 VK 共用标准目录/ZIP 解析、include、数字顺序通道、维度覆盖、宏选项、profile 和热切换控制器。纯 VK 的资源和绘制调用由启动引导转换为 CPU 状态与私有 Vulkan 资源，最终由 Vulkan Swapchain 呈现。

**当前只是 Iris / OptiFine 格式的后处理子集，并不是完整 Iris 渲染器。** 能执行 `deferred* → composite* → final` 的顶点/片元通道，输入为实际场景 `colortex0` / `gcolor` 与 `depthtex0`，输出为单个 RGBA8 颜色目标。支持常用相机、投影、上一帧矩阵、时间和天气 uniform，以及部分旧 GLSL 内建接口。

`gbuffers_*`、阴影绘制、计算/几何/细分着色器、MRT、HDR 目标、颜色 mipmap、自定义纹理、uniform 数组/块和复杂 properties 表达式尚未接入。需要这些功能的包会明确加载失败，不能据此宣称 BSL、Complementary 或任意现有 Iris 包可用。`GL_VK` 混合模式目前也明确拒绝执行光影包。

编译失败回退经过验证；运行期间意外报错则禁用出错的图。静默忽略一个包所需的通道并显示“加载成功”不是兼容策略。

## 参考核对

- [Iris ShaderPackScreen（1.21.1）](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/gui/screen/ShaderPackScreen.java)：底部两排按钮、应用/完成/取消、进入选项前应用选择。
- [Iris ShaderPackSelectionList（1.21.1）](https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/gui/element/ShaderPackSelectionList.java)：独立开关、空列表下载入口、居中包名、拖放提示与目录更新。
- [sulkanShaders](https://github.com/mravatins/sulkanShaders)：参考 Vulkan 通道和资源生命周期；该项目面向更新版 Minecraft / Fabric，使用自己的 `assets/sulkan/shaders` 资源包接口，不能直接替代本项目要求的 Iris / OptiFine 输入格式。

## NVIDIA 与 DLSS

当前测试设备是 RTX 3050 Laptop，GL 和 VK 都实际运行在该设备上。NVIDIA 官方表列明 RTX 30 系列支持 DLSS Super Resolution 和 DLAA，官方 Frame Generation 不支持该系列：[硬件支持表](https://www.nvidia.com/en-us/geforce/technologies/dlss/)。

**本项目尚未接入 DLSS SDK，也没有提供假启用开关。** 准确的物体运动矢量、深度、投影抖动、曝光和重置/生命周期管理是接入条件；现有上一帧相机矩阵不能替代动态实体的运动矢量。官方 SDK 提供 Vulkan / DirectX 路径，GL 还需要额外的互操作实现。后处理缩放不能标记为 DLSS。参见 [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS) 与 [编程指南](https://raw.githubusercontent.com/NVIDIA/DLSS/main/doc/DLSS_Programming_Guide_Release.pdf)。

## 验证方式

`smokeShaderPack` 验证目录/ZIP、include、输出指令、维度覆盖、profile、过期加载请求、编译失败保留旧资源、导入不覆盖与清理临时目录。游戏烟测在隔离的 `run-shaders/GL` 与 `run-shaders/VK` 目录执行，不修改普通 `run` 配置：

```powershell
.\gradlew.bat --offline smokeShaderPack
.\gradlew.bat --offline -PshaderPackSmoke -PshaderPackMode=GL runClient
.\gradlew.bat --offline -PshaderPackSmoke -PshaderPackMode=VK -PvalidationSmoke runClient
```

原生校验测试需要已配置 Vulkan 校验层。报告、编译错误和实际游戏截图写入 `build/reports/shader-packs/<模式>/`。测试通过点选、取消、应用、完成操作真实界面，并检查世界输出、错误回退、缩放、禁用与 GL 上下文约束。

可选 `'-PshaderPack.reference=<已有光影包目录>'` 加入外部灰度后处理包测试。它要求输出为场景灰度图，会检查颜色通道一致且仍有足够场景细节。开发时使用 [MinecraftShaderProgramming 的 Tutorial 1](https://github.com/saadam1n/MinecraftShaderProgramming/tree/master/Tutorial%201%20-%20Final%20Shader%20Program)，不修改其两个着色器源文件；外部测试输入只保存在忽略的 build 目录，不随模组发布。
