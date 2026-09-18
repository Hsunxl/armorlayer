# Armor Layer

给 Minecraft 1.20.1 (Forge) 加一层**真正起作用的第二层护甲**。

> **English TL;DR** — Forge 1.20.1 mod. Turns the Curios "Chainlayer" slots into a real second
> armor layer: armor attributes, enchantments and set bonuses from those slots all apply **and
> stack** with your main armor. Requires **Curios** + **Chained Curios**. Put the jar into `mods/`.
> There is no prebuilt jar in this repo yet — build it yourself with `./gradlew build`.

---

## 它做什么

Chained Curios 在饰品栏里加了 4 个槽（头 / 胸 / 腿 / 脚）。原版只认自己那四个盔甲槽，
所以往里放盔甲只是个"装饰"——护甲值、附魔、套装被动一概不算。

装了本 mod 之后，放进这 4 个槽的盔甲会和主盔甲**同时生效、数值真正相加**：

| 效果 | 第二层 |
|---|---|
| 护甲值 / 韧性 | ✅ 与主盔甲相加 |
| 保护类附魔减伤（保护、神化高级保护…） | ✅ |
| 荆棘反伤 | ✅ |
| 水下速掘、水下呼吸、深海漫游者、冰霜行者、灵魂疾行、迅捷潜行 | ✅ |
| 空中速掘（Ensorcellation） | ✅ |
| ProjectE 宝石盔甲：夜视、回血、迅疾、飞行、自动上台阶、防火、全套减伤 | ✅ |

---

## 安装

### 第 1 步：准备前置 mod

| 前置 | 版本 | 说明 |
|---|---|---|
| **Minecraft** | 1.20.1 | |
| **Forge** | 47.x | |
| **Curios** | 5.14 或更高 | 硬依赖。没有它游戏会直接报缺失依赖，起不来 |
| **Chained Curios** | 1.1.0 或更高 | 提供那 4 个槽。不装的话本 mod 能加载，但没有槽位可放，等于空转 |

以下可选，不装也能跑：

- **Expansion Core** — 想给槽位扩容（每槽多放几件）才需要。
  注意只有**下界合金核心**会给 Chainlayer 这类"非标准槽"加格子。
- **ProjectE** — 装了才有宝石盔甲那套兼容。

### 第 2 步：拿到本 mod 的 jar

⚠️ **本仓库目前没有 Release，也没有打包好的 jar。** 需要自己编译，见文末
[从源码构建](#从源码构建)，产物是 `build/libs/armorlayer-1.0.0.jar`。

如果你已经从别处拿到了 `armorlayer-1.0.0.jar`，直接进第 3 步。

### 第 3 步：放进 mods 文件夹

把 `armorlayer-1.0.0.jar` 复制到游戏实例的 `mods/` 目录下：

```
<PCL/HMCL 的版本文件夹>/mods/armorlayer-1.0.0.jar
```

注意**不要**同时放两个不同的 armorlayer jar（比如手动改成 `-1.0.1.jar` 又留下旧的），
同一个 modid 出现两个文件会直接崩游戏。

### 第 4 步：完全重启游戏

必须彻底退出再启动。替换 jar 之后热重载不生效。

### 第 5 步：确认装好了

打开游戏目录下的 `logs/latest.log`，搜 `ArmorLayer`：

**装好了**会长这样：

```
[ArmorLayer] 已生效。第二层盔甲槽：underlayer_head, underlayer_chest, underlayer_legs, underlayer_feet（属性 / 附魔减伤 / 非保护性附魔 / 套装被动均生效）
```

**有问题**会打红字，明确告诉你哪一块没织进去：

```
[ArmorLayer] Mixin 部分未生效！PlayerMixin=..., CuriosApiMixin=..., DynamicStackHandlerMixin=..., EnchantmentHelperMixin=...
```

看到这条就把这一行连同上下文发到 issue 里。

---

## 使用

### 1. 打开饰品栏

默认按 **`G`**（Curios 的「Open/Close Curios Inventory」，可以在「选项 → 控制」里改键）。

### 2. 找到 Chainlayer 那 4 个槽

界面上会有一个叫 **Chainlayer** 的槽组，4 个格子，图标分别是头盔 / 胸甲 / 护腿 / 靴子形状。
槽位 ID 是 `underlayer_head` / `underlayer_chest` / `underlayer_legs` / `underlayer_feet`。

看不到的话：Curios 界面里有 **Toggle Visibility** 按钮，用它把这组槽显示出来。

### 3. 把盔甲拖进去

**部位要对得上**——头盔进 `Chainlayer Head`，胸甲进 `Chainlayer Chest`，以此类推。

本 mod 已经放开了入槽限制，任何对应部位的盔甲都能放（高价的 Allthemodium / Vibranium /
Unobtainium、ProjectE 宝石盔甲、Draconic 等等都行），不再局限于锁链甲。

### 4. 验证生效

游戏内直接读真实属性值：

```
/attribute @s minecraft:generic.armor get
/attribute @s minecraft:generic.armor_toughness get
```

数值应该等于「主盔甲 + 第二层」之和。日志里也会出现：

```
[ArmorLayer] 同步：<玩家名> 护甲值=X 韧性=Y 本次修饰符条目=N 第二层=[...]
[ArmorLayer] 第二层格数：underlayer_head=1 underlayer_chest=1 ...
```

> **别只看 HUD 的护甲图标。** 原版护甲条最多画 20 点（10 个图标 × 2），超出的部分
> 属性确实在，只是不显示。以 `/attribute` 命令的读数为准。

---

## 已知限制

- **HUD 护甲条上限 20 点**，超出不显示（属性本身正常生效）。
- **覆盖不到自定义读取路径的 mod。** 走标准 `EnchantmentHelper` 的附魔都能吃到；
  但如果有 mod 自己写 `player.getItemBySlot(...)` 绕开这套 API，那还是漏。
- **ProjectE 主动技能暂未处理**：宝石胸的爆炸、头盔的闪电走 `KeyPressPKT` 按键包 →
  `IExtraFunction`，链路里同样有槽位判等。
- **ProjectE 夜视会互相打架**：`GemHelmet.onArmorTick` 在 NBT 开关**关闭**时会主动移除夜视
  效果（ProjectE 自己的设计）。主槽和第二层都戴宝石头盔时，两件都把 NightVision 开关打开
  即可（用 ProjectE 的快捷键切）。
- **Expansion Core 的扩容是临时的**（`addTransientSlotModifier`，不写 NBT），
  退出重进后槽位可能缩回 1 格。这是 Expansion Core 自身的行为，本 mod 管不了。
  **缩容时多出来的格子里那些装备怎么办，不由本 mod 决定——所以卸核心、或重登之前，
  先把多出来的格子里的装备取出来。**
- **服务端也要装**，否则联机时进不去（本 mod 声明了 `MATCH_VERSION` 显示测试）。

---

## 常见问题

**Q：护甲图标没变多，是不是没生效？**
A：先看 `/attribute @s minecraft:generic.armor get` 的数字。HUD 上限 20 点，叠上去的部分不画。

**Q：装备放不进槽里？**
A：检查部位。头盔只能进 `Chainlayer Head`。另外确认槽组在界面上是显示状态。

**Q：装了跟没装一样？**
A：翻 `logs/latest.log`。如果没有 `[ArmorLayer]` 开头的任何一行，说明 jar 没被加载
（放错目录 / 装了 Forge 以外的加载器 / 同时放了两个同 modid 的 jar）。

**Q：第二层的荆棘会不会和主槽的原版荆棘重复计算？**
A：不会。原版那套按 `EquipmentSlot` 逐槽取件，读不到第二层；本 mod 的实现只统计第二层。

**Q：和神化（Apotheosis）的词缀冲突吗？**
A：不冲突。词缀走 `ItemStack#getAttributeModifiers`，本 mod 已经在同步属性时覆盖。

---

## 从源码构建

需要 **JDK 17**。

```bash
./gradlew build
```

产物：`build/libs/armorlayer-1.0.0.jar`

如果 gradle 找不到 JDK，把路径写在你**自己用户**的 `~/.gradle/gradle.properties` 里，
不要写进仓库的 `gradle.properties`：

```properties
org.gradle.java.installations.paths=<你的 JDK 17 路径>
```

开发时另开一个 Forge 1.20.1 实例、把 `build/libs/*.jar` 丢进它的 `mods/` 调试即可。

---

## 实现原理

各条链路的原版实现细节、为什么必须换掉属性 UUID、走过的弯路，都记在
[`docs/implementation.md`](docs/implementation.md)。

---

## 提交前检查

本仓库带了密钥扫描钩子，启用一次即可：

```bash
git config core.hooksPath .githooks
```

之后每次提交会自动扫暂存区，发现疑似密钥或本机绝对路径就中止提交。
手动全量扫描：`python tools/check-secrets.py --all`

---

## 许可

All Rights Reserved（见 `gradle.properties` 的 `mod_license`）。
如需其他授权方式请开 issue 说明。
