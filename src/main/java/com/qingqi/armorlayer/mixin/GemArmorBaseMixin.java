package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让宝石盔甲的「全套」判定把第二层也算进去。
 *
 * 反编译 ProjectE 1.20.1（PE1.0.1）确认，这两个静态方法遍历的都是
 * {@code player.getInventory().armor}（Inventory.armor 字段），不是 getArmorSlots()：
 *
 *     public static boolean hasAnyPiece(Player p) {
 *         return p.getInventory().armor.stream().anyMatch(s -> !s.isEmpty() && s.getItem() instanceof GemArmorBase);
 *     }
 *     public static boolean hasFullSet(Player p) {
 *         return p.getInventory().armor.stream().noneMatch(s -> !s.isEmpty() && !(s.getItem() instanceof GemArmorBase));
 *     }
 *
 * hasFullSet 支撑的是全套减伤（getFullSetBaseReduction / getMaxDamageAbsorb），
 * 所以它不认识第二层的话，两层都穿宝石甲也吃不到全套加成。
 *
 * 这里只在「把第二层算进来后判定为 true」时覆盖返回值：
 * 如果玩家原版四槽里混了别的盔甲，我们的判定同样返回 false，就保持原版结果不变。
 */
@Pseudo
@Mixin(targets = "moze_intel.projecte.gameObjs.items.armor.GemArmorBase")
public class GemArmorBaseMixin {

    @Inject(method = "hasFullSet", at = @At("HEAD"), cancellable = true, remap = false)
    private static void armorlayer$hasFullSet(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (ArmorLayer.hasFullGemSet(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "hasAnyPiece", at = @At("HEAD"), cancellable = true, remap = false)
    private static void armorlayer$hasAnyPiece(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (ArmorLayer.hasAnyGemPiece(player)) {
            cir.setReturnValue(true);
        }
    }
}
