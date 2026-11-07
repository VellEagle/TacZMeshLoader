package com.example.taczmeshloader.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * モデルファイル（geo_models/...json）にメッシュ（"poly_mesh"）が書かれているかを素早く調べる補助クラス。
 *
 * <p>TacZ のモデルファイルは geo_models/ に置かれるため、キューブだけの普通のモデルでも
 * ファイル自体は必ず存在する。「ファイルがあるか」ではメッシュの有無を判定できないので、
 * TML 用のモデルを作る前に、ファイルの中に "poly_mesh" という項目があるかを確認する。
 * （モデルを作り直す重い処理を、メッシュの無いモデルでは一切行わないようにするため）</p>
 */
public final class GeoMeshProbe {

    private static final String KEY = "\"poly_mesh\"";

    private GeoMeshProbe() {}

    /** ファイルが存在し、かつ "poly_mesh" を含むなら true。読み込みに失敗した場合は false。 */
    public static boolean containsPolyMesh(ResourceLocation geoPath) {
        if (geoPath == null) return false;
        try {
            Optional<net.minecraft.server.packs.resources.Resource> resource =
                    Minecraft.getInstance().getResourceManager().getResource(geoPath);
            if (resource.isEmpty()) return false;
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(KEY);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
