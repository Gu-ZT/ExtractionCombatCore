# 地图作者使用说明

在 Minecraft 1.21.8 客户端和服务端安装本模组与 AnvilLib 2.0.0，进入创造模式，在「搜刮演示套件」页取得野外随身包、通电维修柜、钥匙和译码器。

## 试玩维修柜

1. 按 `B` 打开收纳界面，右键背包装备它。在生存物品栏也可以把包放入人物预览右上方的独立背包位。背包键和挑战键都可在游戏的按键设置中修改。
2. 放置维修柜，在柜子旁放一个红石块。用维修钥匙或译码器交互并选择对应路线。钥匙路线长按 20 刻，成功后消耗一把钥匙。译码器自然破译需 400 刻，在标记范围内按挑战键可缩短等待。
3. 第一叠铁粒显露后即可双击取出。等挂钩出现后，抓住它的横杆，向背包格子的右缘移动并释放。五格长的挂钩可以竖放入四格宽、五格高的主仓，小件仍可放进凹口。
4. 另放一个密码柜、音频摩斯柜、图案摩斯柜或滚动锁柜，即可分别试玩其他锁。示例密码是 `0472`，两种摩斯柜的明文均为 `SOS`。敞开工具架会立即显示全部内容。

## 玩家库存配置

服务端实例中的 `config/extractioncombatcore-server.toml` 管理库存限制。主背包与快捷栏可独立设置。禁用槽中的旧物可以取回，护甲和副手仍可使用。

```toml
vanilla_main_inventory_enabled = true
enabled_hotbar_slots = 9
```

快捷栏数量范围为 0 至 9，从左向右开放。地上拾取的物品优先进入允许使用的原版槽，其余尝试直接放入装备的背包。超出容量的部分留在原地。

## 数据包与指令

Minecraft 1.21.8 的数据包在根目录使用 `pack.mcmeta`，将 `pack.pack_format` 设为 `81`。客户端资源包的格式为 `64`。要覆盖演示物品的手持模型，请在 `assets/<命名空间>/items/<物品名>.json` 设置 `model.type` 为 `minecraft:model`，并让 `model.model` 指向对应的模型资源。

在 `data/<命名空间>/extraction/` 下分别创建 `backpacks`、`containers`、`items` 和 `qualities`。定义 ID 使用命名空间和文件相对路径。作者可通过下表中的管理员命令取得绑定指定定义的物品或设置机关。背包和柜子创建后保留自己的布局；测试新布局时请重新领取和放置实例。

| 指令 | 用途 |
|---|---|
| `/extraction backpack extractioncombatcore:field_pack` | 领取绑定野外随身包定义的背包 |
| `/extraction cabinet extractioncombatcore:service_cabinet` | 领取已绑定的维修柜方块 |
| `/extraction flag example:power_room true` | 启用世界机关，传入 `false` 可复位 |

机关条件写作 `{"type":"unlocked","flag":"example:power_room"}`。同一路线的 `requires` 必须同时满足，`steps` 按顺序完成。钥匙或标签条件可分别使用 `consume` 扣除一件物品、使用 `damage` 扣除工具耐久。服务端只在整条路线成功时结算代价。

固定柜子支持创造移除。服务端保存战利品、待放入物品、解锁状态及每位玩家的发现记录。资源包可分别提供完整网格图和原版单格图，缺图时只有对应显示场合回退。[数据包与玩法规格](搜刮玩法设计.md)

## 附属模组接入

使用这些 API 的附属模组应依赖本游戏版本对应的 ExtractionCombatCore 0.2.0 或兼容版本。

### 给物品添加背包

物品栈上的 `extractioncombatcore:backpack` 数据组件保存背包定义 ID。将布局放在 `data/example/extraction/backpacks/field_pack.json`，注册物品时给 `Item.Properties` 设置默认组件即可使用背包位、自动拾取和网格收纳。也可以调用 `stack.set(ExtractionCore.BACKPACK.get(), layout)` 给单件现有物品指定布局。

```java
var layout = ResourceLocation.fromNamespaceAndPath("example", "field_pack");
properties.stacksTo(1).component(ExtractionCore.BACKPACK.get(), layout);
```

附属模组自行选择右键、按键或其它交互作为打开方式。在服务端主线程调用 `LootSessions.openPack(player, slot)`，传入该玩家实际持有背包的槽位。主库存使用 0 至 35，副手使用 40，独立背包位使用 `GameAccess.EQUIPMENT_SLOT`。带组件的物品栈拆成单件后可以建立独立库存。既有内置背包沿用原实例 UUID 和已保存的布局。

### 注册开柜条件与小游戏

在附属模组中创建两个 `DeferredRegister`，并分别调用 `.register(modBus)` 绑定自己的模组事件总线。条件和小游戏使用各自的注册表，可以独立复用。

```java
static final DeferredRegister<AccessCondition> CONDITIONS =
    DeferredRegister.create(LockTypes.CONDITIONS_KEY, "example");
static final DeferredRegister<PuzzleType> PUZZLES =
    DeferredRegister.create(LockTypes.PUZZLES_KEY, "example");

static {
    CONDITIONS.register("compass", () ->
        (player, pos, rule) -> player.getOffhandItem().is(Items.COMPASS));
    PUZZLES.register("dial", Dial::new);
}
```

上例中的 `Dial` 实现 `PuzzleType`。其 `start(player, pos, rule)` 为每名玩家的每次尝试创建一个独立 `PuzzleSession`。会话的 `view()` 返回可以公开给玩家的 NBT，`input(input)` 校验玩家操作，`tick()` 可推进计时。后两者返回 `true` 时核心进入下一步。条件的 `test` 可能在预览、挑战中和解锁时重复执行，须只读。两种类型都可以覆写 `validate(rule, path)` 校验数据包字段，以及 `description(rule)` 提供可公开的路线说明。

只在物理客户端初始化时注册对应控件。控件通过 `context.view()` 读取服务端最新题面，通过 `context.send(...)` 提交玩家的操作。例如，拨盘按钮提交一次转动。

```java
PuzzleWidgets.register(ResourceLocation.fromNamespaceAndPath("example", "dial"),
    (context, x, y, width, height) ->
        Button.builder(Component.literal("转动拨盘"), button -> {
            CompoundTag input = new CompoundTag();
            input.putBoolean("turn", true);
            context.send(input);
        }).bounds(x, y, width, 20).build());
```

容器定义中的路线按完整 ID 引用附属类型。内置类型可继续写作 `hold` 等短名，也可写作 `extractioncombatcore:hold`。新定义引用未知类型会报告出错字段。已保存柜子遇到被移除的附属类型时，对应开锁路线停用。

```json
{
  "requires": [{"type": "example:compass"}],
  "steps": [{"type": "example:dial", "turns": 2}]
}
```

完整的拨盘服务端实现、客户端按钮和柜子定义可见[服务端示例](../src/gameTest/java/dev/extraction/core/server/AddonLockTypes.java)、[客户端示例](../src/gameTest/java/dev/extraction/core/client/ClientPuzzleChecks.java)和[容器 JSON](../src/gameTest/resources/data/example/extraction/containers/dial_cabinet.json)。这组示例随开发测试加载。
