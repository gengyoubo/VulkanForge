# VulkanForge 自有光影加载器

运行时不需要 Iris/Oculus。将 VulkanForge 放入 Minecraft 1.20.1 / Forge 47.x 实例的 `mods/`，原始光影 ZIP 放入 `shaderpacks/`。从视频设置或 VulkanForge 设置进入自有光影界面。

纯 VK 使用 Java 17，启动器 JVM 参数需要指向 bootstrap 的实际绝对路径：

```text
-javaagent:C:\你的实例\bootstrap\vulkanforge-native-bootstrap.jar
```

开发环境的 `gradlew runClient` 已配置此参数。后端设置保存在 `config/vulkanforge-client.toml`，选择 VK 后重启。原生模式使用 GLFW_NO_API 窗口和 Vulkan Swapchain；GL 模式使用 OpenGL。

当前自有地形程序依靠游戏 core shader 通道。Embeddium 绕过这些通道，因此该组合仍会被明确拒绝；开发测试使用 `-PwithoutEmbeddium`。不要把较早包含 Oculus 的参考运行包误当作自研加载器。

已实现功能、近似输入和未实现项见 [shader-packs.md](shader-packs.md)。单纯的表达式或 shader 编译测试通过不代表完整世界效果已验证。
