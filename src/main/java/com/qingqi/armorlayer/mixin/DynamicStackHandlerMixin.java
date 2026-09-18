package com.qingqi.armorlayer.mixin;

import com.qingqi.armorlayer.ArmorLayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.common.inventory.DynamicStackHandler;

import java.util.function.Function;

/**
 * 第二道保险：直接在 Curios 的槽位容器上放行。
 *
 * CuriosApiMixin 走的是 CuriosApi#isStackValid，但 Curios 自己也用 @Inject HEAD cancellable
 * 实现了那个方法（curios$isStackValid，默认优先级 1000）。如果它的注入先执行并返回 false，
 * 我们的注入就不会被调用。这里改在 DynamicStackHandler#isItemValid 上放行 —— 这个方法是
 * Curios 自己写的普通方法，没有别的 mod 跟我们抢注入点，顺序可控。
 */
@Mixin(DynamicStackHandler.class)
public class DynamicStackHandlerMixin {

    @Shadow
    protected Function<Integer, SlotContext> ctxBuilder;

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void armorlayer$allowSecondLayer(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        SlotContext ctx = ctxBuilder.apply(slot);
        if (ctx != null && ArmorLayer.allowsSecondLayer(ctx.identifier(), stack)) {
            cir.setReturnValue(true);
        }
    }
}
