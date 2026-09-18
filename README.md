# Armor Layer

给 Minecraft 1.20.1 (Forge) 加一层**真正起作用的第二层护甲**。

Curios 的槽位平时只能装饰品——物品放进去只是个"装饰"，护甲值、附魔、套装被动一概不算。
这个 mod 把第二层护甲接回原版的三条判定链路，让它和主盔甲**同时生效、真正叠加**。

---

## 它解决什么问题

原版读取"玩家身上穿了什么"的地方是**分散**的，而且写法各不相同：

| 你要的效果 | 原版读的是 | 第二层能不能自动生效 |
|---|---|---|
| 护甲值 / 韧性 | `ItemStack#getAttributeModifiers` | ❌ 属性根本没注册到玩家身上 |
| 保护类附魔减伤（保护、神化高级保护） | `LivingEntity#getArmorSlots()` | ❌ 只返回原版四槽 |
| 荆棘反伤 | `EnchantmentHelper.getRandomItemWith`（按 `EquipmentSlot` 取） | ❌ 不走 `getArmorSlots()` |
| 水下速掘 / 水下呼吸 / 深海漫游者 | `EnchantmentHelper#getEnchantmentLevel` → `Enchantment#getSlotItems` | ❌ 里面硬编码 `entity.getItemBySlot(slot)` |
| 冰霜行者 / 灵魂疾行 / 迅捷潜行 | 同上 | ❌ |
| 套装被动（如 ProjectE 宝石盔甲的飞行） | 各 mod 自己写的槽位判等 | ❌ 通常写成 `player.getItemBySlot(FEET) == stack` |

所以"把盔甲放进 Curios 槽"这件事本身**不会**让任何一条生效。本 mod 逐条补上。

---

## 依赖

| 依赖 | 版本 | 必需 |
|---|---|---|
| Minecraft | 1.20.1 | ✅ |
| Forge | 47.4.20+ | ✅ |
| Curios | 5.14+ | ✅（提供槽位与同步） |
| Chained Curios | 1.1.0+ | ⭕ 提供 `underlayer_*` 四个槽；没有它就只是个空 mod |
| Expansion Core | 任意 | ⭕ 可选，给槽位扩容 |
| ProjectE | 任意 | ⭕ 可选，装了才会编译进兼容逻辑 |

槽位 ID 是 `underlayer_head` / `underlayer_chest` / `underlayer_legs` / `underlayer_feet`
（界面上显示的名字可能叫 "Chainlayer"，那是显示名，不影响）。

---

## 实现要点

### 1. 属性叠加：必须换掉 UUID

这是最容易踩的坑。`ArmorItem` 的护甲值修饰符用的是原版固定常量：

```java
LivingEntity.ARMOR_MODIFIER_UUID_PER_SLOT[部位]
```

同一个部位全游戏共用一个 UUID，而 `AttributeInstance` **按 UUID 存修饰符**，同 UUID 之间是
**替换**而不是相加。所以直接把物品自带的修饰符加进去，第二层和主盔甲会互相顶掉——
表现就是"穿上第二层有值，再穿主盔甲就没了"。

本 mod 为第二层生成专属的确定性 UUID：

```
armorlayer.<槽位下标>.<格号>.<属性注册名>.<同属性序号>
```

- 与原版、其他 mod 都不碰撞 → 两层真正相加
- 由内容确定生成（同属性多条词缀先按「属性名 → operation → 数值」排序再编号），
  每次算出来都一样 → 换装能准确移除，不残留

### 2. 附魔：一个注入点覆盖一整批

所有非保护性附魔最终都转发到同一个收口点：

```
EnchantmentHelper.getEnchantmentLevel(附魔, 实体)  →  Enchantment#getSlotItems
```

而 `getSlotItems` 的字节码写死了：

```java
for (EquipmentSlot slot : this.slots) {
    ItemStack stack = entity.getItemBySlot(slot);   // 完全不看 getArmorSlots()
    if (!stack.isEmpty()) map.put(slot, stack);
}
```

所以只要拦这一个方法，把第二层的物品并进候选集，整批附魔一起生效，不用逐个补。

取值方式是**取最大而不是相加**——原版语义本来就是"该附魔在所有可装备槽里取最高等级"，
第二层只是并进候选集。相加会让双份迅捷潜行变成两倍，破坏原版设计。

### 3. 荆棘：单独实现

原版荆棘在 `ThornsEnchantment#doPostHurt` 里，用
`EnchantmentHelper.getRandomItemWith(Enchantments.THORNS, entity)` 按 `EquipmentSlot`
逐槽取件，绕不开。本 mod 在 `LivingHurtEvent` 上另写一份，**数值逐字照抄原版**：

- 概率 = `random < 0.15 × 荆棘等级`
- 伤害 = `1 + random(4)`（等级 > 10 时 `level - 10`）
- 并用 `source.is(DamageTypes.THORNS)` 挡住"两套荆棘互相弹"的死循环

### 4. 属性会被原版清空，所以每 tick 补一次

`transient` 修饰符在换维度、重生、装备变更时会随属性重建一起被清掉，清掉就不再补，
第二层就"看起来没效果"了。本 mod 每 tick 检查一次，丢了补回。

### 5. ProjectE 兼容

宝石盔甲的飞行 / 自动上台阶 / 防火 / 全套减伤都卡在槽位判等上
（源码里写成 `player.getItemBySlot(原版槽) == stack`、`hasFullSet` 遍历 `Inventory.armor`）。
本 mod 用 `@Pseudo` + `targets` 字符串的方式打 mixin，**编译期不依赖 ProjectE**，
没装就自动跳过、不报错。

判定第二层物品时用**引用相等**而不是 `ItemStack.matches`：因为 ProjectE 的
`checkHotbarCurios` 会把快捷栏和主手的东西一起喂进同一个判定，若按内容相等判，
往快捷栏塞一双宝石靴子就能凭空学会飞行。

> 顺带一提：夜视、自动回血、迅疾（+100% 移速）、护腿跳跃推进**本来就能生效**，
> 因为它们分别在 `onArmorTick` 和 `getAttributeModifiers(FEET, ·)` 里，
> 这两个本 mod 已经在手动调用处理了。

### 6. 每 tick 驱动用 Forge 事件，不用 Mixin

`onArmorTick` 在 Forge 1.20.1 **没有任何调用点**，必须手动调。
而"每 tick 干活"这件事用 `LivingEvent.LivingTickEvent` 比注入 tick 方法更稳
（不受映射/refmap 影响，也不会因为注入静默失败而全盘失效）。

需要 remap 的 Mixin 一律写成「SRG 名 + `remap=false`」，比依赖 refmap 稳。

---

## 构建

需要 **JDK 17**。

```bash
./gradlew build
```

产物在 `build/libs/`。想指定本机 JDK 路径，请写在自己用户的
`~/.gradle/gradle.properties` 里，不要写进仓库的 `gradle.properties`：

```properties
org.gradle.java.installations.paths=<你的 JDK 17 路径>
```

---

## 已知限制

- **HUD 护甲条最多画 20 点**（10 图标 × 2）。超出的部分属性确实在，只是不显示。
  想知道真实数值用 `/attribute @s minecraft:generic.armor get`。
- **覆盖不到自定义读取路径的 mod**。走标准 `EnchantmentHelper` 的都能吃到；
  但如果某个 mod 自己写 `player.getItemBySlot(...)` 绕开这套 API，那还是漏。
- **Expansion Core 的扩容是 transient 的**（`addTransientSlotModifier`，不写 NBT），
  退出重进后槽位可能缩回 1 格。这是 Expansion Core 自身的行为。
  **卸核心前先把多出来的格子里的装备取出来**，缩容时溢出物品怎么处理不由本 mod 决定。
- ProjectE 主动技能（宝石胸爆炸、头盔闪电）走 `KeyPressPKT` 按键包 → `IExtraFunction`，
  链路里同样有槽位判等，**本 mod 暂未处理**。
- 夜视有个互相打架的点：`GemHelmet.onArmorTick` 在 NBT 开关**关闭**时会主动移除夜视，
  这是 ProjectE 自己的设计。两件都把 NightVision 开关打开即可。

---

## 许可

All Rights Reserved（见 `gradle.properties` 的 `mod_license`）。
如需其他授权方式请开 issue 说明。

---

## 提交前检查

本仓库带了密钥扫描钩子，启用一次即可：

```bash
git config core.hooksPath .githooks
```

之后每次提交会自动扫暂存区，发现疑似密钥或本机绝对路径就中止提交。
手动全量扫描：`python tools/check-secrets.py --all`
