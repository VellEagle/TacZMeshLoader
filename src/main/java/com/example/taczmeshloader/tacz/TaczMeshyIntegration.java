package com.example.taczmeshloader.tacz;

/**
 * TacZ と MeshyLoader の統合ヘルパー。
 * Fabric版: onInitializeClient() から直接呼ぶ。
 */
public class TaczMeshyIntegration {

    public static void onClientSetup() {
        TaczPolyMeshGunModel.register();
        // MeshyLoader 独自の Accelerated Rendering 対応の初期化。
        // TacZ 本体の ARCompat::init と同じタイミングで呼ぶ。
        // Fabric 版 TacZ の ARCompat.shouldAccelerate() は（NeoForge 1.21.1 版とは異なり）
        // スタブ化されておらず実際に機能するため、AR が導入されていれば有効になる。
        com.tacz.guns.compat.ar.ARCompat.init();
        // ModernFix の mixin.perf.clear_mixin_classinfo 向けの予防措置
        preloadLateMixinTargets();
    }

    /**
     * Mixin の対象クラスのうち、TacZ がワールド参加時まで読み込まないクラスを、
     * 起動処理中にレンダースレッド上で先に読み込んでおく。
     *
     * <p>ModernFix の {@code mixin.perf.clear_mixin_classinfo} を有効にしていると、
     * タイトル画面の表示直後に、まだ読み込まれていない Mixin 対象クラスが
     * バックグラウンドスレッドでまとめて強制的に読み込まれる。
     * BedrockAttachmentModel（BedrockAttachmentModelMixin の対象）はワールド参加時まで
     * 読み込まれないため、それを避けるための予防措置として先に読み込んでいる。</p>
     *
     * <p>【経緯】当初はタイトル画面でのクラッシュの原因と考えて追加したが、
     * 実際の原因は Quantified API（QAPI）との相性であり、この処理とは無関係だった。
     * 動作への悪影響は無いため、予防措置として残している。</p>
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
