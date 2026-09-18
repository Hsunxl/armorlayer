package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

/**
 * 放行第二层盔甲。
 *
 * Curios 5.14 的入槽判定（CuriosImplMixinHooks#isStackValid）完全依赖
 * ISlotType#getValidators()，而 Chained Curios 提供的 underlayer_* 槽位没有定义
 * 任何 validator（它的 data/chainedcurios/curios/slots/*.json 里没有 validators 字段，
 * Curios 也不会自动把 curios:<槽位> 标签当作校验器）。结果就是：除了它自己特殊处理的
 * 原版锁链甲，任何物品都会被判为不能入槽。
 *
 * priority 设成 2000 是为了排在 Curios 自己的 curios$isStackValid（默认 1000）前面，
 * 否则它先 setReturnValue(false) 之后我们这次注入就不会被执行。
 */
@Mixin(value = CuriosApi.class, priority = 2000)
public class CuriosApiMixin {

    @Inject(method = "isStackValid", at = @At("HEAD"), cancellable = true, remap = false)
    private static void armorlayer$allowSecondLayer(SlotContext context, ItemStack stack,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (ArmorLayer.allowsSecondLayer(context.identifier(), stack)) {
            cir.setReturnValue(true);
        }
    }
}
