package com.example.taczmeshloader.core;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Single poly_mesh face (triangle or quad).
 * Triangles are converted to degenerate quads for QUADS mode rendering.
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
        Matrix3f n = pose.normal();
        // Convert triangles to degenerate quads
        int[] idx = (vertexCount == 3) ? new int[]{0, 1, 2, 2} : new int[]{0, 1, 2, 3};
        for (int i : idx) {
            consumer.vertex(m, posX[i], posY[i], posZ[i])
                    .color(r, g, b, a)
                    .uv(u[i], v[i])
                    .overlayCoords(overlay)
                    .uv2(light)
                    .normal(n, normX[i], normY[i], normZ[i])
                    .endVertex();
        }
    }
}
