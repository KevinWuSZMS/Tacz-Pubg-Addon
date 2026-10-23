package com.example.examplemod.vitals.mixin;

import com.example.examplemod.zones.match.MatchService;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Damage-event packets also play the hurt sound on clients, independently of the server. */
@Mixin(LivingEntity.class)
public abstract class BlueZoneHurtSoundMixin {
    @Shadow protected abstract SoundEvent getHurtSound(DamageSource source);

    @Redirect(method = {"playHurtSound", "handleDamageEvent"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getHurtSound(Lnet/minecraft/world/damagesource/DamageSource;)Lnet/minecraft/sounds/SoundEvent;"))
    private SoundEvent taczPubg$blueZoneHurtSound(LivingEntity entity, DamageSource source) {
        return source.is(MatchService.BLUE_ZONE) ? null : getHurtSound(source);
    }
}
