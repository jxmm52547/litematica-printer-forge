# Litematica-Printer-Forge
**English** | [中文](./README.CN.md)

A Forge mod like [Litematica-Printer](https://github.com/aria1th/litematica-printer)

## Dependencies
This mod requires **[forgematica](https://modrinth.com/mod/forgematica)**, **[mafglib](https://modrinth.com/mod/mafglib)** and my fork of **[curtain](https://github.com/jxmm52547/Curtain)**

## How to use
The usage method is consistent with Litematica-Printer.

**Litematica Addition**:

`easyPlaceMode++` - Turns off Printer, but use Fake Rotation based easy place mode.

`verifierFindInventoryContents`    - Verifier will indicate blocks with items as 'wrong state' even if it has actually correct state. Useful for comparator-related stuff.

`printerOff` - Turns off Printer and use Normal easyplace.

`printerUseInventoryCache` - Uses Own inventory util that is little more stable for desync.


### Printer settings:
Now Settings have names with printer.

**Options that block other action being performed**:

`printerAllowInventoryOperations`  - Printer will match and input required stacks for hopper / dropper / chest / etc, mostly for filter setups.

>`inventoryCloseScreenAfterDone`  - Printer will close screen when filling is complete / or can't fill screen.

>`printerInventoryScreenWait`  - Printer will wait for this time(ms) after screen is open, to sync with server.

>`printerInventoryOperationRetry` - Printer will retry clicking to fill slots for this amount : recommended - 3-20

>`printerInventoryOperationAllowAllNamed` - Printer will allow other named items with same stack size, being used as filter items.

`printerBreakBlocks` - Printer will break ALL Extra or Wrong blocks within schematic. Can perform while placement, but not recommended.

> `printerBedrockBreaking` - Printer will break bedrock with HASTE 2, EFFICIENCY 5 , requires redstone torch, pistons.

> >`printerBedrockBreakingUseSlimeblock` - Printer will allow slime block placement, to find valid locations to break bedrock.

`printerFlippincactus` - Printer will flip blocks with cactus if enabled.

`printerClearFluids` - Printer will do Clearing actions , at default, Lava / Water (Cobblestone / Sponge).

>`printerClearFluidsUseCobblestone` - Printer will use cobblestone for water, instead of sponge.

>`printerClearSnowLayer` - Printer will use String to clear Snow layers.


**Main Features**:

`printerAccurateBlockPlacement` - Printer will use AccurateBlockPlacement Protocol, which is handled via carpet extra.

`printerFakeRotation` - Printer will use Fake Rotations to place blocks in wanted direction.

>`printerFakeRotationTicks` : Printer will wait for ticks(50ms per tick) after sending rotation packets.

>`printerFakeRotationLimitPerTicks` : If Printer waiting tick is set to 0, Printer will limit fake rotation per tick with this number.

>`disableSingleplayerPlacementHandling` : Printer will not modify placement direction based on fake rotation itself. recommended : false

**Debug**:

`ShowDebugMessages` - Printer will show debug messages, for reasons why block placement is failed /skipped /etc.

>`ShowDebugExtraMessages` - Printer will notify you about current block placements and fake rotations.


**Limiting**:

`easyPlaceModePrinterRangeX / Y / Z` - Printer will only place block expanded from starting raytrace pos.

`easyPlaceModePrinterMaxBlocks` - Max blocks / interactions that printer can perform per tick (or, easyplace actions)

`easyPlaceModeDelay` - Delay between printer actions - Recommended > 0.05 (50ms) to sync block states.

`easyPlaceModeHotbarOnly` - Printer will only use hotbar slots, which can be modified with fewer packets.

`printerSleepStackEmptied` - Printer will sleep when used stack is emptied, to sync / prevent some anticheats.

`easyPlaceModePrinterMaxItemChanges` - Printer will limit changing items actions, per cycle. **recommended value : 2**

`printerBreakIgnoresExtra` - Printer won't break extra-marked blocks. Applies to bedrock breaking and breaking.


**Redstone**:

`printerUsePumpkinpieForComposter` - Printer will use Pumpkin Pie to match composter levels.

`printerSmartRedstoneAvoid` - Printer will follow Observer / ETC Order when placing blocks. It will try to avoid observer updates.

`printerObserverAvoidAll` - Printer will avoid Observer being placed when its facing wrong stated-blocks, but will place Wall / etc for some cases.

`printerAvoidCheckOnlyPistons` - Printer will ignore Dispenser QC States.

`printerSuppressPushLimitPistons` - Printer will NOT place Pistons directly powered, but not extended : which means push limit is required.

`printerUseIceForWater` - Printer will place Ice where water source should be at.

`printerCheckWaterFirstForWaterlogged` - Printer will NOT place Waterlogged blocks, and wait until water is there.

`printerPlaceMinecart` - Printer will place Minecart for powered detector rails, It won't place it when TNT will be triggered.
