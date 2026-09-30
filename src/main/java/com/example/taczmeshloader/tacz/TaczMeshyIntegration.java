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
        // ModernFix 互換（mixin.perf.clear_mixin_classinfo 対策）
        event.enqueueWork(TaczMeshyIntegration::preloadLateMixinTargets);
    }

    /**
     * Mixin の対象クラスのうち、TacZ がワールド参加時まで読み込まないクラスを、
     * 起動処理中にレンダースレッド上で先に読み込んでおく。
     *
     * <p>ModernFix の {@code mixin.perf.clear_mixin_classinfo} を有効にしていると、
     * タイトル画面の表示直後に、まだ読み込まれていない Mixin 対象クラスが
     * バックグラウンドスレッドでまとめて強制的に読み込まれる。
     * BedrockAttachmentModel（BedrockAttachmentModelMixin の対象）はワールド参加時まで
     * 読み込まれないため、このタイミングで別スレッドから読み込まれてしまい、
     * 環境によってはタイトル画面でクラッシュ／フリーズする原因になっていた。</p>
     *
     * <p>ここで先に読み込んでおけば、ModernFix の処理時点では既に Mixin 適用済みなので
     * 強制読み込みの対象から外れる。ModernFix が無い環境でも、本来ワールド参加時に
     * 行われる読み込みが少し早まるだけで、動作には影響しない。</p>
     */
    private static void preloadLateMixinTargets() {
        final String[] targets = {
                "com.tacz.guns.client.model.BedrockAttachmentModel",
        };
        for (String name : targets) {
            try {
                Class.forName(name, true, TaczMeshyIntegration.class.getClassLoader());
                org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                        .info("[TacZMeshLoader] Preloaded mixin target: {}", name);
            } catch (Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                        .warn("[TacZMeshLoader] Failed to preload mixin target: {}", name, t);
            }
        }
    }
}
