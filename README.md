# Armor Layer

给 Minecraft 1.20.1 (Forge) 加上**真正意义上的第二层护甲** ～～不再是装饰性盔甲～～。

> **English TL;DR** — Forge 1.20.1 mod. Turns the Curios "Chainlayer" slots into a real second
> armor layer: armor attributes, enchantments and set bonuses from those slots all apply **and
> stack** with your main armor. **Download `ArmorLayer-v1.0.0.zip` from
> [Releases](../../releases/latest), unzip it, and drop the jars from its `mods/` folder into your
> game's `mods/` folder.** Requires Curios + Chained Curios (both included in the zip).

---

## 它做了什么

Chained Curios模组 在Curios饰品栏里加入了 4 个盔甲槽（头 / 胸 / 腿 / 脚）。但是往里放的盔甲只是个"装饰"——护甲值、附魔、套装被动都不会起效。

本模组依赖Chained Curios新加入的装备槽前置，使此第二套盔甲的盔甲会和主盔甲**同时生效、数值真正相加**：

| 效果 | 第二层 |
|---|---|
| 护甲值 / 韧性 | ✅ 与主盔甲相加 |
| 保护类附魔减伤（保护、神化高级保护…） | ✅ |
| 荆棘反伤 | ✅ |
| 水下速掘、水下呼吸、深海漫游者、冰霜行者、灵魂疾行、迅捷潜行 | ✅ |
| 空中速掘（Ensorcellation） | ✅ |
| ProjectE模组兼容：夜视、回血、迅疾、飞行、自动上台阶、防火、全套减伤 | ✅ |

---

## 安装（4 步）

### 第 1 步：下载

到 [Releases](../../releases/latest) 页面下载 **`ArmorLayer-v1.0.0.zip`**。

这个包里**前置 mod 全都齐了**，不用再去别处找。解压后会看到一个 `mods` 文件夹和一份
「先看我-安装说明.txt」。

（只想更新本 mod 的话，单独下 `armorlayer-1.0.0.jar` 也行。）

### 第 2 步：把 jar 复制进游戏的 mods 文件夹

先找到你游戏的 `mods` 文件夹：

- **PCL**：左边点一下你启动的版本 → 「打开文件夹」
- **HMCL**：版本列表里那一行右边的文件夹图标
- **官方启动器**：`.minecraft` 文件夹

然后把解压出来的 `mods` 文件夹里那 **5 个 `.jar` 全部选中、复制、粘贴进去**。

弹出「已有同名文件」就选 **替换**。

全部丢进去最省事，用不到的不会影响游戏。

### 第 3 步：启动游戏

如先前游戏处于打开状态 必须**完全退出再启动**。

### 第 4 步：确认安装成功
'

打开游戏目录下的 `logs/latest.log`，搜 `ArmorLayer`：

**装好了**会长这样：

```
[ArmorLayer] 已生效。第二层盔甲槽：underlayer_head, underlayer_chest, underlayer_legs, underlayer_feet（属性 / 附魔减伤 / 非保护性附魔 / 套装被动均生效）
```

**有问题**会打红字，明确告诉你哪一块加载进去：

```
[ArmorLayer] Mixin 部分未生效！PlayerMixin=..., CuriosApiMixin=..., DynamicStackHandlerMixin=..., EnchantmentHelperMixin=...
```

看到这条就把这一行连同上下文发到 issue 里。
（～～或者问豆包～～）


### 包里的东西 / 环境要求

| 文件 | 必需？ | 说明 |
|---|---|---|
| `armorlayer-1.0.0.jar` | ✅ | 本 mod |
| `curios-forge-5.14.1+1.20.1.jar` | ✅ | 前置模组|
| `chainedcurios-1.1.0.jar` | ✅ |  本模组基于该模组路线优化升级，使第二套盔甲成为可能|


环境：**Minecraft 1.20.1 + Forge 47.x**。

---

## 使用

### 1. 打开饰品栏

默认按 **`G`**（Curios 的「Open/Close Curios Inventory」，可以在「选项 → 控制」里改键）。

### 2. 找到 Chainlayer 那 4 个槽

界面上会有一个叫 **Chainlayer** 的槽组，4 个格子，图标分别是头盔 / 胸甲 / 护腿 / 靴子形状。

看不到的话：Curios 界面里有 **Toggle Visibility** 按钮，用它把这组槽显示出来。

### 3. 把盔甲拖进去

（本模组目前只兼容了ProjectE，Allthemodium非原版盔甲）

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

> 原版护甲条最多画 20 点（10 个图标），超出的部分
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

**Q：装备放不进槽里？**
A：可能是模组还未兼容，请提交问题，我做后续兼容

**Q：装了跟没装一样？**
A：翻 `logs/latest.log`。如果没有 `[ArmorLayer]` 开头的任何一行，说明 jar 没被加载
（放错目录 / 装了 Forge 以外的加载器 / 同时放了两个同 modid 的 jar）。

**Q：第二层的荆棘会不会和主槽的原版荆棘重复计算？**
A：不会。原版那套按 `EquipmentSlot` 逐槽取件，读不到第二层；本 mod 的实现只统计第二层。

**Q：和神化（Apotheosis）的词缀冲突吗？**
A：不冲突。词缀走 `ItemStack#getAttributeModifiers`，本 mod 已经在同步属性时覆盖。

---

## 附录：从源码构建

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

> **比对版本时别直接比 jar 的整体 md5。** 构建会往 `META-INF/MANIFEST.MF` 里写入
> 构建时间（`Implementation-Timestamp`），所以每次构建出来的整个 jar 哈希都不一样，
> 但所有 `.class` 是逐字节相同的。要比对就解包出来比 class。

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
