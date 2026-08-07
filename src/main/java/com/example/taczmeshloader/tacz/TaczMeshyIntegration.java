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
    }
}
