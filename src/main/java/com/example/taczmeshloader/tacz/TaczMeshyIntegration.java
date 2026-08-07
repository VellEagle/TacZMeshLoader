package com.example.taczmeshloader.tacz;

import com.tacz.guns.api.client.other.GunModelTypeManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * TacZ と MeshyLoader の統合ヘルパー。
 *
 * <h3>MOD 開発者（MeshyLoader を組み込む側）がやること</h3>
 * FMLClientSetupEvent のリスナーに以下を追加するだけ:
 * <pre>{@code
 * modEventBus.addListener(TaczMeshyIntegration::onClientSetup);
 * }</pre>
 *
 * <h3>アドオン制作者がやること（Java 不要）</h3>
 * display JSON の "model_type" を "mesh" にするだけ。
 * モデルファイル自体は通常の TacZ gunpack と同じ場所・同じ形式でよい。
 *
 * <pre>{@code
 * // guns/display/mygun_display.json
 * {
 *   "model_type": "meshy",         // ← ここだけ変える
 *   "model": "mypack:models/gun/mygun_geo.json",
 *   "texture": "mypack:textures/gun/uv/mygun.png",
 *   "animation": "mypack:animations/mygun.animation.json"
 * }
 * }</pre>
 *
 * <h3>poly_mesh の読み込みタイミング</h3>
 * GunDisplayInstance がモデルをロードした直後に {@link TaczPolyMeshGunModel#loadPolyMesh(ResourceLocation)}
 * が呼ばれる必要がある。
 *
 * 現在の実装では GunDisplayInstance のモデルロードフローに直接フックできないため、
 * 初回レンダリング時（getGunModel() が null でなくなった後）に遅延初期化する方式を取る。
 * これは GunItemRendererWrapper を Mixin または継承でオーバーライドすることで実現できる。
 *
 * シンプルな代替案として、TacZ 側のコードが公開 API であれば
 * GunModelTypeManager のコンストラクタ BiFunction に ResourceLocation を渡す方法を使う。
 * 現状の TacZ API は (BedrockModelPOJO, BedrockVersion) -> BedrockGunModel のため、
 * ResourceLocation を取得するにはイベント or Mixin が必要。
 *
 * @see TaczPolyMeshGunModel
 */
@OnlyIn(Dist.CLIENT)
public class TaczMeshyIntegration {

    /**
     * FMLClientSetupEvent のリスナーとして登録する。
     */
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(TaczPolyMeshGunModel::register);
        // MeshyLoader 独自の Accelerated Rendering 対応の初期化。
        // TacZ 本体の ARCompat::init と同じタイミング（FMLClientSetupEvent）で呼ぶ。
        event.enqueueWork(com.example.taczmeshloader.compat.ar.ARCompat::init);
    }
}
