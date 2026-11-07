# AE2 控制器反射查找：限定范围的实验

## 改动

用户确认先测试 AE2 控制器宿主链。开发原型只对运行时类型精确为 `appeng.blockentity.networking.ControllerBlockEntity` 的对象缓存三个**不存在的反射成员**：无参 `getUpgrades()`、无参 `getHost()` 和本类声明的 `host` 字段。对存在成员、其他类、其他方法名、反射检查结果不确定的情况均返回原方法。`getBlockEntity()`、递归深度、方法调用、对象字段、能量卡数量、配置和能源存量仍按原逻辑读取，不缓存查询结果。查找时只有 `NoSuchMethodException` / `NoSuchFieldException` 被视作明确不存在。

该优化现已接入 VulkanForge 的正式构建，默认启用；可通过 JVM 参数 `-Dvulkanforge.disableAe2ControllerReflection=true` 禁用以便排查。目标方法签名不匹配时跳过变换。下述独立实验和模组包测试记录属于接入前的研究；不要将独立实验 jar 与已接入的 VulkanForge 同时安装。整合包性能与兼容性仍待实际验证。

## 隔离 Forge 验证

从用户实例复制并通过 SHA-256 确认 AE2 Forge 15.4.12、AE2 Overclocked 1.2.3-fix3、GuideME 20.1.15，另载入 Canary，在 Forge 47.4.28 的一次性开发世界中放置两个真实控制器。对比开启/关闭候选时真实 `EnergyCardRuntime` 的公开查询、私有递归和宿主/字段反射结果，同时验证非目标类回退、控制器自引用，以及十步交替模拟和实际能源注入的返回值与存量。三个独立服务端进程均通过；无其他整合包模组，控制器也没有组成真实网络。

每进程每种路径交替计时 6 对，每对各 5,000 调用。对照也包含一个禁用的探针调用，且计时通过反射调用被测入口，不能看成完全未修改模组或服务器 Tick。三次进程结果的每进程中位：

| 实际入口 | 对照 ns/次 | 候选 ns/次 | 对照 / 候选堆分配 B/次 |
| --- | --- | --- | --- |
| 私有递归 | 27,853 / 23,067 / 115,174 | 1,903 / 1,789 / 6,770 | 15,424 / 904 |
| 公开能量卡检查 | 24,687 / 22,556 / 110,960 | 1,897 / 1,809 / 6,293 | 15,512 / 992 |
| 控制器模拟能源注入 | 25,010 / 23,688 / 117,972 | 1,732 / 1,732 / 6,483 | 15,560 / 1,040 |

第三进程整体慢得多，原因未确定；配对方向一致但波动说明这些数字不应外推成 MSPT。线程分配不含其他线程和本地内存。该测试仅覆盖一个没有真实 AE 网络和升级交互的控制器类型，仍需整合包副本以及安全上下文、其他 Mixin、热重定义和生命周期兼容测试。

结果：`build/reports/ae2-controller-reflection/19a4c6c4-f49d-492e-817d-af9c87362dbb/summary.json`。包名隔离后又重新跑了一个进程，行为与三个场景均通过：`build/reports/ae2-controller-reflection/612a99bb-27cb-422c-9a52-3157cb85113b/summary.json`。

复现：JDK 17 下运行 `python tools/benchmark-ae2-controller-forge.py --mods <实例的mods目录> --forks 3`。脚本只读取并复制经过哈希确认的模组到开发缓存，为每组复制开发世界；不改用户模组或存档。

## MFCL 索引开启时的旧录制

以 JDK `RecordingFile` 限制到此前两次 MFCL 索引模式的 180 秒测量窗口，使用最大 192 MB 堆逐事件读取；不输出 JVM 参数或原始路径。两组服务端线程分别有 4,051、3,981 个带栈 Java CPU 样本，其中 15、21 个包含 `EnergyCardRuntime.getInstalledEnergyCards`；这 15、21 个栈也包含 Mekanism `EnergyNetwork.tickEmit`，其中 14、18 个包含 `ControllerBlockEntity` 方法帧。服务端线程的对象分配采样事件分别为 7,257、7,689，其中 223、229 个栈包含该方法。分配采样并非精确分配次数或字节量，CPU 样本占比也不是 MSPT 占比；栈帧不能逐个证明实际接收对象是精确控制器类。这两份录制来自优化前，不能当作本次候选的 A/B 测试。

汇总见 `build/reports/ae2-controller-reflection/jfr-analysis/summary.json`，工具为 `tools/analyze-ae2-existing-recordings.py` 和 `tools/experiments/Ae2RecordingAnalysis.java`。原始录制包含敏感启动参数，不应外传。

## 整合包副本尝试

独立实验 jar 已构建，但尚未完成整合包 A/B。第一次复制两套目录时，由于 MFCL 生成资源约 23.6 万个小文件，按逻辑字节数预估低于实际磁盘占用，剩余空间接近下限时中止并清理了已标记的副本，游戏没有启动。第二次改为一份隔离实例，准备同一世界模板并计划每轮重置世界和配置；基线启动后系统可用内存降到约 0.4 GB，在进入世界前停止了本次测试进程。没有取得整合包性能样本，不能根据这两次尝试推断游戏收益。

重试脚本 `tools/benchmark-ae2-controller-pack.py` 已增加按 4 KiB 分配单元估算、逐文件磁盘余量检查、启动前 5 GB 可用物理内存要求和运行时 1.5 GB 下限；该 5 GB 阈值是此次机器的保守测试条件，不是游戏的最低硬件要求。它只复制源目录；会在经过标记和路径校验的自有测试目录中重置存档和配置。为节省空间同一隔离实例复用，MFCL 等其他运行缓存可能跨轮演化，故两组只能作为初步对照；正式结论还需独立重复和负载控制。实验 jar 尚未在整合包中完成加载和行为验证。
