package com.example.taczmeshloader.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * poly_mesh の 1 描画ユニット（ライトレベル・キャッシュ対応 VBO 版）。
 *
 * <h3>シェーダー切り替え時の影反転バグ修正</h3>
 * Oculus がシェーダーパックを ON/OFF すると、シャドウパスの有無や
 * 法線変換行列の期待値が変わるため、古いライトレベルで焼き込んだ
 * VBO がそのまま残ると影の表示が反転する。
 *
 * 修正: {@link #invalidateVboCache()} を用意し、シェーダー状態が変わった
 * フレームで全 VBO キャッシュを破棄する。呼び出しは {@link PolyMeshModel}
 * 経由で行われ、{@link com.example.taczmeshloader.render.ShaderStateTracker}
 * がフレーム開始時に差分を検知して通知する。
 */
public class PolyMesh {

    // =========================================================
    // ▼ 描画設定トグル ▼
    // =========================================================
    private static final boolean FLIP_MODEL_X    = false;
    private static final boolean FLIP_MODEL_Y    = true;
    private static final boolean FLIP_UV_V       = true;
    private static final boolean FORCE_FLAT_SHADING = true;
    private static final boolean INVERT_FLAT_NORMAL = false;
    // =========================================================

    // ---- ライトレベルごとのVBOキャッシュ（最大8個） ----
    private final Map<Integer, VertexBuffer> vboCache = new LinkedHashMap<Integer, VertexBuffer>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, VertexBuffer> eldest) {
            if (size() > 8) {
                if (eldest.getValue() != null) eldest.getValue().close();
                // 破棄したことを PolyMeshModel に知らせる（知らせないと、PolyMeshModel は
                // このライト値を「アップロード済み」と思い込んだまま VBO 描画を選び、
                // drawVBO() が空振りしてメッシュが消える）
                if (evictionListener != null) evictionListener.accept(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /** LRU により VBO が破棄されたときに、そのライト値を受け取るリスナー（PolyMeshModel が登録） */
    private java.util.function.IntConsumer evictionListener = null;

    public void setEvictionListener(java.util.function.IntConsumer listener) {
        this.evictionListener = listener;
    }

    // ---- フォールバック用ベイク済み配列 ----
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
                float[] v0 = positions[poly[0][0]], v1 = positions[poly[1][0]], v2 = positions[poly[2][0]];
                float ux = v1[0]-v0[0], uy = v1[1]-v0[1], uz = v1[2]-v0[2];
                float vx = v2[0]-v0[0], vy = v2[1]-v0[1], vz = v2[2]-v0[2];
                faceNx = INVERT_FLAT_NORMAL ? vy*uz-vz*uy : uy*vz-uz*vy;
                faceNy = INVERT_FLAT_NORMAL ? vz*ux-vx*uz : uz*vx-ux*vz;
                faceNz = INVERT_FLAT_NORMAL ? vx*uy-vy*ux : ux*vy-uy*vx;
                float len = (float)Math.sqrt(faceNx*faceNx + faceNy*faceNy + faceNz*faceNz);
                if (len > 1e-6f) { faceNx/=len; faceNy/=len; faceNz/=len; }
            }
            int drawCount = (poly.length == 3) ? 4 : poly.length;
            for (int i = 0; i < drawCount; i++) {
                int srcIdx = (poly.length == 3 && i == 3) ? 2 : i;
                int[] vi = poly[srcIdx];
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

    // =========================================================================
    // VBO 管理
    // =========================================================================

    private static final org.apache.logging.log4j.Logger MESH_PERF_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoaderPerf");

    public void ensureUploaded(int packedLight) {
        if (vertexCount == 0 || vboCache.containsKey(packedLight)) return;

        long t0 = System.nanoTime();

        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);

        BufferBuilder builder = new BufferBuilder(vertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

        for (int i = 0; i < vertexCount; i++) {
            builder.vertex(bakedX[i], bakedY[i], bakedZ[i])
                    .color(1f, 1f, 1f, 1f)
                    .uv(bakedU[i], bakedV[i])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight) // 要求されたライトレベルを焼き付ける
                    .normal(bakedNX[i], bakedNY[i], bakedNZ[i])
                    .endVertex();
        }

        vertexBuffer.bind();
        vertexBuffer.upload(builder.end());
        VertexBuffer.unbind();

        vboCache.put(packedLight, vertexBuffer);

        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        MESH_PERF_LOG.info("[MeshyPerf] VBO upload: light={} vertexCount={} cacheSizeAfter={} tookMs={} screenOpen={}",
                packedLight, vertexCount, vboCache.size(), String.format("%.3f", ms),
                net.minecraft.client.Minecraft.getInstance().screen != null);
    }

    /**
     * drawVBO() で使う作業用の行列（毎フレームの new を避けるため使い回す）。
     * レンダースレッドからしか呼ばれないため共有しても安全。
     */
    private final Matrix4f scratchModelView = new Matrix4f();

    public void drawVBO(Matrix4f posePose, int packedLight) {
        VertexBuffer vbo = vboCache.get(packedLight);
        if (vbo == null) return;

        // 【YSM 互換対応】RenderSystem のモデルビュー行列を合成してから渡す。
        //
        // 通常の VertexConsumer 描画（キューブ部分など）は、頂点を PoseStack で変換した後、
        // 描画時に RenderSystem.getModelViewMatrix() がシェーダー側で掛けられる。
        // 以前の VBO 描画は PoseStack の行列だけを渡していたため、このモデルビュー行列が
        // 抜け落ちていた。
        //
        // ワールド内の描画（地面・一人称・額縁・Oculus の影パス）ではモデルビュー行列は
        // 単位行列（何もしない行列）なので、これまで問題が表に出なかった。
        // しかし Yes Steve Model（YSM）の左上プレイヤープレビューは、描画前に
        // モデルビュー行列へ「奥行き方向の移動と Z 反転」を設定してから
        // 銃を描くため、その分が抜けたメッシュは表示範囲の外（カメラの後ろ側）に置かれ、
        // メッシュだけが消えていた（キューブは正しく表示される）。
        //
        // 1.21.1 版（NeoForge / Fabric）と同じく合成することで、キューブ部分と
        // 完全に同じ位置に描かれるようになる。単位行列の場面では結果は変わらない。
        scratchModelView.set(RenderSystem.getModelViewMatrix()).mul(posePose);

        vbo.bind();
        vbo.drawWithShader(scratchModelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    public boolean isVboReady(int packedLight) { return vboCache.containsKey(packedLight); }
    public int getVertexCount() { return vertexCount; }

    /**
     * シェーダー状態変化時に VBO キャッシュを全破棄する。
     *
     * Oculus がシェーダーパックを切り替えると、シャドウパスの有無や
     * 法線行列の扱いが変わる。古い VBO には以前の状態で焼き込んだ
     * ライト値・法線が残っているため、そのまま使うと影が反転する。
     * キャッシュを破棄して次フレームで再アップロードさせることで解決する。
     */
    public void invalidateVboCache() {
        int hadEntries = vboCache.size();
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) vbo.close();
        }
        vboCache.clear();
        if (hadEntries > 0) {
            MESH_PERF_LOG.info("[MeshyPerf] VBO cache invalidated: entriesCleared={} screenOpen={}",
                    hadEntries, net.minecraft.client.Minecraft.getInstance().screen != null);
        }
    }

    public void close() {
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) { vbo.close(); }
        }
        vboCache.clear();
    }

    // =========================================================================
    // フォールバック: VertexConsumer パス
    // =========================================================================

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

    /**
     * 両面描画用（TSO 互換で使用）。各面を「元の順番」と「逆順」の 2 回書き込む。
     *
     * <p>TaCZ: Scope Overhaul の遅延描画は、TacZ がキューブの描画に使う RenderType
     * （entityCutout = 裏面カリングあり）にそのまま書き込む。PolyMesh は Y 反転の都合で
     * 面の向きが逆になっているため、片面だけだとカリングで消えてしまう。
     * 両向きを書いておけば、どちらか一方が必ず表向きになる。</p>
     */
    public void compileConsumerDoubleSided(PoseStack.Pose pose, VertexConsumer consumer,
                                           int lightmap, int overlay,
                                           float red, float green, float blue, float alpha) {
        if (vertexCount == 0) return;
        Matrix4f pm = pose.pose();
        Matrix3f nm = pose.normal();
        for (int q = 0; q + 3 < vertexCount; q += 4) {
            for (int k = 0; k < 4; k++) {
                emitVertex(pm, nm, consumer, q + k, lightmap, overlay, red, green, blue, alpha);
            }
            for (int k = 3; k >= 0; k--) {
                emitVertex(pm, nm, consumer, q + k, lightmap, overlay, red, green, blue, alpha);
            }
        }
    }

    private void emitVertex(Matrix4f pm, Matrix3f nm, VertexConsumer consumer, int i,
                            int lightmap, int overlay, float red, float green, float blue, float alpha) {
        consumer.vertex(pm, bakedX[i], bakedY[i], bakedZ[i])
                .color(red, green, blue, alpha)
                .uv(bakedU[i], bakedV[i])
                .overlayCoords(overlay)
                .uv2(lightmap)
                .normal(nm, -bakedNX[i], -bakedNY[i], -bakedNZ[i])
                .endVertex();
    }

    /**
     * 頂点を行列で変換した位置（ピクセル単位 = ブロック単位 × 16）で、範囲 bounds を広げる（TSO 互換で使用）。
     *
     * @param bounds {minX, minY, minZ, maxX, maxY, maxZ}
     */
    public void expandBounds(Matrix4f matrix, float[] bounds) {
        org.joml.Vector3f p = new org.joml.Vector3f();
        for (int i = 0; i < vertexCount; i++) {
            matrix.transformPosition(bakedX[i], bakedY[i], bakedZ[i], p);
            float x = p.x * 16.0f, y = p.y * 16.0f, z = p.z * 16.0f;
            if (x < bounds[0]) bounds[0] = x;
            if (y < bounds[1]) bounds[1] = y;
            if (z < bounds[2]) bounds[2] = z;
            if (x > bounds[3]) bounds[3] = x;
            if (y > bounds[4]) bounds[4] = y;
            if (z > bounds[5]) bounds[5] = z;
        }
    }

    // =========================================================================
    // パースユーティリティ
    // =========================================================================

    private float[][] parse2DArray(JsonArray array, int dim) {
        if (array == null) return new float[0][0];
        float[][] result = new float[array.size()][dim];
        for (int i = 0; i < array.size(); i++) {
            JsonArray sub = array.get(i).getAsJsonArray();
            for (int j = 0; j < Math.min(dim, sub.size()); j++) result[i][j] = sub.get(j).getAsFloat();
        }
        return result;
    }

    private int[][][] parse3DArray(JsonArray array) {
        if (array == null) return new int[0][0][0];
        int[][][] result = new int[array.size()][][];
        for (int i = 0; i < array.size(); i++) {
            JsonArray face = array.get(i).getAsJsonArray();
            result[i] = new int[face.size()][3];
            for (int j = 0; j < face.size(); j++) {
                JsonArray vd = face.get(j).getAsJsonArray();
                result[i][j][0] = vd.get(0).getAsInt();
                result[i][j][1] = vd.get(1).getAsInt();
                result[i][j][2] = vd.get(2).getAsInt();
            }
        }
        return result;
    }
}
