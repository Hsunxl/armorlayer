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
 * 让 ProjectE 的宝石胸甲放在第二层时也能防火。
 *
 * 它实现了 IFireProtector，ProjectE 的 TickEvents 会遍历玩家身上的装备挨个问
 * canProtectAgainstFire，而原本的实现是：
 *
 *     public boolean canProtectAgainstFire(ItemStack stack, ServerPlayer player) {
 *         return player.getItemBySlot(EquipmentSlot.CHEST) == stack;
 *     }
 *
 * 只认原版胸槽，所以放第二层时防火无效。这里补一条放行。
 */
@Pseudo
@Mixin(targets = "moze_intel.projecte.gameObjs.items.armor.GemChest")
public class GemChestMixin {

    /** CURIO_SLOTS 里的下标：underlayer_chest */
    private static final int SLOT_CHEST = 1;

    @Inject(method = "canProtectAgainstFire", at = @At("HEAD"), cancellable = true, remap = false)
    private void armorlayer$canProtectAgainstFire(ItemStack stack, ServerPlayer player,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (ArmorLayer.isSecondLayerItem(player, SLOT_CHEST, stack)) {
            cir.setReturnValue(true);
        }
    }
}
