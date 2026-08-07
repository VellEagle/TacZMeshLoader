package com.example.taczmeshloader.compat.ar;

import com.example.taczmeshloader.core.PolyMesh;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Accelerated Rendering (AR) との統合ポイント。
 *
 * <h3>設計方針</h3>
 * TacZ 本体の {@code com.tacz.guns.compat.ar.ARCompat} と全く同じパターンを踏襲する:
 * <ul>
 *   <li>このクラス自身のメソッドシグネチャには AR 固有の型を一切登場させない
 *       （引数・戻り値は VertexConsumer / Matrix4f / Matrix3f / int / boolean / Object のみ）。</li>
 *   <li>AR のクラスを実際に参照する処理はすべて {@link ARCompatImpl} に分離する。</li>
 *   <li>{@link #LOADED} が true の場合にのみ ARCompatImpl を呼び出す。</li>
 * </ul>
 * これにより、AR がインストールされていない環境でも {@link ARCompatImpl}
 * （ひいては AR 自体のクラス）は一切ロードされず、NoClassDefFoundError を起こさない。
 *
 * <p>MeshyLoader 側は PolyMesh / PolyMeshModel から常にこのクラスのみを参照し、
 * ARCompatImpl や AR 固有の型を直接 import してはならない。</p>
 */
public final class ARCompat {

    public static final String MOD_ID = "acceleratedrendering";

    /** AR がロードされているかどうか。{@link #init()} が呼ばれるまでは false 扱い。 */
    public static boolean LOADED = false;

    private ARCompat() {
    }

    /**
     * FMLClientSetupEvent 内から呼ぶこと（{@code event.enqueueWork(ARCompat::init)}）。
     * TacZ 本体の ARCompat.init() と同じタイミングで呼べば十分安全。
     */
    public static void init() {
        LOADED = ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * 指定した VertexConsumer が現在 AR によってアクセラレーションされているかどうか。
     * AR がロードされていない場合は常に false。
     */
    public static boolean isAccelerated(VertexConsumer vertexConsumer) {
        return LOADED && ARCompatImpl.isAccelerated(vertexConsumer);
    }

    /**
     * PolyMesh 用の AR レンダラーハンドルを新規作成する。
     * 戻り値は不透明な Object（実体は AR 固有の IAcceleratedRenderer 実装）。
     * AR がロードされていない場合は null。
     *
     * <p>呼び出し側（PolyMesh）はこの戻り値の中身を一切気にせず、
     * {@link #render} / {@link #invalidate} にそのまま渡すだけでよい。</p>
     */
    public static Object createRenderer(PolyMesh polyMesh) {
        return LOADED ? ARCompatImpl.createRenderer(polyMesh) : null;
    }

    /**
     * {@link #createRenderer} で作成したハンドルを使って AR 経由の描画を試みる。
     *
     * @return 実際に AR 経由で描画できた場合 true。
     *         AR 未ロード・ハンドルが null・対象バッファが非アクセラレーションの場合は false
     *         （呼び出し側は false の場合、従来の VBO / VertexConsumer 描画にフォールバックすること）。
     */
    public static boolean render(Object rendererHandle, VertexConsumer consumer,
                                  Matrix4f transform, Matrix3f normal,
                                  int light, int overlay, int color) {
        if (!LOADED || rendererHandle == null) {
            return false;
        }
        return ARCompatImpl.render(rendererHandle, consumer, transform, normal, light, overlay, color);
    }

    /**
     * ハンドルが保持している AR 側メッシュキャッシュを破棄する。
     * Oculus のシェーダーパック切り替え時など、PolyMesh 側の VBO キャッシュ破棄と
     * 同じタイミングで呼ぶこと。
     */
    public static void invalidate(Object rendererHandle) {
        if (LOADED && rendererHandle != null) {
            ARCompatImpl.invalidate(rendererHandle);
        }
    }
}
