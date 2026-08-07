package com.example.taczmeshloader.compat.ar;

import com.example.taczmeshloader.core.PolyMesh;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * AR (Accelerated Rendering) の型を直接参照する実装クラス。
 *
 * <p><b>重要:</b> このクラスは {@link ARCompat#LOADED} が true の場合にのみ
 * {@link ARCompat} 経由で呼び出されること。AR が存在しない環境でこのクラスの
 * メソッドが実行される（＝クラスがロードされる）ことは無いため、
 * NoClassDefFoundError は発生しない。</p>
 *
 * <p>このクラス自身の公開メソッドのシグネチャにも AR 固有の型を出さず、
 * VertexConsumer / Matrix4f / Matrix3f / int / Object のみを使う（メソッド本体の
 * ローカル変数は {@code var} で受けて型推論に任せる）。これは念のための多重の
 * 安全対策であり、TacZ 本体の ARCompatImpl と同じ流儀。</p>
 */
final class ARCompatImpl {

    private ARCompatImpl() {
    }

    static boolean isAccelerated(VertexConsumer vertexConsumer) {
        return VertexConsumerExtension.getAccelerated(vertexConsumer).isAccelerated();
    }

    static Object createRenderer(PolyMesh polyMesh) {
        return new PolyMeshAcceleratedRenderer(polyMesh);
    }

    static boolean render(Object rendererHandle, VertexConsumer consumer,
                           Matrix4f transform, Matrix3f normal,
                           int light, int overlay, int color) {
        if (!(rendererHandle instanceof PolyMeshAcceleratedRenderer renderer)) {
            return false;
        }

        var extension = VertexConsumerExtension.getAccelerated(consumer);
        if (!extension.isAccelerated()) {
            return false;
        }

        renderer.doRender(extension, transform, normal, light, overlay, color);
        return true;
    }

    static void invalidate(Object rendererHandle) {
        if (rendererHandle instanceof PolyMeshAcceleratedRenderer renderer) {
            renderer.invalidateCache();
        }
    }
}
