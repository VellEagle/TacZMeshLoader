package com.example.taczmeshloader.mixin.lrtactical;

import com.example.taczmeshloader.lrtactical.LrPolyMeshModel;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import me.xjqsh.lrtactical.client.resource.display.ConsumableDisplayInstance;
import me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance;
import me.xjqsh.lrtactical.client.resource.display.ThrowableDisplayInstance;
import me.xjqsh.lrtactical.client.renderer.model.CustomBedrockModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LR Tactical（Fabric 移植版）の DisplayInstance 生成に介入する Mixin 群（Fabric 1.21.1 版）。
 *
 * <p>LR が入っていない環境でも落ちないよう、全て {@code @Pseudo} ＋ {@code require = 0}。</p>
 *
 * <h3>なぜ @Redirect が必要か</h3>
 * create() の中で
 * <pre>
 *   display.model = new CustomBedrockModel(pojo, version);
 *   controller = Animations.createControllerFromBedrock(anim, display.model);
 * </pre>
 * の順に処理されるため、後からモデルを差し替えるとアニメーションが元のモデルに
 * 紐づいたままになる。そこで new CustomBedrockModel(...) 自体を
 * {@link LrPolyMeshModel} に差し替える（create 内に 2 か所：旧形式用・新形式用）。
 *
 * <h3>キューブモデルへの影響</h3>
 * LrPolyMeshModel は CustomBedrockModel のサブクラスで、poly_mesh が無いモデルでは
 * 何も追加しないため、普通のキューブモデルは元と全く同じ動作をする。
 */
public class LrDisplayInstanceMixin {

    // ==========================================================================
    // Melee（近接武器）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance", remap = false)
    public static class MeleeMixin {

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 0),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewLegacy(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 1),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewNew(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(MeleeDisplayInstance.MeleeDisplay pojo,
                                                    ResourceLocation id,
                                                    CallbackInfoReturnable<MeleeDisplayInstance> cir) {
            MeleeDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), display.getTexture());
            }
        }
    }

    // ==========================================================================
    // Consumable（消耗品）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.ConsumableDisplayInstance", remap = false)
    public static class ConsumableMixin {

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 0),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewLegacy(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 1),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewNew(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(ConsumableDisplayInstance.ConsumableDisplay pojo,
                                                    ResourceLocation id,
                                                    CallbackInfoReturnable<ConsumableDisplayInstance> cir) {
            ConsumableDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), display.getTexture());
            }
        }
    }

    // ==========================================================================
    // Throwable（投擲物）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.ThrowableDisplayInstance", remap = false)
    public static class ThrowableMixin {

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 0),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewLegacy(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Redirect(method = "create",
                at = @At(value = "NEW", target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel", ordinal = 1),
                require = 0)
        private static CustomBedrockModel meshyloader$redirectNewNew(BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(ThrowableDisplayInstance.ThrowableDisplay pojo,
                                                    ResourceLocation id,
                                                    CallbackInfoReturnable<ThrowableDisplayInstance> cir) {
            ThrowableDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), display.getTexture());
            }
        }
    }
}
