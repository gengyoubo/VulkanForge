# 8192 Vulkan 方块图集

方块图集现在支持每边最多 8192 像素，包括 8192×8192 RGBA8（256 MiB）和非正方形图集。`WORLD_ATLAS=1024x512` 记录的是当次 Minecraft 实际生成的图集尺寸；后端不会把小图集放大到 8192，也不会缩放图集或修改模型 UV。

修改覆盖 GL 异步 PBO 读取、同步读取、原始 CPU 纹理上传缓存、同进程桥接、二进制桥接及 Vulkan 图像上传。整图和动画补丁统一检查尺寸、RGBA 字节数和坐标，光照图仍要求完整 16×16。补丁还会根据当前 Vulkan 图集的实际尺寸检查边界。

大图集按整行切成最多 16 MiB 的上传块，直接从原像素数组的对应区间写入 staging buffer，不另建分块数组。沿用 64 MiB 在途数据预算与现有背压机制；这不是 staging buffer 总驻留内存上限。只有第一块和最后一块切换整张图像的布局，采样前的队列顺序与可见性保持不变。全图替换仍是资源重载边界，可能因背压等待；普通小补丁继续走异步上传和非阻塞准入。

创建 Vulkan 图像前查询指定格式和用途的设备尺寸限制；设备不支持时报告请求尺寸与硬件限制，不尝试创建越界图像。8192 仍需要足够显存和 Java 堆空间：仅一份完整 RGBA 像素数组就占 256 MiB，CPU 缓存、快照、PBO 和 Vulkan 图像还会占用额外内存。CPU 原始纹理缓存保留 256 MiB 总预算，无法缓存时沿用 PBO 读取路径。

验证命令（JDK 17，真实 GPU，隐藏窗口）：

```powershell
.\gradlew.bat smokeLargeTextureCapture :vulkan-lab:smokeLargeAtlas
.\gradlew.bat :vulkan-lab:smokeLargeAtlas -PlegacyGpuSmoke '-PvulkanVersion=1.2' '-Pvulkanforge.smokeReportDir=build/reports/large-atlas/fence-vk12'
.\gradlew.bat :vulkan-lab:smokeResources :vulkan-lab:smokeGlTail
```

`smokeLargeTextureCapture` 验证真实 8192×8192 PBO 读取、pack 状态恢复和 8192×512 CPU 缓存。`smokeLargeAtlas` 验证完整 256 MiB 图集上传与地形着色器采样、跨 4096 边界及分块边界、右下角二进制补丁、实际图像边界拒绝、非正方形图集及重新加载为 1×1；也检查无效尺寸、字节数和溢出坐标。报告保存在 `build/reports/large-atlas/`。

2026-10-27 在 NVIDIA GeForce RTX 3050 Laptop GPU 上通过上述测试：Vulkan 1.4 timeline 和 Vulkan 1.2 fence 两条上传路径均通过完整 8192×8192 二进制图集读取、上传、采样及补丁检查；GL 异步/同步读取、CPU 缓存、资源准入及 GL Tail 回归通过，完整 `build` 成功。测试环境报告 `VALIDATION=UNAVAILABLE`，因此不代表验证层检查已通过。

这组验证使用合成像素，不代表已经验证所有高清材质包、动画数量或资源包重载峰值。当前图集仍只有原有单 mip 级；本次没有增加 mipmap 或改变纹理过滤策略。
