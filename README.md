# VulkanForge

Minecraft **1.20.1 / Forge 47.4.28 / Java 17** 客户端 Vulkan 渲染实验。

当前已经打通两条路径：

- 独立 Vulkan 1.4 窗口：交换链、动态渲染、缩放重建、三角形绘制。
- 游戏主窗口预览：Vulkan 离屏绘制旋转三角形 → staging buffer 读回 → 后台传输 RGBA → 游戏线程上传纹理 → 主菜单 / HUD 显示。

**当前世界、实体和 GUI 仍由原版 OpenGL 渲染。窗口内预览是接入验证，不是完整 Vulkan 后端，也不是零拷贝加速路径。**

## 构建和启动

使用 JDK 17。若系统默认 `java` 为 Java 8 或较新 JDK，先在当前 PowerShell 会话中设置 `JAVA_HOME`，不要修改全局环境：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
.\gradlew.bat runClient
```

IntelliJ IDEA：导入根目录的 `build.gradle`，将 **Gradle JVM** 和 **Project SDK** 设为 Java 17。

产物：`build/libs/vulkanforge-0.1.0.jar`。放入 Forge 1.20.1 客户端的 `mods` 文件夹即可；Vulkan 测试程序和运行库已打包，无需另放 LWJGL jar。当前随包提供 Windows/Linux x86_64 原生库；Linux 路径尚未实机验证。

游戏中：

- 主菜单左下角 **Vulkan 窗口内预览 / Vulkan preview** 按钮，或 **F8**：启停右上角的窗口内预览。
- **F9**：打开独立 Vulkan 三角形窗口；该窗口按 Esc 关闭。
- 快捷键可以在原版按键设置中重新绑定。
- 没有满足 Vulkan 1.4 要求的设备时，预览显示失败状态并保存报告，原版游戏继续运行。

日志在游戏目录 `logs/vulkanforge/`，开发环境即 `run/logs/vulkanforge/`：

| 文件 | 内容 |
| --- | --- |
| `probe-report.txt` | 启动时的 loader / GPU / 功能检测与设备创建 |
| `bridge-report.txt` | 游戏窗口预览的离屏渲染与退出结果 |
| `triangle-report.txt` | 独立窗口测试 |

## 验证命令

```powershell
# 不启动 Minecraft，直接开发独立实验模块
.\gradlew.bat -p vulkan-lab runProbe
.\gradlew.bat -p vulkan-lab runTriangle

# 真实 GPU 集成检查：RGBA 像素、动画、二进制帧协议、正常退出和停止请求
.\gradlew.bat :vulkan-lab:smokeBridge

# 交换链缩放检查
java -jar vulkan-lab/build/libs/vulkan-lab.jar --triangle --frames=90 --resize-smoke

# 安装 Vulkan SDK 校验层后，可要求其必须存在
java -jar vulkan-lab/build/libs/vulkan-lab.jar --probe --require-validation
```

普通 `build` 不要求 GPU 或 Vulkan 驱动。`smokeBridge` 需要 Vulkan 1.4 硬件和驱动。日志中 `VALIDATION=UNAVAILABLE` 表示没有启用校验层；此时错误计数为零不代表通过了校验层检查。

## 接入结构

- `src/main/java/dev/vulkanforge/client/ClientEvents.java`：Forge 生命周期、屏幕/HUD 事件、按键。
- `VulkanPreview` / `FrameTexture`：只在游戏渲染线程处理纹理上传和绘制；关闭时释放纹理与 native buffer。
- `OffscreenBridge`：后台进程与三缓冲最新帧邮箱，不让游戏线程等待管道或 GPU；关闭 stdin 请求 Vulkan 进程释放资源，超时强制回收。
- `bridge-protocol/`：两个模块共享的有界帧协议。
- `vulkan-lab/`：LWJGL 3.3.6，Vulkan 实例、设备、离屏图像、同步、内存与交换链。

预览目标固定为 **640×360、最高 30 FPS**，随 GUI 尺寸等比显示。它每帧读回并上传约 0.88 MiB，适用于验证接入。后续主场景替换需要设计窗口呈现、GPU 资源共享/同进程绑定，以及原版绘制调用和 Forge 模组的兼容路径。

Vulkan 进程与 Minecraft 分离，避免新旧 LWJGL 的 Java 类和 native 库混入同一个加载器。打包的 Gradle wrapper 来自 Forge 官方 MDK；LWJGL 及其原生依赖许可证随实验模块 jar 保留。
