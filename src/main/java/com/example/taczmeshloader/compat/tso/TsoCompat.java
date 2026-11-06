package com.example.taczmeshloader.compat.tso;

import net.neoforged.fml.ModList;

/**
 * TaCZ: Scope Overhaul（TSO, modid = taczso）との互換処理の共通部分。
 *
 * <h3>TSO と組み合わせたときの問題</h3>
 * TSO はスコープの覗き穴（レンズ）の位置・大きさを、ocular / ocular_ring / division
 * パーツの「キューブ」の範囲から計算する。メッシュだけで作られたパーツにはキューブが
 * 無いため、TSO はレンズの大きさを計算できず、覗き穴やレティクルが正しく出ない。
 * また、シェーダー使用時に TSO は ocular / division を後から描き直す（遅延描画）が、
 * そのときもキューブしか描かないため、メッシュの目鏡やレティクルが消えてしまう。
 *
 * <h3>対策</h3>
 * <ul>
 *   <li>{@link TsoMeshBoundsCube}：メッシュの範囲と同じ大きさの「見えないキューブ」を
 *       パーツに追加し、TSO の計算にメッシュの範囲を使わせる。</li>
 *   <li>{@link com.example.taczmeshloader.mixin.tso.TsoPipLensRendererMixin}：
 *       TSO の遅延描画の実行中だけ {@link #isInDeferredWriter()} を true にし、
 *       その間だけ見えないキューブがメッシュを描くようにする。</li>
 * </ul>
 * TSO が入っていないときは、どちらも一切動作しない（見えないキューブも追加されない）。
 */
public final class TsoCompat {
    public static final String MOD_ID = "taczso";

    private static Boolean loaded = null;
    /** TSO の遅延描画（ocular / division の描き直し）の実行中かどうか。描画スレッドからのみ触る。 */
    private static int deferredWriterDepth = 0;

    private TsoCompat() {}

    public static boolean isLoaded() {
        if (loaded == null) {
            ModList list = ModList.get();
            if (list == null) return false; // 起動の極初期（まだ判定できない）
            loaded = list.isLoaded(MOD_ID);
        }
        return loaded;
    }

    public static boolean isInDeferredWriter() {
        return deferredWriterDepth > 0;
    }

    // =========================================================================
    // メッシュ銃のステンシル保持（TaczPolyMeshGunModel / BedrockGunModelStencilMixin から使用）
    // =========================================================================

    /** true の間、TacZ の BedrockGunModel#render 最後のステンシルのクリアを止める */
    private static boolean keepGunStencil = false;
    /** 実際にクリアを止めたかどうか */
    private static boolean gunStencilClearSkipped = false;

    /** メッシュ銃が TacZ 側の描画（super.render）を呼ぶ直前に呼ぶ */
    public static void beginKeepGunStencil() {
        keepGunStencil = true;
        gunStencilClearSkipped = false;
    }

    /**
     * super.render の直後に呼ぶ。
     *
     * @return Mixin が実際にステンシルのクリアを止めた場合 true（ステンシルが残っている）
     */
    public static boolean endKeepGunStencil() {
        boolean skipped = gunStencilClearSkipped;
        keepGunStencil = false;
        gunStencilClearSkipped = false;
        return skipped;
    }

    public static boolean shouldKeepGunStencil() {
        return keepGunStencil;
    }

    public static void markGunStencilClearSkipped() {
        gunStencilClearSkipped = true;
    }

    // =========================================================================
    // レンズ形状の補正（TsoPipLensRendererMixin から使用）
    // =========================================================================

    /** 補正の対象にする ocular パーツ（メッシュローダーで読み込んだスコープのもの）。弱参照で保持 */
    private static final java.util.Set<com.tacz.guns.client.model.bedrock.BedrockPart> CORRECTED_OCULARS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** メッシュローダーで読み込んだスコープの ocular パーツを登録する（モデル読み込み時） */
    public static void registerOcular(com.tacz.guns.client.model.bedrock.BedrockPart ocularPart) {
        if (ocularPart != null) CORRECTED_OCULARS.add(ocularPart);
    }

    private static java.lang.reflect.Constructor<?> geometryConstructor = null;
    private static boolean geometryConstructorFailed = false;

    /**
     * TSO の computeOcularGeometry の結果を、向きに依存しない計算で作り直す。
     *
     * <h3>なぜ必要か</h3>
     * TSO は「ocular のキューブはボーンのローカル座標の XY 平面に、回転なしで置かれている」
     * 前提で、ボーン直下のキューブの X / Y の幅からレンズの大きさを、ピボット位置から中心を求める。
     * ところが、ocular ボーン（またはその親）を Y 軸で 90 度回して YZ 平面にレンズを作った
     * モデルや、回転付きキューブ（TacZ では子パーツになるため TSO の計算から漏れる）で
     * ocular を作ったモデルでは、レンズがとても小さく・ずれて計算される。すると覗き穴が欠け、
     * 開口部の測定値が毎フレーム揺れ、それに合わせて銃の引き寄せ量（照準位置）が揺れ続ける。
     *
     * <h3>計算方法</h3>
     * ocular パーツ配下のすべてのキューブ（回転付きの子パーツ・メッシュの範囲を表す見えない
     * キューブを含む）の角を、TSO と同じ座標系に変換し、その範囲の中心と幅から
     * 中心・半径・画面上の位置と大きさを求める。結果の形式は TSO の元の計算と同じ。
     *
     * @return 補正後の OcularGeometry。補正しない（できない）場合は original をそのまま返す
     */
    public static Object correctOcularGeometry(Object original, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                               java.util.List<com.tacz.guns.client.model.bedrock.BedrockPart> path) {
        if (original == null || path == null || path.isEmpty()) return original;
        com.tacz.guns.client.model.bedrock.BedrockPart leaf = path.get(path.size() - 1);
        if (!CORRECTED_OCULARS.contains(leaf)) return original;

        java.lang.reflect.Constructor<?> ctor = geometryConstructor(original.getClass());
        if (ctor == null) return original;

        // TSO と同じ座標系（パスの全パーツの変換を適用した姿勢）で、ocular 配下の範囲を求める
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        poseStack.pushPose();
        try {
            for (com.tacz.guns.client.model.bedrock.BedrockPart part : path) {
                part.translateAndRotateAndScale(poseStack);
            }
            collectBounds(poseStack, leaf, b, true);
        } finally {
            poseStack.popPose();
        }
        if (b[0] > b[3]) return original;

        float centerX = (b[0] + b[3]) * 0.5f, centerY = (b[1] + b[4]) * 0.5f, centerZ = (b[2] + b[5]) * 0.5f;
        float halfX = (b[3] - b[0]) * 0.5f, halfY = (b[4] - b[1]) * 0.5f;
        float depth = -centerZ;
        if (!(depth > 1.0e-4f) || !(halfX > 0) || !(halfY > 0)) return original;

        // TacZ / TSO の「z = -90 の平面に描く円」の座標。目からの方向が実際のレンズ中心と一致するように縮尺を決める
        float scale = 90.0f / depth;
        float cx = centerX * scale;
        float cy = centerY * scale;
        float radius = (halfX + halfY) * 0.5f * scale;

        // 画面上の位置と大きさ（TSO の元の計算と同じ方法で投影）
        org.joml.Matrix4f toClip = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix())
                .mul(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
        com.mojang.blaze3d.pipeline.RenderTarget target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        float w = target.width, h = target.height;
        org.joml.Vector4f c = toClip.transform(new org.joml.Vector4f(centerX, centerY, centerZ, 1.0f));
        org.joml.Vector4f ex = toClip.transform(new org.joml.Vector4f(centerX + halfX, centerY, centerZ, 1.0f));
        org.joml.Vector4f ey = toClip.transform(new org.joml.Vector4f(centerX, centerY + halfY, centerZ, 1.0f));
        float screenX = 0, screenY = 0, screenRadius = 0, screenOuterRadius = 0;
        if (c.w > 0 && ex.w > 0 && ey.w > 0) {
            screenX = (c.x / c.w * 0.5f + 0.5f) * w;
            screenY = (c.y / c.w * 0.5f + 0.5f) * h;
            float sxX = (ex.x / ex.w * 0.5f + 0.5f) * w, sxY = (ex.y / ex.w * 0.5f + 0.5f) * h;
            float syX = (ey.x / ey.w * 0.5f + 0.5f) * w, syY = (ey.y / ey.w * 0.5f + 0.5f) * h;
            float hx = (float) Math.hypot(sxX - screenX, sxY - screenY);
            float hy = (float) Math.hypot(syX - screenX, syY - screenY);
            screenRadius = Math.min(hx, hy);
            screenOuterRadius = (float) Math.hypot(hx, hy);
        }
        float[] box = new float[4];
        projectBox(toClip, b, w, h, box);

        try {
            return ctor.newInstance(cx, cy, -90.0f, radius, screenX, screenY, screenRadius, screenOuterRadius, box, depth);
        } catch (ReflectiveOperationException | RuntimeException e) {
            geometryConstructorFailed = true;
            geometryConstructor = null;
            return original;
        }
    }

    private static java.lang.reflect.Constructor<?> geometryConstructor(Class<?> geometryClass) {
        if (geometryConstructor != null) return geometryConstructor;
        if (geometryConstructorFailed) return null;
        try {
            java.lang.reflect.Constructor<?> ctor = geometryClass.getDeclaredConstructor(
                    float.class, float.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float[].class, float.class);
            ctor.setAccessible(true);
            geometryConstructor = ctor;
            return ctor;
        } catch (ReflectiveOperationException | RuntimeException e) {
            geometryConstructorFailed = true;
            return null;
        }
    }

    /** パーツ（と子パーツ）のキューブの 8 頂点を、現在の姿勢で変換して範囲 b に加える */
    private static void collectBounds(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                      com.tacz.guns.client.model.bedrock.BedrockPart part, float[] b, boolean alreadyTransformed) {
        poseStack.pushPose();
        if (!alreadyTransformed) part.translateAndRotateAndScale(poseStack);
        org.joml.Matrix4f pose = poseStack.last().pose();
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (com.tacz.guns.client.model.bedrock.BedrockCube cube : part.cubes) {
            float x0, y0, z0, x1, y1, z1;
            if (cube instanceof com.tacz.guns.client.model.bedrock.BedrockCubeBox box) {
                x0 = box.minX; y0 = box.minY; z0 = box.minZ; x1 = box.maxX; y1 = box.maxY; z1 = box.maxZ;
            } else if (cube instanceof com.tacz.guns.client.model.bedrock.BedrockCubePerFace face) {
                x0 = face.minX; y0 = face.minY; z0 = face.minZ; x1 = face.maxX; y1 = face.maxY; z1 = face.maxZ;
            } else {
                continue;
            }
            for (int i = 0; i < 8; i++) {
                pose.transformPosition(((i & 1) == 0 ? x0 : x1) / 16.0f, ((i & 2) == 0 ? y0 : y1) / 16.0f,
                        ((i & 4) == 0 ? z0 : z1) / 16.0f, v);
                b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
                b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
            }
        }
        for (com.tacz.guns.client.model.bedrock.BedrockPart child : part.children) {
            collectBounds(poseStack, child, b, false);
        }
        poseStack.popPose();
    }

    /** 範囲 b の 8 頂点を画面に投影した外接矩形（TSO の box と同じ形式） */
    private static void projectBox(org.joml.Matrix4f toClip, float[] b, float w, float h, float[] out) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        org.joml.Vector4f p = new org.joml.Vector4f();
        for (int i = 0; i < 8; i++) {
            p.set((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5], 1.0f);
            toClip.transform(p);
            if (p.w <= 0) {
                java.util.Arrays.fill(out, 0);
                return;
            }
            float x = (p.x / p.w * 0.5f + 0.5f) * w, y = (p.y / p.w * 0.5f + 0.5f) * h;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        out[0] = x0; out[1] = y0; out[2] = x1; out[3] = y1;
    }

    /**
     * TSO の遅延描画用 Runnable を包み、実行中だけ {@link #isInDeferredWriter()} を true にする。
     */
    public static Runnable wrapDeferredWriter(Runnable original) {
        if (original == null) return null;
        return () -> {
            deferredWriterDepth++;
            try {
                original.run();
            } finally {
                deferredWriterDepth--;
            }
        };
    }
}
