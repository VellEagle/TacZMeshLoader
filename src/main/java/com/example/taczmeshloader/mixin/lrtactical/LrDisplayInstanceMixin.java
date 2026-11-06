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
 * LesRaisins の DisplayInstance 生成に介入するMixin群。
 *
 * <h3>なぜ @Redirect が必要か</h3>
 * create() 内のフロー：
 * <pre>
 *   display.model = new CustomBedrockModel(pojo, version);   // ← ここを差し替える
 *   AnimationController controller =
 *       Animations.createControllerFromBedrock(anim, display.model); // ← modelに紐づく
 * </pre>
 * {@code @Inject(at=RETURN)} でモデルを後から差し替えても、
 * AnimationController はすでに元の CustomBedrockModel に紐づいており
 * アニメーションが動作しない。
 * {@code @Redirect} で new CustomBedrockModel(...) 自体を LrPolyMeshModel に
 * 差し替えることで、AnimationController も最初から LrPolyMeshModel に紐づく。
 *
 * <h3>cubeモデルへの影響</h3>
 * LrPolyMeshModel は CustomBedrockModel のサブクラスなので、
 * poly_mesh が不要な通常の cubeモデルでも完全に同じ動作をする。
 * loadPolyMesh() は後から必要な場合のみ呼ぶ。
 */
public class LrDisplayInstanceMixin {

    // ==========================================================================
    // Melee（近接武器）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance",
           remap = false)
    public static class MeleeMixin {

        /**
         * new CustomBedrockModel(modelPOJO, BedrockVersion.LEGACY) の呼び出しを差し替える。
         */
        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 0
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewLegacy(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        /**
         * new CustomBedrockModel(modelPOJO, BedrockVersion.NEW) の呼び出しを差し替える。
         */
        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 1
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewNew(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        /**
         * create() の末尾で、モデルが LrPolyMeshModel であれば
         * テクスチャを渡して poly_mesh のロードを試みる。
         * geo_models/ に対応する poly_mesh JSON がなければ何もしない。
         */
        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(
                MeleeDisplayInstance.MeleeDisplay pojo,
                ResourceLocation id,
                CallbackInfoReturnable<MeleeDisplayInstance> cir) {
            MeleeDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                ResourceLocation tex = display.getTexture();
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), tex);
            }
        }
    }

    // ==========================================================================
    // Consumable（消耗品）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.ConsumableDisplayInstance",
           remap = false)
    public static class ConsumableMixin {

        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 0
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewLegacy(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 1
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewNew(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(
                ConsumableDisplayInstance.ConsumableDisplay pojo,
                ResourceLocation id,
                CallbackInfoReturnable<ConsumableDisplayInstance> cir) {
            ConsumableDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                ResourceLocation tex = display.getTexture();
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), tex);
            }
        }
    }

    // ==========================================================================
    // Throwable（投擲物）
    // ==========================================================================

    @Pseudo
    @Mixin(targets = "me.xjqsh.lrtactical.client.resource.display.ThrowableDisplayInstance",
           remap = false)
    public static class ThrowableMixin {

        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 0
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewLegacy(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Redirect(
            method = "create",
            at = @At(
                value = "NEW",
                target = "me/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel",
                ordinal = 1
            ),
            require = 0
        )
        private static CustomBedrockModel meshyloader$redirectNewNew(
                BedrockModelPOJO pojo, BedrockVersion version) {
            return new LrPolyMeshModel(pojo, version);
        }

        @Inject(method = "create", at = @At("RETURN"), require = 0)
        private static void meshyloader$afterCreate(
                ThrowableDisplayInstance.ThrowableDisplay pojo,
                ResourceLocation id,
                CallbackInfoReturnable<ThrowableDisplayInstance> cir) {
            ThrowableDisplayInstance display = cir.getReturnValue();
            if (display == null) return;
            if (display.getModel() instanceof LrPolyMeshModel polyModel) {
                ResourceLocation tex = display.getTexture();
                LrPolyMeshModel.tryLoadPolyMesh(polyModel, pojo.modelLocation(), tex);
            }
        }
    }
}
