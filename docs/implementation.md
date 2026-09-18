# 实现说明

这份文档记录 Armor Layer 的各项技术细节：原版为什么不让第二层生效、每一处是怎么接上的、
以及踩过哪些坑。**普通使用不需要读它**，用法见 `README.md`。

---

## 问题在哪：原版读取"玩家穿了什么"的地方是分散的

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

## 1. 属性叠加：必须换掉 UUID

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
- **种子里的"格号"不能省**：Expansion Core 扩容后同一部位会有多格，
  不加格号，同部位的三件头盔会共用同一个 UUID 而互相顶掉

## 2. 附魔：一个注入点覆盖一整批

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

另外加了一道 `canEnchant` 过滤，避免"第二层放护腿、护腿带着靴子专属附魔"这种异常组合
凭空生效。

### Ensorcellation 的「空中速掘」

原版没有这个附魔。它来自 Ensorcellation，判定在 `CommonEvents.handleBreakSpeedEvent`，
取等级走 CoFH Core 的 `getMaxEquippedEnchantmentLevel`，而那个方法反汇编出来最后一行
**就是** `EnchantmentHelper.m_44836_(enchantment, entity)`——正好是本 mod 注入的方法，
所以自动兼容，不需要额外处理。

## 3. 荆棘：单独实现

原版荆棘在 `ThornsEnchantment#doPostHurt` 里，用
`EnchantmentHelper.getRandomItemWith(Enchantments.THORNS, entity)` 按 `EquipmentSlot`
逐槽取件，绕不开。本 mod 在 `LivingHurtEvent` 上另写一份，**数值逐字照抄原版**：

- 概率 = `random < 0.15 × 荆棘等级`
- 伤害 = `1 + random(4)`（等级 > 10 时 `level - 10`）
- 并用 `source.is(DamageTypes.THORNS)` 挡住"两套荆棘互相弹"的死循环

只统计第二层，不会和主槽的原版荆棘重复计算。

## 4. 属性会被原版清空，所以每 tick 补一次

`transient` 修饰符在换维度、重生、装备变更时会随属性重建一起被清掉，清掉就不再补，
第二层就"看起来没效果"了。本 mod 每 tick 检查一次，丢了补回。

## 5. ProjectE 兼容

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

## 6. 每 tick 驱动用 Forge 事件，不用 Mixin

`onArmorTick` 在 Forge 1.20.1 **没有任何调用点**，必须手动调。
而"每 tick 干活"这件事用 `LivingEvent.LivingTickEvent` 比注入 tick 方法更稳
（不受映射/refmap 影响，也不会因为注入静默失败而全盘失效）。

需要 remap 的 Mixin 一律写成「SRG 名 + `remap=false`」，比依赖 refmap 稳。

## 7. 支持 Expansion Core 扩容

Expansion Core 在装备"扩展核心"时给其他槽加格子（`addTransientSlotModifier`）。
本 mod 的模型是 **「槽位 + 格号」二维**，每 tick 现读 `getSlots()`，有几格就处理几件，
不写死数量。

它自身的行为值得注意：

- 它的 `CURIOS_BASE_SLOTS` 常量是 `ring, necklace, hand, hands, head, belt, back, charm, feet`，
  **不含 `underlayer_*`**。只有**下界合金核心**会给所有"非标准槽"额外 +1。
- 扩容是 transient 的（不写 NBT），退出重进后槽位可能缩回 1 格。

---

## 附：走过的弯路

| 现象 | 真实原因 |
|---|---|
| 能穿进槽但护甲值不涨 | 手写 refmap 结构写错（少了 mixin config 那层），映射从未生效，注入被静默跳过。改用「SRG 名 + `remap=false`」后解决 |
| 日志误报"Mixin 未生效" | 自检用 `startsWith("armorlayer$")`，而 Mixin 生成的名字是 `handler$zzz000$armorlayer$xxx`，前缀不匹配。改用 `contains` |
| 护甲值被主盔甲覆盖 | 属性修饰符 UUID 与 `ARMOR_MODIFIER_UUID_PER_SLOT` 撞车（见第 1 节） |
| 只有第一格装备生效 | 取值写死 `findCurio(槽, 0)`，只看每槽第 0 格（见第 7 节） |
