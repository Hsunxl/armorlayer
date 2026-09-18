package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把第二层的附魔接进原版附魔读取链路。
 *
 * 反编译 1.20.1 确认的调用链：
 *
 *     EnchantmentHelper.getRespiration(entity)      ┐
 *     EnchantmentHelper.hasAquaAffinity(entity)     │
 *     EnchantmentHelper.getDepthStrider(entity)     ├─→ EnchantmentHelper
 *     EnchantmentHelper.hasFrostWalker(entity)      │   .getEnchantmentLevel(附魔, 实体)
 *     EnchantmentHelper.hasSoulSpeed(entity)        │            │
 *     EnchantmentHelper.getSneakingSpeedBonus(entity)┘            ↓
 *                                                    Enchantment#getSlotItems(实体)
 *
 * 而 getSlotItems 的字节码是：
 *
 *     for (EquipmentSlot slot : this.slots) {
 *         ItemStack stack = entity.getItemBySlot(slot);   // ← 硬编码原版四槽
 *         if (!stack.isEmpty()) map.put(slot, stack);
 *     }
 *
 * 它读的是 getItemBySlot，**完全不看 getArmorSlots()**。所以上一轮改 getArmorSlots()
 * 只带上了"保护类减伤"（LivingEntity#hurt 里是显式传 getArmorSlots() 的），
 * 带不上这一批非保护性附魔 —— 水下速掘、水下呼吸、深海漫游者、冰霜行者、
 * 灵魂疾行、迅捷潜行全都漏在第二层外面。
 *
 * 这份注入挂在它们共同的收口点 getEnchantmentLevel(附魔, 实体) 上，
 * 在返回值处做一次"取最大"，一处覆盖全部，不用逐个附魔去补。
 *
 * 取最大而不是相加：原版语义就是"在该附魔所有可装备槽里取最高等级"
 * （双护腿各带迅捷潜行 III 也只算 III），第二层并进候选集即可，不改变这个语义。
 *
 * method 写 SRG 名 m_44836_ 并设 remap = false：运行时的方法名就是 SRG，
 * 依赖 refmap 映射的话，refmap 一有问题注入会被静默跳过（上一轮踩过这个坑）。
 */
@Mixin(EnchantmentHelper.class)
public class EnchantmentHelperMixin {

    @Inject(
            method = "m_44836_(Lnet/minecraft/world/item/enchantment/Enchantment;Lnet/minecraft/world/entity/LivingEntity;)I",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private static void armorlayer$secondLayerEnchantLevel(Enchantment enchantment, LivingEntity entity,
                                                           CallbackInfoReturnable<Integer> cir) {
        int secondLayer = ArmorLayer.getSecondLayerEnchantLevel(enchantment, entity);
        if (secondLayer > cir.getReturnValue()) {
            cir.setReturnValue(secondLayer);
        }
    }
}
