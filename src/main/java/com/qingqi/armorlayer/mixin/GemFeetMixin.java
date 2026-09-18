package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 ProjectE 的宝石靴子放在第二层时也能提供飞行和自动上台阶。
 *
 * 反编译 ProjectE 1.20.1（PE1.0.1）确认，这两个方法原本都是：
 *
 *     public boolean canProvideFlight(ItemStack stack, ServerPlayer player) {
 *         return player.getItemBySlot(EquipmentSlot.FEET) == stack;
 *     }
 *     public boolean canAssistStep(ItemStack stack, ServerPlayer player) {
 *         return player.getItemBySlot(EquipmentSlot.FEET) == stack
 *             && ItemHelper.checkItemNBT(stack, "StepAssist");
 *     }
 *
 * 也就是说它只认原版脚槽。这里在方法开头补一句：如果这件物品就在第二层脚槽里，直接放行。
 *
 * 用 targets 字符串 + @Pseudo 而不是直接 @Mixin(GemFeet.class)：
 * 这样编译期不需要依赖 ProjectE；万一没装 ProjectE，Mixin 只会跳过，不会报错。
 */
@Pseudo
@Mixin(targets = "moze_intel.projecte.gameObjs.items.armor.GemFeet")
public class GemFeetMixin {

    /** CURIO_SLOTS 里的下标：underlayer_feet */
    private static final int SLOT_FEET = 3;

    @Inject(method = "canProvideFlight", at = @At("HEAD"), cancellable = true, remap = false)
    private void armorlayer$canProvideFlight(ItemStack stack, ServerPlayer player,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (ArmorLayer.isSecondLayerItem(player, SLOT_FEET, stack)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canAssistStep", at = @At("HEAD"), cancellable = true, remap = false)
    private void armorlayer$canAssistStep(ItemStack stack, ServerPlayer player,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!ArmorLayer.isSecondLayerItem(player, SLOT_FEET, stack)) {
            return;
        }
        // 原版那个 ItemHelper.checkItemNBT(stack, "StepAssist") 的等价写法：
        // hasTag() && getTag().getBoolean("StepAssist")
        if (stack.hasTag() && stack.getTag() != null && stack.getTag().getBoolean("StepAssist")) {
            cir.setReturnValue(true);
        }
    }
}
