# Litematica-Printer-Forge
[English](./README.md) | **中文**

类似于 [投影打印机](https://github.com/aria1th/litematica-printer) 的Forge模组

## 前置
此模组需要安装 **[forgematica](https://modrinth.com/mod/forgematica)**, **[mafglib](https://modrinth.com/mod/mafglib)** 以及我fork的 **[curtain](https://github.com/jxmm52547/Curtain)**

## 使用
与**Litematica-Printer**使用方法一致


`easyPlaceMode++` - 关闭打印机，但使用基于假旋转的简单放置模式。

`verifierFindInventoryContents`    - **验证程序**会将包含项目的块指示为**“错误状态”**，即使它实际上具有正确的状态。对于与**比较器**相关的东西很有用。

`printerOff` - 关闭打印机并使用 Normal easyplace。

`printerUseInventoryCache` - 使用自己的库存实用程序，该实用程序对于不同步来说更稳定。

### 打印机设置：

现在，“设置”具有带有打印机的名称。

**阻止正在执行的其他操作的选项：**

`printerAllowInventoryOperations`  - 打印机将匹配并输入漏斗/投掷器/箱子/等所需要填充物的，主要用于过滤器设置。

>`inventoryCloseScreenAfterDone`  -  当填充完成/或无法填充屏幕时，打印机将关闭屏幕。

>`printerInventoryScreenWait`  - 打印机将在屏幕打开后等待此时间（毫秒）与服务器同步。

>`printerInventoryOperationRetry` - 打印机将重试单击以填充此数量的物品栏：推荐 - 3-20

>`printerInventoryOperationAllowAllNamed` - 打印机将允许具有相同填充物大小的其他命名项目用作筛选项目。

`printerBreakBlocks` - 打印机将破坏原理图中所有额外或错误的方块。可以在放置时执行，但不建议这样做。

> `printerBedrockBreaking` - 打印机将用 **急迫2， 效率5** 来破基岩，需要红石火把、活塞。

> >`printerBedrockBreakingUseSlimeblock` - 打印机将允许放置粘液块，以找到破坏基岩的有效位置。

`printerFlippincactus` - 如果启用，打印机将用仙人掌翻转方块。

`printerClearFluids` - 打印机将执行清除操作，默认情况下，岩浆/水（圆石/海绵）。

>`printerClearFluidsUseCobblestone` - 打印机将使用 **圆石** 代替 **海绵** 来排水。

>`printerClearSnowLayer` - 打印机将使用 **光照** 清除雪。

**主要特点**：

`printerAccurateBlockPlacement` - 打印机将使用 AccurateBlockPlacement 协议，该协议通过 carpet extra 进行处理。

`printerFakeRotation` - 打印机将使用假旋转将方块放置在所需的方向上。

>`printerFakeRotationTicks` : 发送旋转数据包后，打印机将等待滴答声（每滴答50毫秒）。

>`printerFakeRotationLimitPerTicks` : 如果打印机等待刻度设置为 0，则打印机将使用此数字限制每次刻度的假旋转。

>`disableSingleplayerPlacementHandling` : 打印机不会根据假旋转本身修改放置方向。推荐关闭。

**调试**：

`ShowDebugMessages` - 打印机将显示调试消息，原因为方块放置失败/跳过/等。

>`ShowDebugExtraMessages` - 打印机将通知您当前块放置和假旋转。

**限制**：

`easyPlaceModePrinterRangeX / Y / Z` -  打印机将仅放置从启动光线追踪位置开始展开的块。

`easyPlaceModePrinterMaxBlocks` - 打印机每次滴答时可以执行的最大块/交互（或 easyplace 操作）

`easyPlaceModeDelay` - 打印机操作之间的延迟 - 建议> 0.05 （50ms） 同步块状态。

`easyPlaceModeHotbarOnly` - 打印机将仅使用快捷栏，可以使用更少的数据包进行修改。

`printerSleepStackEmptied` - 当使用的填充物被清空时，打印机将进入睡眠状态，以同步/防止一些反作弊。

`easyPlaceModePrinterMaxItemChanges` - 打印机将限制每个周期的更改项目操作。**推荐值 ： 2**

`printerBreakIgnoresExtra` - 打印机不会破坏额外标记的块。适用于基岩破碎和破碎。

**红石**：

`printerUsePumpkinpieForComposter` - 打印机将使用南瓜派来匹配堆肥等级。

`printerSmartRedstoneAvoid` - 打印机在放置块时将遵循侦测器/ETC顺序。它将尝试避免侦测器更新。

`printerObserverAvoidAll` - 打印机将避免在侦测器面临错误状态块时被放置，但在某些情况下会放置**墙壁**/等。

`printerAvoidCheckOnlyPistons` - 打印机将忽略分配器QC状态。

`printerSuppressPushLimitPistons` - 打印机不会直接放置活塞，但不会延长：这意味着需要推限制。

`printerUseIceForWater` - 打印机会将冰块放置在水源应有的位置。

`printerCheckWaterFirstForWaterlogged` - 打印机不会放置水，并等待在那里放置水。

`printerPlaceMinecart` - 打印机将 Minecart 放置在有**可被检测的动力铁轨**上，当会触发 TNT 时，它不会放置它。