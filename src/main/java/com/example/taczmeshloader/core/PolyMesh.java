package com.example.taczmeshloader.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single poly_mesh rendering unit with VBO caching and light-level support.
 */
public class PolyMesh {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolyMesh.class);

    // Rendering flags
    private static final boolean FLIP_MODEL_X    = false;
    private static final boolean FLIP_MODEL_Y    = true;
    private static final boolean FLIP_UV_V       = true;
    private static final boolean FORCE_FLAT_SHADING = true;
    private static final boolean INVERT_FLAT_NORMAL = false;

    // LRU cache: max 8 VBOs per mesh, auto-evicts oldest
    private final Map<Integer, VertexBuffer> vboCache = new LinkedHashMap<Integer, VertexBuffer>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, VertexBuffer> eldest) {
            if (size() > 8) {
                if (eldest.getValue() != null) eldest.getValue().close();
                return true;
            }
            return false;
        }
    };

    // Baked vertex data for fallback rendering
    private final float[] bakedX, bakedY, bakedZ;
    private final float[] bakedNX, bakedNY, bakedNZ;
    private final float[] bakedU, bakedV;
    private final int vertexCount;

    public PolyMesh(JsonObject meshObj, float texWidth, float texHeight, float[] absPivot) {
        float pivotX = absPivot[0], pivotY = absPivot[1], pivotZ = absPivot[2];

        boolean normalizedUvs = meshObj.has("normalized_uvs") && meshObj.get("normalized_uvs").getAsBoolean();
        float[][] positions = parse2DArray(meshObj.getAsJsonArray("positions"), 3);
        float[][] normals   = parse2DArray(meshObj.getAsJsonArray("normals"), 3);
        float[][] uvs       = parse2DArray(meshObj.getAsJsonArray("uvs"), 2);
        int[][][] polys     = parse3DArray(meshObj.getAsJsonArray("polys"));

        int totalVerts = 0;
        for (int[][] poly : polys) {
            if (poly.length >= 3) totalVerts += (poly.length == 3) ? 4 : poly.length;
        }
        this.vertexCount = totalVerts;
        this.bakedX  = new float[totalVerts]; this.bakedY  = new float[totalVerts]; this.bakedZ  = new float[totalVerts];
        this.bakedNX = new float[totalVerts]; this.bakedNY = new float[totalVerts]; this.bakedNZ = new float[totalVerts];
        this.bakedU  = new float[totalVerts]; this.bakedV  = new float[totalVerts];

        int vIdx = 0;
        for (int[][] poly : polys) {
            if (poly.length < 3) continue;
            float faceNx = 0, faceNy = 0, faceNz = 0;
            if (FORCE_FLAT_SHADING) {
                // Validate position indices
                if (poly[0][0] >= positions.length || poly[1][0] >= positions.length || poly[2][0] >= positions.length) {
                    LOGGER.warn("Invalid position indices in poly, skipping");
                    continue;
                }
                float[] v0 = positions[poly[0][0]], v1 = positions[poly[1][0]], v2 = positions[poly[2][0]];
                float ux = v1[0]-v0[0], uy = v1[1]-v0[1], uz = v1[2]-v0[2];
                float vx = v2[0]-v0[0], vy = v2[1]-v0[1], vz = v2[2]-v0[2];
                faceNx = INVERT_FLAT_NORMAL ? vy*uz-vz*uy : uy*vz-uz*vy;
                faceNy = INVERT_FLAT_NORMAL ? vz*ux-vx*uz : uz*vx-ux*vz;
                faceNz = INVERT_FLAT_NORMAL ? vx*uy-vy*ux : ux*vy-uy*vx;
                float len = (float)Math.sqrt(faceNx*faceNx + faceNy*faceNy + faceNz*faceNz);
                if (len > 1e-6f) { 
                    faceNx/=len; faceNy/=len; faceNz/=len; 
                } else {
                    // Degenerate triangle - use default up normal
                    faceNx = 0; faceNy = 1; faceNz = 0;
                }
            }
            int drawCount = (poly.length == 3) ? 4 : poly.length;
            for (int i = 0; i < drawCount; i++) {
                int srcIdx = (poly.length == 3 && i == 3) ? 2 : i;
                int[] vi = poly[srcIdx];
                
                // Validate indices before array access
                if (vi[0] >= positions.length || vi[1] >= normals.length || vi[2] >= uvs.length) {
                    LOGGER.warn("Out of bounds indices: pos={}, norm={}, uv={}", vi[0], vi[1], vi[2]);
                    continue;
                }
                
                float[] pos = positions[vi[0]]; float[] uv = uvs[vi[2]];
                bakedX[vIdx] = (FLIP_MODEL_X ? -(pos[0]-pivotX) : (pos[0]-pivotX)) / 16.0f;
                bakedY[vIdx] = (FLIP_MODEL_Y ? -(pos[1]-pivotY) : (pos[1]-pivotY)) / 16.0f;
                bakedZ[vIdx] = (pos[2]-pivotZ) / 16.0f;
                if (FORCE_FLAT_SHADING) {
                    bakedNX[vIdx] = FLIP_MODEL_X ? -faceNx : faceNx;
                    bakedNY[vIdx] = FLIP_MODEL_Y ? -faceNy : faceNy;
                    bakedNZ[vIdx] = faceNz;
                } else {
                    float[] n = normals[vi[1]];
                    bakedNX[vIdx] = FLIP_MODEL_X ? -n[0] : n[0]; bakedNY[vIdx] = FLIP_MODEL_Y ? -n[1] : n[1]; bakedNZ[vIdx] = n[2];
                }
                bakedU[vIdx] = normalizedUvs ? uv[0] : (uv[0]/texWidth);
                float v = normalizedUvs ? uv[1] : (uv[1]/texHeight);
                bakedV[vIdx] = FLIP_UV_V ? 1.0f - v : v;
                vIdx++;
            }
        }
    }

    // VBO management

    public void ensureUploaded(int packedLight) {
        if (vertexCount == 0 || vboCache.containsKey(packedLight)) return;

        try {
            VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);

            BufferBuilder builder = new BufferBuilder(vertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

            for (int i = 0; i < vertexCount; i++) {
                builder.vertex(bakedX[i], bakedY[i], bakedZ[i])
                        .color(1f, 1f, 1f, 1f)
                        .uv(bakedU[i], bakedV[i])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(packedLight)
                        .normal(bakedNX[i], bakedNY[i], bakedNZ[i])
                        .endVertex();
            }

            vertexBuffer.bind();
            vertexBuffer.upload(builder.end());
            VertexBuffer.unbind();

            vboCache.put(packedLight, vertexBuffer);
        } catch (Exception e) {
            LOGGER.error("Failed to upload VBO for light level {}", packedLight, e);
        }
    }

    public void drawVBO(Matrix4f posePose, int packedLight) {
        VertexBuffer vbo = vboCache.get(packedLight);
        if (vbo == null) {
            LOGGER.warn("VBO not ready for light level {}, skipping draw", packedLight);
            return;
        }

        vbo.bind();
        vbo.drawWithShader(posePose, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    public boolean isVboReady(int packedLight) { return vboCache.containsKey(packedLight); }
    public int getVertexCount() { return vertexCount; }

    public void close() {
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) { vbo.close(); }
        }
        vboCache.clear();
    }

    // Fallback: VertexConsumer path

    public void compileConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                                int lightmap, int overlay,
                                float red, float green, float blue, float alpha) {
        if (vertexCount == 0) return;
        Matrix4f pm = pose.pose();
        Matrix3f nm = pose.normal();
        for (int i = 0; i < vertexCount; i++) {
            consumer.vertex(pm, bakedX[i], bakedY[i], bakedZ[i])
                    .color(red, green, blue, alpha)
                    .uv(bakedU[i], bakedV[i])
                    .overlayCoords(overlay)
                    .uv2(lightmap)
                    .normal(nm, -bakedNX[i], -bakedNY[i], -bakedNZ[i])
                    .endVertex();
        }
    }

    public void compile(PoseStack.Pose pose, VertexConsumer consumer,
                        int lightmap, int overlay, float red, float green, float blue, float alpha) {
        compileConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha);
    }

    // JSON parsing utilities

    private float[][] parse2DArray(JsonArray array, int dim) {
        if (array == null || array.isEmpty()) return new float[0][0];
        float[][] result = new float[array.size()][dim];
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonArray()) {
                LOGGER.warn("Invalid 2D array element at index {}, skipping", i);
                continue;
            }
            JsonArray sub = array.get(i).getAsJsonArray();
            for (int j = 0; j < Math.min(dim, sub.size()); j++) {
                try {
                    result[i][j] = sub.get(j).getAsFloat();
                } catch (Exception e) {
                    LOGGER.warn("Invalid float at [{}][{}], using 0", i, j);
                    result[i][j] = 0;
                }
            }
        }
        return result;
    }

    private int[][][] parse3DArray(JsonArray array) {
        if (array == null || array.isEmpty()) return new int[0][0][0];
        int[][][] result = new int[array.size()][][];
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonArray()) {
                LOGGER.warn("Invalid 3D array element at index {}, skipping", i);
                result[i] = new int[0][0];
                continue;
            }
            JsonArray face = array.get(i).getAsJsonArray();
            result[i] = new int[face.size()][3];
            for (int j = 0; j < face.size(); j++) {
                if (!face.get(j).isJsonArray()) {
                    LOGGER.warn("Invalid face element at [{}][{}], using defaults", i, j);
                    continue;
                }
                JsonArray vd = face.get(j).getAsJsonArray();
                for (int k = 0; k < Math.min(3, vd.size()); k++) {
                    try {
                        result[i][j][k] = vd.get(k).getAsInt();
                    } catch (Exception e) {
                        LOGGER.warn("Invalid int at [{}][{}][{}], using 0", i, j, k);
                        result[i][j][k] = 0;
                    }
                }
            }
        }
        return result;
    }
}