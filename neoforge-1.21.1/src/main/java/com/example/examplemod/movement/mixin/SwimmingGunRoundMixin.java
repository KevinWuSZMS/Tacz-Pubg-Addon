package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class SwimmingGunRoundMixin {
    @Shadow private LivingEntity shooter;

    @WrapOperation(method = "shootOnce", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/util/CycleTaskHelper;addCycleTask(Ljava/util/function/BooleanSupplier;JI)V"), remap = false)
    private void taczPubg$stopBurstOnSwimming(BooleanSupplier nativeRound, long period, int cycles, Operation<Void> original) {
        // The script API is reused. Capture its owner now, but evaluate water for each round.
        original.call(SwimmingGunRestrictions.guardRound(shooter, nativeRound), period, cycles);
    }
}
