package com.qingqi.armorlayer;

import com.google.common.collect.HashMultimap;
import com.mojang.logging.LogUtils;
import com.google.common.collect.Multimap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import top.theillusivec4.curios.common.inventory.DynamicStackHandler;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 第二层盔甲。
 *
 * 存储复用 Curios 的 underlayer_head / underlayer_chest / underlayer_legs / underlayer_feet
 * 四个槽（整合包里由 Chained Curios 提供，GUI 与同步都是现成的）。
 *
 * 每个槽的格数**不固定**：Chained Curios 注册的是各 1 格，而 Expansion Core 这类 mod
 * 会用 Curios 的 addTransientSlotModifier 扩容（它的下界合金核心给所有非标准槽各 +1）。
 * 所以这里一律按「槽位 + 格号」建模，有几格就处理几件，不写死数量。
 *
 * 本 mod 负责把这一层接回原版盔甲的三条链路：
 *
 *   1. 属性（护甲值 / 韧性 / 击退抗性）-> sync() 里手动增删 transient modifiers
 *   2. 附魔减伤（保护、荆棘、神化的高级保护）-> 由 PlayerMixin 改 getArmorSlots() 自动带上
 *   3. 套装被动（IForgeItem#onArmorTick，例如 ProjectE 夜视）-> sync() 里手动触发
 */
@Mod(ArmorLayer.MODID)
@Mod.EventBusSubscriber(modid = ArmorLayer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ArmorLayer {

    public static final String MODID = "armorlayer";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Curios 槽位，顺序固定为 头盔 / 胸甲 / 护腿 / 靴子 */
    public static final String[] CURIO_SLOTS = {
            "underlayer_head",
            "underlayer_chest",
            "underlayer_legs",
            "underlayer_feet"
    };

    /** 与 CURIO_SLOTS 一一对应的原版装备槽，取属性修饰符时需要 */
    public static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    /**
     * 第二层里的一格：物品栈 + 所属槽位 + 该槽内的格号。
     *
     * 「槽位」决定用哪个部位去取物品自带的属性修饰符；
     * 「格号」是槽位扩容后才存在的概念 —— Expansion Core 之类的 mod 会通过
     * Curios 的 addTransientSlotModifier 把 underlayer_head 这类槽从 1 格加到 3 格，
     * 三件头盔要各算各的属性，所以必须能区分到格。
     */
    public record LayerItem(ItemStack stack, int slotIndex, int cellIndex) {

        /** 这一格对应的原版装备槽（HEAD / CHEST / LEGS / FEET） */
        public EquipmentSlot equipmentSlot() {
            return EQUIPMENT_SLOTS[slotIndex];
        }

        /** 日志与比较用的可读标识，例如 underlayer_head#1 */
        public String label() {
            return CURIO_SLOTS[slotIndex] + "#" + cellIndex;
        }
    }

    /** 上一 tick 记录的第二层（含部位信息，撤修饰符时要用它算 UUID） */
    private static final Map<LivingEntity, List<LayerItem>> LAST_KNOWN = new WeakHashMap<>();

    /** 只在首次调用时打一条日志，用来确认 tick 注入有没有生效 */
    private static final java.util.concurrent.atomic.AtomicBoolean SYNC_CALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ArmorLayer() {
        // 自检：Mixin 有没有真的织进去。织进去的话 Player 上会多出 armorlayer$ 开头的方法。
        boolean playerApplied = hasMixin(Player.class);
        boolean curiosApplied = hasMixin(CuriosApi.class);
        boolean handlerApplied = hasMixin(DynamicStackHandler.class);
        boolean enchantApplied = hasMixin(EnchantmentHelper.class);

        if (playerApplied && curiosApplied && handlerApplied && enchantApplied) {
            LOGGER.info("[ArmorLayer] 已生效。第二层盔甲槽：{}（属性 / 附魔减伤 / 非保护性附魔 / 套装被动均生效）",
                    String.join(", ", CURIO_SLOTS));
        } else {
            LOGGER.error("[ArmorLayer] Mixin 部分未生效！PlayerMixin={}, CuriosApiMixin={}, " +
                            "DynamicStackHandlerMixin={}, EnchantmentHelperMixin={}。" +
                            "对应缺失的那部分功能不会工作。请检查游戏日志里 Mixin 相关的报错。",
                    playerApplied, curiosApplied, handlerApplied, enchantApplied);
        }
    }

    /**
     * 自检：织进去之后目标类上会多出带 armorlayer$ 的方法。
     * Mixin 生成的名字形如 handler$zzz000$armorlayer$getArmorSlots，前面有 handler$ 前缀，
     * 所以只能用 contains，不能用 startsWith（第一版就是这里写错，导致明明生效却报"未生效"）。
     */
    private static boolean hasMixin(Class<?> target) {
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().contains("armorlayer$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 某件物品能否放进第二层的指定槽位。
     *
     * 规则很简单：物品的天然装备部位和槽位对得上就允许（头盔进 underlayer_head，以此类推）。
     * 由 CuriosApiMixin 调用。
     */
    public static boolean allowsSecondLayer(String slotId, ItemStack stack) {
        int index = -1;
        for (int i = 0; i < CURIO_SLOTS.length; i++) {
            if (CURIO_SLOTS[i].equals(slotId)) {
                index = i;
                break;
            }
        }
        if (index < 0 || stack.isEmpty()) {
            return false;
        }
        // 非盔甲物品会返回 MAINHAND / OFFHAND，自然匹配不上
        EquipmentSlot natural = LivingEntity.getEquipmentSlotForItem(stack);
        boolean allowed = natural == EQUIPMENT_SLOTS[index];

        // 诊断日志。GUI 每帧都会在客户端调用这里，刷屏会把日志撑爆，
        // 所以只在服务端打印，并且同样内容 1 秒内只打一次。
        if (!isClientCall() && shouldLog(slotId + "|" + BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            LOGGER.info("[ArmorLayer] 入槽判定：槽位={} 物品={} 天然部位={} -> {}",
                    slotId, BuiltInRegistries.ITEM.getKey(stack.getItem()), natural, allowed);
        }
        return allowed;
    }

    /** 日志节流：同一条内容 1 秒内只打一次 */
    private static final Map<String, Long> LOG_TIMES = new java.util.HashMap<>();

    private static boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = LOG_TIMES.get(key);
        if (last != null && now - last < 1000L) {
            return false;
        }
        LOG_TIMES.put(key, now);
        return true;
    }

    /** 当前是否处于客户端（Render thread）。服务端逻辑不该在客户端跑。 */
    private static boolean isClientCall() {
        return Thread.currentThread().getName().equals("Render thread");
    }

    /**
     * 原版锁链甲由 Chained Curios 自己处理（它对锁链甲是原生支持的，会自己加属性、
     * 算附魔减伤、做耐久联动）。这里跳过，避免同一件盔甲的属性被计算两次。
     */
    private static boolean isVanillaChainmail(ItemStack stack) {
        return stack.is(Items.CHAINMAIL_HELMET)
                || stack.is(Items.CHAINMAIL_CHESTPLATE)
                || stack.is(Items.CHAINMAIL_LEGGINGS)
                || stack.is(Items.CHAINMAIL_BOOTS);
    }

    /**
     * 读取第二层所有格子（含空格），按「槽位顺序 → 格号」排列。
     *
     * 槽位数量是**动态**的：Chained Curios 注册的 underlayer_* 各 1 格，
     * 而 Expansion Core 这类 mod 会用 Curios 的 addTransientSlotModifier
     * 给玩家加格数（它的下界合金核心会给所有非标准槽各 +1）。
     * 所以每 tick 现读一次 getSlots()，不能把 1 格当成常量写死。
     */
    public static List<LayerItem> getSecondLayer(LivingEntity entity) {
        List<LayerItem> result = new ArrayList<>();
        ICuriosItemHandler inventory =
                CuriosApi.getCuriosInventory(entity).resolve().orElse(null);

        for (int slotIndex = 0; slotIndex < CURIO_SLOTS.length; slotIndex++) {
            ICurioStacksHandler handler = inventory == null
                    ? null
                    : inventory.getStacksHandler(CURIO_SLOTS[slotIndex]).orElse(null);

            if (handler == null) {
                // 拿不到槽位信息（槽没注册 / Curios 未就绪）时退回「每槽一格」
                result.add(new LayerItem(ItemStack.EMPTY, slotIndex, 0));
                continue;
            }

            IDynamicStackHandler stacks = handler.getStacks();
            int size = Math.max(stacks.getSlots(), 1);
            for (int cell = 0; cell < size; cell++) {
                result.add(new LayerItem(stacks.getStackInSlot(cell), slotIndex, cell));
            }
        }
        return result;
    }

    /**
     * 第二层所有格子的物品栈（扁平）。
     * 给只关心"有哪几件"的下游用：PlayerMixin 拼 getArmorSlots()、
     * 附魔等级统计、荆棘统计。
     */
    public static List<ItemStack> getSecondLayerStacks(LivingEntity entity) {
        List<LayerItem> items = getSecondLayer(entity);
        List<ItemStack> stacks = new ArrayList<>(items.size());
        for (LayerItem item : items) {
            stacks.add(item.stack());
        }
        return stacks;
    }

    /**
     * 每 tick 的驱动点。
     *
     * 这里用 Forge 的 LivingTickEvent，而不是 Mixin 注入 Player#tick：
     * 之前用 Mixin 注入，结果因为手写 refmap 结构写错（少了 mixin config 那一层），
     * 运行时映射不生效、注入被静默跳过，sync() 一次都没跑过。
     * Forge 事件完全不依赖 Mixin 和 refmap，稳定得多。
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof Player) {
            sync(event.getEntity());
        }
    }

    /**
     * 每 tick 调用：手动触发 onArmorTick，并同步第二层的属性修饰符。
     *
     * onArmorTick 两边都调。原版（1.19 及以前）就是客户端和服务端各调一次，
     * mod 作者按这个语义写逻辑 —— ProjectE 的 GemLegs 就是明证：它的"跳跃推进"
     * 写在 isClientSide 分支里，只在客户端跑；服务端分支做的是潜行范围伤害。
     * 只在服务端调的话，这类客户端侧的效果永远不触发。
     *
     * 属性那部分仍然只在服务端做：客户端的属性值由服务端同步包决定，
     * 在客户端加修饰符只会造成两边数据不一致。
     */
    public static void sync(LivingEntity entity) {
        List<LayerItem> current = getSecondLayer(entity);

        // Forge 1.20.1 没有任何地方自动调用 IForgeItem#onArmorTick，得我们自己补。
        if (entity instanceof Player player) {
            for (LayerItem item : current) {
                if (!item.stack().isEmpty()) {
                    item.stack().getItem().onArmorTick(item.stack(), entity.level(), player);
                }
            }
        }

        if (entity.level().isClientSide) {
            return;
        }

        // 只要日志里出现这一行，就说明 tick 驱动（Forge 事件）是通的。
        if (SYNC_CALLED.compareAndSet(false, true)) {
            LOGGER.info("[ArmorLayer] sync() 已开始运行（LivingTickEvent 驱动正常）");
        }
        logSlotShape(current);

        List<LayerItem> last = LAST_KNOWN.computeIfAbsent(entity, e -> new ArrayList<>());

        boolean changed = false;
        boolean refilled = false;
        int modifierCount = 0;

        // 格数或内容有变化就整体重建：撤掉旧的（必须用旧的 LayerItem 才能算回原 UUID），
        // 再加上新的。装/拆 Expansion Core 导致槽位扩容缩容时也会走到这里。
        boolean sameShape = last.size() == current.size();
        if (sameShape) {
            for (int i = 0; i < current.size(); i++) {
                if (!ItemStack.matches(last.get(i).stack(), current.get(i).stack())) {
                    sameShape = false;
                    break;
                }
            }
        }

        if (!sameShape) {
            for (LayerItem old : last) {
                if (!old.stack().isEmpty() && !isVanillaChainmail(old.stack())) {
                    entity.getAttributes().removeAttributeModifiers(modifiersFor(old));
                }
            }
            for (LayerItem item : current) {
                if (!item.stack().isEmpty() && !isVanillaChainmail(item.stack())) {
                    Multimap<Attribute, AttributeModifier> modifiers = modifiersFor(item);
                    modifierCount += modifiers.size();
                    entity.getAttributes().addTransientAttributeModifiers(modifiers);
                }
            }
            LAST_KNOWN.put(entity, copyOf(current));
            changed = true;
        } else {
            // 内容没变，但要确认修饰符还在。
            // transient 修饰符会被原版清空（换维度、重生、装备变更都可能触发
            // 属性重建），一旦被清掉就不再补，第二层就"看起来没效果"。
            // 这里每 tick 检查，丢了就补回去。
            for (LayerItem item : current) {
                if (item.stack().isEmpty() || isVanillaChainmail(item.stack())) {
                    continue;
                }
                Multimap<Attribute, AttributeModifier> modifiers = modifiersFor(item);
                if (!modifiers.isEmpty() && !isApplied(entity, modifiers)) {
                    entity.getAttributes().addTransientAttributeModifiers(modifiers);
                    refilled = true;
                }
            }
        }

        // 诊断日志：状态变化时立刻打一条；否则每 5 秒打一条，方便确认是否真的在跑。
        boolean hasAny = current.stream().anyMatch(item -> !item.stack().isEmpty());
        if (changed || refilled) {
            LOGGER.info("[ArmorLayer] 同步：{} 护甲值={} 韧性={} 本次修饰符条目={} 第二层={}{}",
                    entity.getName().getString(),
                    round(entity.getAttributeValue(Attributes.ARMOR)),
                    round(entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS)),
                    modifierCount,
                    describe(current),
                    refilled ? "（检测到修饰符丢失，已补回）" : "");
        } else if (hasAny && entity.tickCount % 100 == 0) {
            LOGGER.info("[ArmorLayer] 状态：{} 护甲值={} 韧性={} 第二层={}",
                    entity.getName().getString(),
                    round(entity.getAttributeValue(Attributes.ARMOR)),
                    round(entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS)),
                    describe(current));
        }
    }

    /** 上一次打印过的槽位格数，只在变化时打日志（装/拆扩容核心时会变） */
    private static volatile String lastSlotShape = "";

    /**
     * 打印每个 underlayer 槽当前的格数。
     *
     * Chained Curios 默认各 1 格；Expansion Core 的下界合金核心会把非标准槽各 +1，
     * 这里打出来就能直接确认扩容 mod 有没有生效、当前是几倍。
     */
    private static void logSlotShape(List<LayerItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int slotIndex = 0; slotIndex < CURIO_SLOTS.length; slotIndex++) {
            int cells = 0;
            for (LayerItem item : items) {
                if (item.slotIndex() == slotIndex) {
                    cells++;
                }
            }
            if (slotIndex > 0) {
                sb.append(' ');
            }
            sb.append(CURIO_SLOTS[slotIndex]).append('=').append(cells);
        }
        String shape = sb.toString();
        if (!shape.equals(lastSlotShape)) {
            lastSlotShape = shape;
            LOGGER.info("[ArmorLayer] 第二层格数：{}", shape);
        }
    }

    private static List<LayerItem> copyOf(List<LayerItem> items) {
        List<LayerItem> copy = new ArrayList<>(items.size());
        for (LayerItem item : items) {
            copy.add(new LayerItem(item.stack().copy(), item.slotIndex(), item.cellIndex()));
        }
        return copy;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String describe(List<LayerItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            LayerItem item = items.get(i);
            sb.append(item.label()).append('=');
            sb.append(item.stack().isEmpty()
                    ? "空"
                    : BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString());
        }
        return sb.append("]").toString();
    }

    /**
     * 第二层专用修饰符 UUID。
     *
     * 必须自己造，不能沿用物品自带的：
     * ArmorItem 的护甲值 / 韧性修饰符用的 UUID 是原版固定的
     * ARMOR_MODIFIER_UUID_PER_SLOT[部位]，和玩家主盔甲**完全同一个 UUID**。
     * AttributeInstance 内部是 Map<UUID, AttributeModifier>，同 UUID 是替换而非累加，
     * 于是谁后写谁赢：先穿第二层再穿主盔甲，主盔甲把第二层顶掉（反之亦然）。
     * 表现就是"单穿有数值，两件一起穿只剩主盔甲那点护甲值"。
     *
     * 这里按「槽位 + 格号 + 属性注册名 + 同属性序号」确定性生成 UUID：
     *   - 与原版、其他 mod 的 UUID 不会碰撞 => 主盔甲和第二层真正相加
     *   - 同一格里的同一件盔甲每次算出来都一样 => 换装时能准确移除，不会残留
     *
     * 为什么要带上格号：一个 underlayer_head 槽可以有多个格（Expansion Core 之类的
     * 扩容 mod 通过 Curios 的 slot modifier 加），如果三件头盔共用同一个 UUID，
     * 它们之间又会互相顶掉，只剩最后一件生效。
     */
    private static UUID uuidFor(int slotIndex, int cellIndex, String attributeName, int ordinal) {
        String seed = "armorlayer." + slotIndex + "." + cellIndex + "." + attributeName + "." + ordinal;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 取第二层某件盔甲的属性修饰符。
     *
     * 内容和物品自带的一致，只是把每条修饰符的 UUID 换成上面那套专属 UUID。
     *
     * 少数 mod 的盔甲把数值放在别处，getAttributeModifiers 会返回空 —— 这时按
     * ArmorItem 的 defense / toughness 自己造一个，保证护甲条和减伤一定变化。
     */
    private static Multimap<Attribute, AttributeModifier> modifiersFor(LayerItem item) {
        ItemStack stack = item.stack();
        Multimap<Attribute, AttributeModifier> source = stack.getAttributeModifiers(item.equipmentSlot());

        Multimap<Attribute, AttributeModifier> result = HashMultimap.create();
        Map<String, Integer> counters = new HashMap<>();

        // 同一属性可能挂多条修饰符（比如神化的词缀）。先用属性名+操作+数值排序，
        // 保证序号分配只取决于内容、与集合的迭代顺序无关，这样每 tick 算出的 UUID 恒定。
        List<Map.Entry<Attribute, AttributeModifier>> entries = new ArrayList<>(source.entries());
        entries.sort(Comparator
                .comparing((Map.Entry<Attribute, AttributeModifier> e) ->
                        BuiltInRegistries.ATTRIBUTE.getKey(e.getKey()).toString())
                .thenComparing(e -> e.getValue().getOperation().name())
                .thenComparingDouble(e -> e.getValue().getAmount()));

        for (Map.Entry<Attribute, AttributeModifier> entry : entries) {
            String name = BuiltInRegistries.ATTRIBUTE.getKey(entry.getKey()).toString();
            int ordinal = counters.merge(name, 1, Integer::sum) - 1;
            AttributeModifier modifier = entry.getValue();

            result.put(entry.getKey(), new AttributeModifier(
                    uuidFor(item.slotIndex(), item.cellIndex(), name, ordinal),
                    "ArmorLayer " + name,
                    modifier.getAmount(),
                    modifier.getOperation()));
        }

        if (result.isEmpty() && stack.getItem() instanceof ArmorItem armor) {
            result.put(Attributes.ARMOR, new AttributeModifier(
                    uuidFor(item.slotIndex(), item.cellIndex(), "generic.armor", 0), "ArmorLayer defense",
                    armor.getDefense(), AttributeModifier.Operation.ADDITION));
            float toughness = armor.getToughness();
            if (toughness > 0F) {
                result.put(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(
                        uuidFor(item.slotIndex(), item.cellIndex(), "generic.armor_toughness", 0),
                        "ArmorLayer toughness",
                        toughness, AttributeModifier.Operation.ADDITION));
            }
        }
        return result;
    }

    /**
     * 这组修饰符是否还挂在实体身上。
     * AttributeInstance#addTransientAttributeModifiers 对相同 UUID 会先移除再添加，
     * 所以重复添加不会叠加，补的时候可以放心直接加。
     */
    private static boolean isApplied(LivingEntity entity, Multimap<Attribute, AttributeModifier> modifiers) {
        for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            AttributeInstance instance = entity.getAttributes().getInstance(entry.getKey());
            if (instance == null || instance.getModifier(entry.getValue().getId()) == null) {
                return false;
            }
        }
        return true;
    }

    // ==================================================================================
    // 荆棘：原版链路带不上第二层，这里自己实现一份（数值照抄原版 ThornsEnchantment）
    // ==================================================================================

    /**
     * 为什么不能靠原版荆棘：
     * ThornsEnchantment#doPostHurt 用的是
     * EnchantmentHelper.getRandomItemWith(Enchantments.THORNS, entity)，
     * 它按 EquipmentSlot 逐槽取件（返回值是 Map.Entry&lt;EquipmentSlot, ItemStack&gt;），
     * 根本不读 getArmorSlots() —— 所以改 getArmorSlots() 带不上第二层的荆棘。
     *
     * 这里在 LivingHurtEvent 里补一份，只统计第二层，不会和原版（主槽那部分）重复计算。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // 反伤不再触发反伤，避免两套荆棘互相弹个没完
        if (event.getSource().is(DamageTypes.THORNS)) {
            return;
        }

        Entity attackerEntity = event.getSource().getEntity();
        if (!(attackerEntity instanceof LivingEntity attacker) || attacker == player) {
            return;
        }

        int level = secondLayerThorns(player);
        if (level <= 0) {
            return;
        }

        RandomSource random = player.getRandom();

        // 原版 ThornsEnchantment.shouldHit：level > 0 && random < 0.15 * level
        if (!(random.nextFloat() < 0.15F * level)) {
            return;
        }

        // 原版 ThornsEnchantment.getDamage：level > 10 ? level - 10 : 1 + random(4)
        int damage = level > 10 ? level - 10 : 1 + random.nextInt(4);

        attacker.hurt(player.damageSources().thorns(player), (float) damage);

        if (shouldLog("thorns")) {
            LOGGER.info("[ArmorLayer] 荆棘反伤：{} 反伤 {} 荆棘等级={} 伤害={}",
                    player.getName().getString(), attacker.getName().getString(), level, damage);
        }
    }

    /** 第二层所有格子上装备的荆棘等级之和 */
    private static int secondLayerThorns(Player player) {
        int level = 0;
        for (ItemStack stack : getSecondLayerStacks(player)) {
            if (!stack.isEmpty()) {
                level += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.THORNS, stack);
            }
        }
        return level;
    }

    // ==================================================================================
    // 给 ProjectE 宝石盔甲放行
    //
    // 它四件套的技能各自被一处「槽位判等」卡住，源码里写成
    //     player.getItemBySlot(原版槽) == stack
    // 于是放进 Curios 第二层时一律 false。下面几个方法给对应的 mixin 用。
    // ==================================================================================

    /**
     * 这件物品是不是玩家第二层某个槽里的那一件。
     *
     * 用引用相等（==）而不是 ItemStack.matches —— ProjectE 会把快捷栏里的物品
     * 也一起喂进判定（PlayerHelper.checkHotbarCurios 会遍历快捷栏和主手），
     * 若按内容相等判断，玩家往快捷栏里塞一件宝石靴子也会凭空学会飞行。
     *
     * slotIndex 是 CURIO_SLOTS 的下标（0=头 1=胸 2=腿 3=脚），不是格号：
     * 该部位下任意一格装着这件物品都算。
     */
    public static boolean isSecondLayerItem(LivingEntity entity, int slotIndex, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (LayerItem item : getSecondLayer(entity)) {
            if (item.slotIndex() == slotIndex && item.stack() == stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按注册名判断是不是 ProjectE 的宝石盔甲（projecte:gem_*）。
     * 走注册名而不是 instanceof，这样本 mod 编译期不需要依赖 ProjectE。
     */
    public static boolean isGemArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "projecte".equals(id.getNamespace()) && id.getPath().startsWith("gem_");
    }

    /**
     * 对应 GemArmorBase.hasFullSet(Player)。
     * 原实现只遍历 Inventory.armor，这里把第二层也算进来。
     * 语义保持一致：每个非空槽位都必须是宝石盔甲（全空也返回 true，和原版一样）。
     */
    public static boolean hasFullGemSet(Player player) {
        for (ItemStack stack : allArmorLayers(player)) {
            if (!stack.isEmpty() && !isGemArmor(stack)) {
                return false;
            }
        }
        return true;
    }

    /** 对应 GemArmorBase.hasAnyPiece(Player)，同样把第二层算进来 */
    public static boolean hasAnyGemPiece(Player player) {
        for (ItemStack stack : allArmorLayers(player)) {
            if (isGemArmor(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 原版四个盔甲槽 + 第二层所有格，用于"是不是全套宝石甲"这类判定 */
    private static List<ItemStack> allArmorLayers(Player player) {
        List<ItemStack> all = new ArrayList<>(EQUIPMENT_SLOTS.length + 4);
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            all.add(player.getItemBySlot(slot));
        }
        all.addAll(getSecondLayerStacks(player));
        return all;
    }

    // ==================================================================================
    // 非保护性附魔：水下速掘 / 水下呼吸 / 深海漫游者 / 冰霜行者 / 灵魂疾行 / 迅捷潜行
    //
    // 这批附魔和"保护类减伤"走的是两条完全不同的路，所以改 getArmorSlots() 对它们无效：
    //
    //   保护类减伤：LivingEntity#hurt 里显式写 EnchantmentHelper
    //               .getDamageProtection(this.getArmorSlots(), source)
    //               => 读的是 getArmorSlots() => 第二层已经吃到了
    //
    //   上面这批  ：EnchantmentHelper.getEnchantmentLevel(附魔, 实体)
    //               => Enchantment#getSlotItems(实体)
    //               => for (slot : this.slots) entity.getItemBySlot(slot)   ← 硬编码原版四槽
    //               => 第二层完全看不见
    //
    // 由 EnchantmentHelperMixin 挂在 getEnchantmentLevel 的返回处补这一层。
    // ==================================================================================

    /**
     * 第二层四件里，某附魔的最高等级；没有则返回 0。
     *
     * 与原版 getSlotItems 保持同样的过滤：只有当这件附魔本来就能附在该物品上时才计入
     * （canEnchant）。这样"第二层放护腿、护腿上带着靴子专属附魔"这种异常组合不会凭空生效。
     */
    public static int getSecondLayerEnchantLevel(Enchantment enchantment, LivingEntity entity) {
        if (enchantment == null || !(entity instanceof Player player)) {
            return 0;
        }
        int best = 0;
        for (ItemStack stack : snapshotSecondLayer(player)) {
            if (stack.isEmpty() || !enchantment.canEnchant(stack)) {
                continue;
            }
            int level = EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
            if (level > best) {
                best = level;
            }
        }
        return best;
    }

    /**
     * 同一 tick 内复用第二层快照。
     *
     * getEnchantmentLevel 是热路径 —— 水下呼吸在实体每 tick 的呼吸判定里都会调用，
     * 深海漫游者/灵魂疾行在 travel 里调用，一次 tick 下来同一个玩家会被问好几次。
     * 每次都去问一遍 Curios 太浪费，按 tickCount 缓存一份即可：
     * 装备变更都发生在 tick 之间，最多延迟 1 tick（50ms）刷新。
     */
    private record LayerSnapshot(int tick, List<ItemStack> stacks) {
    }

    private static final Map<LivingEntity, LayerSnapshot> LAYER_SNAPSHOTS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static List<ItemStack> snapshotSecondLayer(Player player) {
        LayerSnapshot cached = LAYER_SNAPSHOTS.get(player);
        if (cached != null && cached.tick() == player.tickCount) {
            return cached.stacks();
        }
        List<ItemStack> stacks = getSecondLayerStacks(player);
        LAYER_SNAPSHOTS.put(player, new LayerSnapshot(player.tickCount, stacks));
        return stacks;
    }
}
