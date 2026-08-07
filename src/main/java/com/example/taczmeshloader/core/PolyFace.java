package com.example.taczmeshloader.core;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * poly_mesh の 1 フェース（三角形 or 四角形）。
 * 三角形は縮退 quad に変換して QUADS モードで描画する。
 */
@OnlyIn(Dist.CLIENT)
public class PolyFace {

    private final float[] posX, posY, posZ;
    private final float[] normX, normY, normZ;
    private final float[] u, v;
    private final int vertexCount;

    public PolyFace(float[] posX, float[] posY, float[] posZ,
                    float[] normX, float[] normY, float[] normZ,
                    float[] u, float[] v, int vertexCount) {
        this.posX = posX; this.posY = posY; this.posZ = posZ;
        this.normX = normX; this.normY = normY; this.normZ = normZ;
        this.u = u; this.v = v;
        this.vertexCount = vertexCount;
    }

    @OnlyIn(Dist.CLIENT)
    public void compile(PoseStack.Pose pose, VertexConsumer consumer,
                        int light, int overlay, float r, float g, float b, float a) {
        Matrix4f m = pose.pose();
        // 三角形は最終頂点を複製して縮退 quad にする
        int[] idx = (vertexCount == 3) ? new int[]{0, 1, 2, 2} : new int[]{0, 1, 2, 3};
        for (int i : idx) {
            consumer.addVertex(m, posX[i], posY[i], posZ[i])
                    .setColor(r, g, b, a)
                    .setUv(u[i], v[i])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, normX[i], normY[i], normZ[i]);
        }
    }
}
