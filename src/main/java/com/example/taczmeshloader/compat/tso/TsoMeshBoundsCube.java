package com.example.taczmeshloader.compat.tso;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * メッシュのみで作られた ocular / ocular_ring / division パーツに追加する「見えないキューブ」。
 *
 * <p>TSO（TaCZ: Scope Overhaul）はレンズの位置・大きさをパーツのキューブの範囲
 * （{@code BedrockCubeBox} の min/max）から計算するため、メッシュと同じ範囲を持つ
 * キューブを置いておくことで、メッシュのパーツでも正しく計算されるようにする。</p>
 *
 * <p>通常は何も描かない（TacZ の描画やステンシル書き込みには一切影響しない）。
 * TSO の遅延描画（シェーダー使用時の ocular / division の描き直し）の実行中だけ、
 * キューブの代わりにメッシュを描く。</p>
 *
 * <p>TSO が導入されているときだけ追加される（{@link TsoCompat#isLoaded()}）。</p>
 */
@OnlyIn(Dist.CLIENT)
public class TsoMeshBoundsCube extends BedrockCubeBox {
    private final TaczPolyMeshAttachmentModel owner;
    private final String boneName;

    /**
     * @param bounds メッシュの範囲（ボーンのローカル座標・ピクセル単位）{minX, minY, minZ, maxX, maxY, maxZ}
     */
    public TsoMeshBoundsCube(TaczPolyMeshAttachmentModel owner, String boneName, float[] bounds) {
        super(0, 0,
                bounds[0], bounds[1], bounds[2],
                bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2],
                0, false, 16, 16);
        this.owner = owner;
        this.boneName = boneName;
    }

    @Override
    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
                        float red, float green, float blue, float alpha) {
        // 通常は何も描かない。TSO の遅延描画中だけ、このパーツのメッシュを描く。
        if (TsoCompat.isInDeferredWriter()) {
            owner.renderTsoDeferredMesh(boneName, pose, consumer, light, overlay, red, green, blue, alpha);
        }
    }
}
