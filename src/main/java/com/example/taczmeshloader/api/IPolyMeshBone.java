package com.example.taczmeshloader.api;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import java.util.List;

/**
 * Abstraction over a Bedrock bone used by {@code PolyMeshModel}.
 * Wraps {@code BedrockPart} so the core renderer stays TacZ-agnostic.
 */
public interface IPolyMeshBone {

    String getName();

    float getPivotX();
    float getPivotY();
    float getPivotZ();

    float getRotX();
    float getRotY();
    float getRotZ();

    default float getScaleX() { return 1f; }
    default float getScaleY() { return 1f; }
    default float getScaleZ() { return 1f; }

    boolean isVisible();

    /** Returns true for bones whose name marks them as always fully-lit (e.g. emissive parts). */
    default boolean isIlluminated() { return false; }

    List<? extends IPolyMeshBone> getChildren();

    /**
     * Applies this bone's pivot/rotation/scale to the given {@link PoseStack}.
     * Override to delegate to {@code BedrockBone.translateAndRotateAndScale()}.
     */
    default void applyTransform(PoseStack poseStack) {
        poseStack.translate(getPivotX() / 16.0, getPivotY() / 16.0, getPivotZ() / 16.0);
        if (getRotZ() != 0f) poseStack.mulPose(Axis.ZP.rotation(getRotZ()));
        if (getRotY() != 0f) poseStack.mulPose(Axis.YP.rotation(getRotY()));
        if (getRotX() != 0f) poseStack.mulPose(Axis.XP.rotation(getRotX()));
        float sx = getScaleX(), sy = getScaleY(), sz = getScaleZ();
        if (sx != 1f || sy != 1f || sz != 1f) poseStack.scale(sx, sy, sz);
    }
}
