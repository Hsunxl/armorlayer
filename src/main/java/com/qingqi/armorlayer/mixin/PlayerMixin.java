package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Player.class)
public class PlayerMixin {

    /**
     * 让 getArmorSlots() 返回「原版 4 件 + Curios 第二层所有格」。
     *
     * 数量不是固定的 8：underlayer_* 槽可以被扩容（Expansion Core 这类 mod 会加格），
     * 所以每次现取一次第二层，有几格就拼几件。
     *
     * 原版只有两个地方读它，都会受益：
     *   - LivingEntity#hurt 里的 EnchantmentHelper.getDamageProtection(getArmorSlots(), source)
     *     => 第二层的保护/荆棘/神化高级保护开始生效
     *   - LivingEntity#getArmorCoverPercentage()
     *     => 第二层也计入盔甲覆盖率
     *
     * 注意这里用 getItemBySlot() 逐件组装，不能调原版 getArmorSlots()，否则无限递归。
     *
     * 方法名直接写 SRG 名 m_6168_ 并设 remap = false：
     * 运行时的方法就是 SRG 名，之前靠手写 refmap 做映射，但 refmap 结构写错
     * （少了 mixin config 那一层）导致映射从未生效，注入被静默跳过 —— 表现就是
     * "能穿进槽但护甲值不涨"。写死 SRG 名最稳，不依赖 refmap。
     */
    @Inject(method = "m_6168_", at = @At("HEAD"), cancellable = true, remap = false)
    private void armorlayer$getArmorSlots(CallbackInfoReturnable<Iterable<ItemStack>> cir) {
        Player self = (Player) (Object) this;

        List<ItemStack> all = new ArrayList<>(12);
        all.add(self.getItemBySlot(EquipmentSlot.HEAD));
        all.add(self.getItemBySlot(EquipmentSlot.CHEST));
        all.add(self.getItemBySlot(EquipmentSlot.LEGS));
        all.add(self.getItemBySlot(EquipmentSlot.FEET));
        all.addAll(ArmorLayer.getSecondLayerStacks(self));

        cir.setReturnValue(all);
    }
}
