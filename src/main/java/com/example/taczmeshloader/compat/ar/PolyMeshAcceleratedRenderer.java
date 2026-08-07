package com.example.taczmeshloader.compat.ar;

import com.example.taczmeshloader.core.PolyMesh;
import com.github.argon4w.acceleratedrendering.core.CoreFeature;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IBufferGraph;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * 1つの {@link PolyMesh}（1ボーン分の poly_mesh ジオメトリ）を AR の
 * アクセラレーションパイプラインに載せるためのラッパー。
 *
 * <p>TacZ 本体の {@code com.tacz.guns.mixin.client.ar.BedrockPartMixin} や
 * Accelerated Rendering 本体の {@code BedrockPartMixin}（simplebedrockmodel 用）と
 * 全く同じ設計:</p>
 * <ul>
 *   <li>ローカル座標（アイデンティティ姿勢）で一度だけジオメトリをビルドし、
 *       描画先バッファ（{@link IBufferGraph}）ごとに {@link IMesh} としてキャッシュする。</li>
 *   <li>実際のワールド変換・ライト・色は {@code extension.beginTransform()} /
 *       {@code mesh.write()} 経由で描画のたびに GPU 側へ渡す。これにより同じ
 *       PolyMesh を複数インスタンス（地面に落ちた複数の銃など）分バッチ描画できる。</li>
 * </ul>
 *
 * <p>ジオメトリのビルドには {@link PolyMesh#compileConsumer} をそのまま再利用する。
 * これは既存の VertexConsumer フォールバック経路と全く同じ頂点データ
 * （法線の向き・UV・フラットシェーディング設定を含む）を生成するため、
 * AR 加速時と非加速時で見た目が変わらないことが保証される。</p>
 */
public final class PolyMeshAcceleratedRenderer implements IAcceleratedRenderer<Void> {

    /** ジオメトリをローカル座標のまま焼き込むための恒等姿勢。 */
    private static final PoseStack.Pose IDENTITY_POSE = new PoseStack().last();

    private final PolyMesh polyMesh;
    private final Map<IBufferGraph, IMesh> meshes = new Object2ObjectOpenHashMap<>();

    PolyMeshAcceleratedRenderer(PolyMesh polyMesh) {
        this.polyMesh = polyMesh;
    }

    /** Oculus のシェーダーパック切り替え時など、キャッシュ済み AR メッシュを破棄する。 */
    void invalidateCache() {
        meshes.clear();
    }

    /**
     * {@link ARCompatImpl#render} から呼ばれるエントリポイント。
     * AR の doRender() 経由でこの PolyMesh を描画キューに投入する。
     */
    void doRender(IAcceleratedVertexConsumer extension, Matrix4f transform, Matrix3f normal,
                  int light, int overlay, int color) {
        extension.doRender(this, null, transform, normal, light, overlay, color);
    }

    @Override
    public void render(
            VertexConsumer vertexConsumer,
            Void context,
            Matrix4f transform,
            Matrix3f normal,
            int light,
            int overlay,
            int color
    ) {
        var extension = VertexConsumerExtension.getAccelerated(vertexConsumer);
        var mesh = meshes.get(extension);

        extension.beginTransform(transform, normal);

        if (mesh != null) {
            mesh.write(extension, color, light, overlay);
            extension.endTransform();
            return;
        }

        if (polyMesh.getVertexCount() == 0) {
            extension.endTransform();
            return;
        }

        var meshCollector = CoreFeature.createMeshCollector(extension);
        var meshBuilder = extension.decorate(meshCollector);

        // ローカル座標のまま（アイデンティティ姿勢・light=0）でジオメトリを書き込む。
        // 実際のワールド変換は beginTransform()、実際のライト値は下の mesh.write() が担う。
        polyMesh.compileConsumer(IDENTITY_POSE, meshBuilder, 0, overlay, 1.0f, 1.0f, 1.0f, 1.0f);

        meshCollector.flush();

        mesh = AcceleratedEntityRenderingFeature
                .getMeshType()
                .getBuilder()
                .build(meshCollector);

        meshes.put(extension, mesh);
        mesh.write(extension, color, light, overlay);

        extension.endTransform();
    }
}
