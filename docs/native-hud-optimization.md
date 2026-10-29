# 原生 VK 的 HUD 诊断与 GUI 快速路径

本轮完成独立 HUD 消融、原生 GUI sprite 快速路径、相邻兼容绘制合批，以及画面和同步校验。**没有实现跨帧 HUD 纹理缓存，也没有安装或验证 Exordium/Gnetum 的兼容性。** 快速路径当前默认关闭，显式启用后仍按原帧率更新 HUD。

## 已实现的路径

`GuiGraphics` 不带顶点颜色的 `innerBlit` 通常为每张图标生成四个 POSITION_TEX 顶点，再经过通用 BufferUploader、VAO、uniform 与绘制状态提取。快速路径直接构造等价的原生 sprite 数据包，保留贴图、姿态、颜色、投影、shader 混合方式和公开 uniform/sampler 状态，并执行 `ShaderInstance.clear()` 的清理约定。

后端只合并相邻且兼容的数据包：目标、shader、贴图、uniform、顶点布局、索引类型、视口、裁剪及光栅状态必须一致。保持原顺序，使用已有的帧槽动态缓冲和 MDI；没有把图标跨层重排。着色器使用 DrawID、VertexID 等依赖单次绘制语义的内建变量时禁止合批。

支持检查限定为标准 Position/UV0、Sampler0、ModelViewMat/ProjMat/ColorModulator 接口。额外 uniform、改变属性位置、不支持的状态或无法取得混合方式时，继续执行原 `innerBlit`。带顶点颜色的重载、字体、物品模型及未知 Mod HUD 保留原路径。快速路径也不依赖 VK-12 的 Region Bridge、动态实体合批或 Chloride Top-K 开关。

## 独立消融结果

原始数据：`build/reports/hud-ablation/before/`。1280×720，视距 8，固定相机、60 头牛，生存 HUD、9 个快捷栏物品，生命值 16、饥饿值 17。每项各测三段；第一组按下表顺序，后续组固定种子打乱。诊断计时开启，快速路径关闭。

| 单独跳过的内容 | 区间 FPS 中位数 | 渲染线程 CPU ms/帧 | 逻辑绘制数/帧 |
|---|---:|---:|---:|
| 无，基线 | 711.43 | 1.3585 | 163 |
| 第一人称双手/手持物 | 742.05 | 1.3387 | 162 |
| 快捷栏 | 801.74 | 1.2694 | 152 |
| 生命/饥饿/护甲等状态图标 | 717.16 | 1.2483 | 115 |
| 完整 GUI render | 857.48 | 1.0154 | 99 |
| F1 | 909.20 | 0.8908 | 92 |
| 双手与完整 GUI | 1022.07 | 0.9150 | 98 |

Forge 47.4.28 在此场景执行的是 ForgeGui 的 renderHealth/renderFood/renderArmor/renderAir，原版 `Gui.renderPlayerHealth()` 调用数为零；因此诊断同时覆盖实际 Forge overlay。计数确认各目标确实执行或被跳过。

区间 FPS 受运行顺序、JIT、GC 和世界动态影响，不能把各项 FPS 差值相加，也不能从一张 F1 截图推断全部收益来自 GUI。F1 与跳过双手和 GUI 的绘制数也不一致。这里消融用于定位，实际优化幅度使用下述同一 JVM 中的 A/B。

## 实际开关对照

最终无细粒度计时对照保存在 `build/reports/hud-ablation/final-comparison/`，使用同一 JVM 中交替的 LEGACY_GUI/FAST_GUI，各三段、每段 6 秒。固定 Java heap 为 256–1536 MiB，Code Cache 128 MiB；记录实际呈现帧、渲染线程 CPU 和分配量。详细 CPU/GPU scope 计时与 Vulkan validation 关闭，只有诊断计数开启。该结果不能与上表使用不同 heap 和计时设置的绝对 FPS 直接相减。

| 指标 | 原路径 | 快速路径 | 变化 |
|---|---:|---:|---:|
| 区间 FPS 中位数 | 761.42 | 870.36 | +14.3% |
| 渲染线程 CPU ms/帧 | 1.2412 | 1.0771 | −13.2% |
| 渲染线程分配 bytes/帧 | 894614 | 769832 | −13.9% |
| 逻辑绘制数/帧 | 约 163 | 163 | 相同场景内容 |
| 地形/实体绘制数/帧 | 69 / 20 | 69 / 20 | 不变 |

快速路径每帧处理 55 个 sprite，无接口回退，合并掉 49 条独立绘制，最终录制 6 条 GUI GPU 绘制命令。这是该固定场景的短时 A/B，不能外推为任意整合包或分辨率均提高 14.3%。另一次相同固定 heap 的早期对照为 715.72→823.31 FPS，最终清理约定修改后的详细 scope 对照为 728.08→817.07 FPS；它们只作方向复核，不混合到最终结果。

单独的 scope 对照在 `build/reports/hud-ablation/profile-comparison/`，同一最终实现，各两段、每段 4 秒：

| CPU scope，ms/帧 | 原路径 | 快速路径 |
|---|---:|---:|
| HUD 总计（含下列子项） | 0.3289 | 0.2134 |
| 快捷栏（含物品模型） | 0.1239 | 0.1224 |
| Forge 生命图标 | 0.0501 | 0.0109 |
| Forge 饥饿图标 | 0.0536 | 0.0105 |
| Forge 护甲图标 | 0.0271 | 0.0057 |
| 第一人称双手 | 0.0224 | 0.0222 |

生命、饥饿和护甲图标 scope 下降约 78–80%，HUD 总计下降约 35%；快捷栏仍约 0.12 ms，值得继续拆分物品模型与装饰层成本。HUD 是包含子项的计时，不能与子项相加。图标 scope 的改善也不等于游戏 FPS 提高 80%。

## 正确性与验证范围

同场景保持逻辑绘制数、地形绘制数、实体绘制数不变；画面对照只比较底部 HUD 的固定区域，世界中的牛、时间和叠加 FPS 文本可能变化，不用全屏差分判断等价。

最终两张截图均已检查，快捷栏、状态图标和手持物可见。底部 HUD 区域 `(350,585)–(930,720)` 共 78300 像素，其中 11910 像素存在差异，最大单通道差为 1/255，差异达到 2/255 的像素为零；生命区和快捷栏子区最大差同样为 1/255。结果保存在 `final-comparison/pixel-comparison.json`，属于此静态 HUD 的 1 LSB 容差对照，**不是逐位完全一致**。

`build/reports/hud-ablation/validation/` 保存启用快速路径的世界 smoke：主菜单、进出世界、背包、窗口缩放、资源重载、Fabulous、返回菜单与关闭。Vulkan 同步校验开启，结果 `STATUS=OK`、`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。另外通过构建、bootstrap/buffer smoke、原生动态合批与 legacy 回退测试；buffer smoke 检查标准 GUI shader 接口接受、额外 uniform 和错误属性位置拒绝。

上述结论限于该测试场景。没有把关闭 validation 时的 FPS 与启用时的 FPS 混用，也没有把诊断时有意缺失的 HUD 消融截图当成优化画面等价证据。共享工作目录扫描失败、并发游戏导致内存不足的尝试，以及未命中快速路径的早期 pilot，均排除在收益统计之外。

## 启用与复现

使用 JDK 17。开发启动：

```powershell
.\gradlew.bat -PnativeGame -PnativeGuiFastPath runClient
```

启动器 JVM 参数为 `-Dvulkanforge.nativeGuiFastPath=true`；删除该参数即使用原路径，需重启。主 JAR 仍为 `build/libs/vulkanforge-0.1.0.jar`。

诊断与对照：

```powershell
# 七项独立消融；诊断计时只在显式启用时注入
.\gradlew.bat -PgameFps -PfpsMode=VK -PhudAblation -PnativeProfile '-Pfps.rounds=3' '-Pfps.seconds=6' runClient
# 原路径/快速路径交替；没有 HUD scope 计时
.\gradlew.bat -PgameFps -PfpsMode=VK -PguiFastCompare -PnativeDiagnostics '-Pfps.rounds=3' '-Pfps.seconds=6' runClient
python tools/analyze-hud-ablation.py build/reports/hud-ablation/final-comparison
```

完整复测日志位于对应报告目录。固定 heap 的复测还使用本地 Gradle init script，将 JavaExec 的 min/max heap 设为 256m/1536m、`-XX:ReservedCodeCacheSize=128m`；Gradle heap 为 768m、Metaspace 上限 256m。为避免 IDE 与游戏共用构建产物，实测使用 `build/hud-isolated-workspace` 源码快照，其最终源码与主工作区核对后再复制报告和 JAR。

## Exordium/Gnetum 与缓存取舍

后续已经落地的第一版按槽 GPU 几何缓存、失效规则及单独对照见 [快捷栏缓存](native-hotbar-cache.md)。下文的局部纹理缓存仍是后续实验方向。

已核对具体 1.20.1 源码，而非把项目最新分支功能套到旧版本：

- [Exordium 1.2.1 的 BufferedComponent](https://github.com/tr7zw/Exordium/blob/1.2.1-1.20.1/Shared/src/main/java/dev/tr7zw/exordium/util/BufferedComponent.java) 使用 `cooldown` 与 `needsRender()`；Force Updates 绕过变化判断，但仍受 maxFps 限制。[快捷栏检测](https://github.com/tr7zw/Exordium/blob/1.2.1-1.20.1/Shared/src/main/java/dev/tr7zw/exordium/mixin/GuiHotbarMixin.java) 比较选中槽、数量、耐久、模型和弹出动画；附魔或物品冷却会持续要求刷新。因此“有变化才更新”已有基础，而“变化当帧更新”还涉及时间限制和动画语义。
- [Gnetum 1.20.1 的 PassManager](https://github.com/decce6/Gnetum/blob/1.20.1/src/main/java/me/decce/gnetum/PassManager.java) 分配组件更新 pass，并受最大 HUD FPS 控制；[FramebufferManager](https://github.com/decce6/Gnetum/blob/1.20.1/src/main/java/me/decce/gnetum/FramebufferManager.java) 维护前后纹理、完成后交换并清理下一张 back，每帧混合 front。分辨率、GUI scale 或 downscale 改变时重建。它已经使用 GPU。

后续实验应以组件为边界：先测快捷栏物品生成、提交和缓存维护各占多少，再对已知静态物品做按槽缓存；背景和选中框变化规则简单，可作为正确性原型，但当前 sprite 合批后可节省的成本更小。时钟、指南针、地图、附魔、冷却、弹出动画和自定义模型必须感知动画或逐帧回退。数量、耐久和选中框可以作为独立装饰层立即更新。不能仅靠 ItemStack 引用未变来判定图像未变。

复杂文字/物品适合测试局部透明纹理缓存；少量 quad 应先测是否值得增加缓存查找与管理。原版反色准星依赖每帧世界颜色，应保留每帧正确混合，可缓存几何。未知 Mod HUD 保守保留原渲染，窗口/GUI scale/资源重载/世界切换需使相关缓存失效。

Vulkan 原生缓存可让离屏更新和合成留在同一图形队列的提交流程中，用 image barrier 管理采样与写入依赖；不必为了每次 HUD 更新增加 CPU 等待或独立 submit。Front/Back 指针交换本身不是同步：旧纹理再次写入前仍须覆盖前一次采样的依赖。同队列正确的 barrier 或缓存版本延迟回收均可设计，不能一概声称必须每在途帧分配一份。局部更新还需清除旧像素，并保持透明度、组件重叠与绘制顺序。收益须包含缓存检测、更新和合成的总成本后再测定。
