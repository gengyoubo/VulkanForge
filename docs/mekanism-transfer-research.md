# 通用机械管道与机器传输：初步归因

## 范围和样本

分析对象为整合包中的 Mekanism 10.4.16.80。源码按先前核对的版本提交 `50978fc` 阅读；对照的 JFR 是 2026-11-06 生存存档、MFCL 索引优化前的两分钟录制，不是优化后的新测量。采样时用户描述为多种机器混合。磁盘上的主世界存档记录了 995 个 Mekanism 管道和线缆类方块实体：气体管道 301、能源线缆 252、机械管道 298、物流传输器 144。这是已保存区块的数量，不是录制时活跃或正在传输的数量。

使用 `tools/analyze-mekanism-transfer-jfr.py` 扫描完整导出栈：服务端线程 2750 个 Java CPU 执行样本中，79 个经过指定的 Mekanism 网络、管道、分配或连接更新类，约 2.9%。其中能源相关 23、流体相关 23、化学品相关 15、物品相关 8、栈中包含 Capability/缓存相关名称的 29；分类可重叠。具体帧包括 `EnergyNetwork.tickEmit` 11、`MechanicalPipe.pullFromAcceptors` 11、`FluidNetwork.tickEmit` 7、`BoxedChemicalNetwork.tickEmit` 5、`Transmitter.refreshConnections` 10。数值是栈的包含计数而不是各自独占耗时；24 个栈被录制器截断，JIT 内联归因也受当次录制未启用 DebugNonSafepoints 限制。它们既不是 MSPT 占比，也不能代表 MFCL 优化后的服务器或高吞吐负载。

## 已确认的重复工作

1. **接收端候选与模拟插入。** 能源、流体、化学品网络在缓冲非空时，每 Tick 从网络接受端缓存遍历位置和方向，为本次发送新建 `Target` 并逐端点模拟插入，以筛出能接收的端点；`Target.sendPossible` 随后又对筛出的端点模拟插入，再进行分配和实际写入。流体筛选使用 `FluidUtils.canFill`，同样会调用模拟 `fill`。这是源码中确定存在的重复调用，但尚未测得次数、成本和占比。参见 [EnergyNetwork](https://github.com/mekanism/Mekanism/blob/50978fc/src/main/java/mekanism/common/content/network/EnergyNetwork.java)、[FluidNetwork](https://github.com/mekanism/Mekanism/blob/50978fc/src/main/java/mekanism/common/content/network/FluidNetwork.java)、[Target](https://github.com/mekanism/Mekanism/blob/50978fc/src/main/java/mekanism/common/lib/distribution/Target.java)。
2. **主动抽取时构造列表。** 能源线缆和机械管道的 `pullFromAcceptors` 根据 PULL 面调用 `getConnectedAcceptors`；后者每次新建列表，遍历相应方向并解析现有缓存。化学管道有单独实现，也构造列表。方向最多六个，分配能否被 JVM 消除、实测有多贵都未验证；不能仅凭源码断定这是大热点。
3. **连接已经有缓存。** `AcceptorCache` 存储接收端及 `LazyOptional`，并对 Capability 失效注册监听；`NetworkAcceptorCache` 按位置和方向维护网络接收端，变更经 `commit` 更新，网络合并和注销也会改变缓存。因此不能假设每 Tick 都从世界重新查找全部 Capability。邻居变化和刷新时仍可能查询，需用计数区别稳定网络与频繁变化的网络。

## 正确性边界与候选

不要直接缓存第一次模拟的数量供 `sendPossible` 使用：`sendPossible` 遍历过程中可能先向前一个端点执行实际插入，后一个端点可能是同一机器或同一多方块共享缓冲的另一面；其容量、过滤条件或其他外部状态因此可能已改变。缓存上一次 Tick 的“接收/拒绝”也不安全：机器能量、流体种类、红石、配置、区块和 Capability 都可能改变。对模拟方法有副作用或动态返回值的第三方实现，减少调用次数本身也可能改变兼容性。

可以优先实验缓存**稳定拓扑的遍历结构**，而不是缓存能否插入或插入数量：保留每个方向、顺序和重复连接，在网络连接更新、合并、拆分、卸载及 Capability 失效时同步失效，且每 Tick 保留原有模拟与分配行为。它能否节省足够开销目前未知；网络缓存的现有枚举顺序和同一目标从多面连接的行为都必须保持。另一个较局部的研究对象是 PULL 方向列表构建，但也必须保持对移除、无效 Capability、邻居变化及遍历过程中变化的处理。

## 下一步可复现实验

在隔离服务器构建有持续输入和输出的能源、液体和气体网络，分别控制管道数、端点数、连接面数及接收端空/满/变化状态；物流传输器单独测量，不把它当成同一种网络算法。每次固定拓扑和供需，检查实际发送量、守恒、分配顺序和最终存量；覆盖同一存储多面连接、网络合并拆分、区块卸载重载、Capability 失效和机器配置改变。开发专用计数器分别记录每 Tick 的候选面、模拟调用、成功/拒绝、目标对象创建、列表创建及实际传输；计数器会增加开销，计数与性能 A/B 应分别运行。对照组和原型组使用相同世界副本、交替顺序、足够预热和多个重复，JFR 从启动开启 `DebugNonSafepoints`。只有行为对照通过且收益可重复后，再用当前 MFCL 版本的真实整合包副本验证。

## 复现采样统计

`python tools/analyze-mekanism-transfer-jfr.py <录制.jfr>`。录制含启动参数等敏感内容，原始 JFR 不应外传。脚本只输出汇总，不输出 JVM 参数。
