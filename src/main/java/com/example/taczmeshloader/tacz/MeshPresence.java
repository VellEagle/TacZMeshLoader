package com.example.taczmeshloader.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;

/**
 * 銃（と装着中のアタッチメント）が TML のメッシュ（poly_mesh）を使っているかを判定する補助クラス。
 *
 * <h3>TacZ Refabricated 1.2.0 対応で追加</h3>
 * TacZ 1.2.0 では、三人称で持った銃・地面に落ちた銃・額縁/展示ケース/ペグボードの銃を、
 * 銃パックのモデルデータから別途作った「外部モデル」を SBM の VBO にまとめて焼き込んで描く
 * ようになった（初期値オン）。この外部モデルはキューブだけから作られるため、
 * TML のメッシュは一切含まれず、メッシュのみの銃は見えなくなってしまう。
 * そこで、メッシュを使う銃に限ってこの焼き込みを断り、TML がメッシュを描ける
 * 従来の描画経路に戻すための判定に使う（キューブだけの銃は TacZ の高速化をそのまま使う）。
 */
@Environment(EnvType.CLIENT)
public final class MeshPresence {

    private MeshPresence() {}

    /** この銃アイテムの描画に TML のメッシュが関わるなら true。 */
    public static boolean gunUsesMesh(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) return false;

        Optional<GunDisplayInstance> display = TimelessAPI.getGunDisplay(stack);
        if (display.isPresent()) {
            GunDisplayInstance instance = display.get();
            if (isMeshGun(instance.getGunModel())) return true;
            Pair<BedrockGunModel, ResourceLocation> lod = instance.getLodModel();
            if (lod != null && isMeshGun(lod.getLeft())) return true;
        }

        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) continue;
            // 1.21.1 版の TacZ は getAttachment に登録データ（Provider）の引数が必要
            // （TacZ 自身の DisplayGunGeometry と同じ取得方法を使う）
            ItemStack attachment = gun.getAttachment(
                    com.tacz.guns.client.util.ClientRegistryAccess.registries(), stack, type);
            if (attachment.isEmpty()) {
                attachment = gun.getBuiltinAttachment(stack, type);
            }
            if (attachment.isEmpty()) continue;
            IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachment);
            if (iAttachment == null) continue;
            Optional<ClientAttachmentIndex> index = TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachment));
            if (index.isPresent()) {
                if (isMeshAttachment(index.get().getAttachmentModel())) return true;
                Pair<BedrockAttachmentModel, ResourceLocation> lod = index.get().getLodModel();
                if (lod != null && isMeshAttachment(lod.getLeft())) return true;
            }
        }
        return false;
    }

    private static boolean isMeshGun(BedrockGunModel model) {
        return model instanceof TaczPolyMeshGunModel mesh && mesh.hasMeshData();
    }

    private static boolean isMeshAttachment(BedrockAttachmentModel model) {
        return model instanceof TaczPolyMeshAttachmentModel mesh && mesh.hasMeshData();
    }
}
