# 原生 VK 快捷栏按槽几何缓存

已实现第一版按槽缓存：静态物品首次绘制时保留独立 VertexBuffer，之后复用设备本地的顶点和索引缓冲，跳过物品模型的重复顶点生成和上传。每帧仍按原顺序执行原 RenderType、着色器参数更新与绘制；这是几何缓存，不是降低 HUD 刷新率，也没有增加离屏 HUD 纹理或 GPU 读回。

当前为显式开启的实验功能，限于原生 VK 和玩家快捷栏。其他 GUI、背包物品、手持物、世界物品与第三方 HUD 沿用原路径。

## 支持与失效

第一版采用明确的原版物品集合：石头、圆石、泥土、玻璃、火把、钻石、铁锭、金锭、煤、钻石剑、铁剑、水桶和空桶；同时要求基础模型是标准 SimpleBakedModel，使用标准且为空的 ItemOverrides，且不是自定义物品渲染器。检查的是未解析的基础模型，避免把已选中的 override 子模型误判为静态模型。

| 变化 | 行为 |
|---|---|
| 相同物品、NBT、位置与 GUI 姿态 | 复用本槽 GPU 几何 |
| 数量变化 | 几何继续复用；原版角标正常更新 |
| 耐久或其他 NBT 原地变化 | 比较 NBT 快照，本帧重建；原版耐久条正常更新 |
| 换物品、槽位位置或姿态改变 | 本帧重建 |
| 附魔、冷却、弹出动画 | 释放本槽缓存并正常绘制 |
| 时钟、指南针、地图、树叶、箱子、未知物品或模型 | 正常绘制 |
| 世界/玩家切换、窗口或 GUI scale 改变、资源重载 | 清理缓存，下次需要时重建 |

选中框、数量、耐久条、冷却遮罩和 ItemDecorator 不在缓存中，继续执行原方法。图集纹理每帧正常采样，几何缓存本身不会冻结动画贴图。着色器的时间、颜色、矩阵、光照等参数也仍按帧更新。

缓存按 renderSlot 的 1–10 号种子分槽，包含副手位置。每槽最多 16 层、256 KiB 新生成的几何；超限保留正常绘制且不发布缓存。120 个渲染帧未使用的槽会释放。GPU 常驻资源使用现有 geometry budget，预算不足时保留已有的非驻留绘制回退。删除、失效与重建通过已有帧 fence 延迟回收资源；不增加 CPU 等待。

## 实现边界

Gui.renderSlot 的物品调用进入 NativeHotbarCache；已有 EntityDraws 的 RenderType capture 在首次绘制时接收实际排序后的几何，上传独立 STATIC VertexBuffer。后续命中时重新执行各层 setup/clear、标准 drawWithShader 与必要的物品光照，保留混合、裁剪、深度和顺序。静态 VBO 的 buffer id/version 进入现有 NativeGeometryCache，在 GPU 上常驻。

常驻物品绘制明确禁止进入 transient dynamic group，避免把常驻顶点缓冲与临时合批索引混用。这一边界在开启动态合批的 GPU 像素测试中验证。

当前不承诺任意修改原版物品行为的 Mixin 都能正确缓存。尤其是动态替换标准模型、改变已知物品顶点或颜色的模组，需要单独适配或关闭缓存。未测试 Exordium/Gnetum 与本缓存叠加；它们的整体 HUD 缓存不属于本次实现。

## 性能与画面

同一 JVM 中交替 `NO_ITEM_CACHE` 与 `ITEM_CACHE`，各三段、每段 6 秒；两边都开启上一轮 GUI sprite 快速路径，关闭详细 CPU/GPU scope 计时与 validation。1280×720、视距 8、60 头牛、生存 HUD、9 个物品，固定 256–1536 MiB heap。数据位于 `build/reports/hotbar-cache/final/`。

| 无详细 scope 计时，三段/模式 | 关闭物品缓存 | 开启物品缓存 |
|---|---:|---:|
| FPS 区间中位数 | 903.27 | 886.57 |
| 渲染线程 CPU ms/帧 | 1.0661 | 1.1159 |
| 渲染线程分配 bytes/帧 | 763036 | 757189 |
| 地形/实体逻辑绘制数/帧 | 69 / 20 | 69 / 20 |
| 缓存命中/回退/帧 | 0 / 0 | 5 / 4 |

最终无 scope 对照 FPS 为 −1.85%，渲染线程 CPU 为 +4.67%，分配量为 −0.77%；**没有证明稳定的整帧性能收益，因此默认关闭。** 缓存模式的三个区间为 792.78、886.57、927.14 FPS，关闭模式为 892.24、922.74、903.27 FPS，运行内也存在明显波动。先前两段 pilot 的 804.25→840.11 FPS 仅作实验记录，不能挑选它作为最终收益。

为判断局部成本是否下降，另做各两段、每段 4 秒的独立 scope 对照，保存在 `profile/`：快捷栏 inclusive CPU 从 0.12775 降到 0.12129 ms/帧（约 −5.1%，节省约 6.5 μs）；HUD inclusive 从 0.22394 降到 0.21213 ms/帧。两者是包含关系，不能相加。该对照 FPS 为 785.75→826.37，但详细计时设置不同，不与无 scope 结果混合。

稳定命中阶段每帧复用 5 次 GPU 常驻绘制、避免约 1092 bytes 的临时几何拷贝，新增 resident 上传为零；其余 4 个物品正常绘制。已有 Flerovium 路径的顶点生成成本较低，缓存还保留每层 RenderType 和 shader 提交成本，这是本实现收益有限的一个合理解释；尚未做逐项成本隔离来证明全部差异来自何处。

这轮结果衡量的是在 GUI sprite 快速路径之上增加物品缓存的效果，不能与上一轮 14.3% 的收益直接相加。缓存保留绘制次数，节省模型生成与上传；没有减少世界、地形或实体内容。

最终开关截图底部 HUD 区域 `(350,585)–(930,720)` 的 78300 像素中，11429 像素有差异，最大单通道差为 1/255，差异达到 2/255 的像素为零；快捷栏子区域最大差同为 1/255。结果位于 `final/pixel-comparison.json`。这是固定 HUD 的容差对照，不是逐位完全一致，也不是运动世界画面的全屏比较。

## 验证

- 世界 smoke 检查数量变化不重建、原地修改耐久/NBT 重建、换物品重建、弹出/冷却/时钟回退，并保存各阶段截图。
- 同一测试覆盖背包、窗口缩放、资源重载、Fabulous、退出世界和返回菜单；退出世界后缓存条目数必须为零。
- `smokeNativeHotbarResident` 使用 GPU 像素读回检查非地形缓冲的复用、删除重建、透明绘制顺序和动态合批边界。
- bootstrap/buffer smoke、原有动态合批及 legacy 测试同时通过。

最终完整验证为 `STATUS=OK`、`OPENGL_CONTEXTS=0`、`FORBIDDEN_GL_CALLS=0`、`VALIDATION_ERRORS=0`、`CLOSED=true`。报告和阶段截图位于 `build/reports/hotbar-cache/validation/`；非地形常驻缓冲的 GPU 像素、复用、删除重建及动态边界检查全部通过。最终源码未因上述性能结果调整默认开关。

早期未接到既有 RenderType coremod capture 的 pilot 没有命中缓存，未纳入收益。一次生命周期测试在返回菜单后调用了已销毁的 Embeddium 可见性统计，测试失败；已改为直接检查本缓存的生命周期状态，并重新执行完整验证。相关失败日志保留在 build 下，不作为成功证据。

## 使用与复现

使用最新 `build/libs/vulkanforge-0.1.0.jar`。启动器 JVM 参数：

```text
-Dvulkanforge.nativeGuiFastPath=true
-Dvulkanforge.nativeHotbarCache=true
```

开发启动：

```powershell
.\gradlew.bat -PnativeGame -PnativeGuiFastPath -PnativeHotbarCache runClient
# 同一 JVM 中只切换物品缓存，保持 sprite 快速路径开启
.\gradlew.bat -PgameFps -PfpsMode=VK -PhotbarCacheCompare -PnativeDiagnostics '-Pfps.rounds=3' '-Pfps.seconds=6' '-Pfps.output=build/reports/hotbar-cache/final' runClient
python tools/analyze-hud-ablation.py build/reports/hotbar-cache/final
```

删除 `nativeHotbarCache` 参数并重启即可关闭物品缓存。GUI sprite 快速路径可以独立使用。固定 heap 的实测使用报告中的 runtime.init.gradle；测试在隔离源码快照中执行，最终源码和构建 JAR 经核对后复制回主工作区。
