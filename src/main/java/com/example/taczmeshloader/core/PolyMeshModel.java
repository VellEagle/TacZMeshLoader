package com.example.taczmeshloader.core;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.Util;

import java.util.*;
import java.util.function.Function;

@OnlyIn(Dist.CLIENT)
public class PolyMeshModel {
    private final IPolyMeshBone root;
    private final Map<String, List<PolyMesh>> meshMap = new HashMap<>();
    private final Set<String> translucentBones = new HashSet<>();
    private final boolean hasTranslucent;
    private final Set<String> meshAncestorBones = new HashSet<>();

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_CULL =
            Util.memoize(RenderType::entityTranslucentCull);

    public PolyMeshModel(IPolyMeshBone root, JsonObject rawJson) {
        this.root = root;
        parsePolyMeshes(rawJson);
        for (String name : meshMap.keySet()) {
            if (name.toLowerCase().contains("translucent")) translucentBones.add(name);
        }
        this.hasTranslucent = !translucentBones.isEmpty();
        buildMeshAncestors(this.root, new ArrayDeque<>());
    }

    public boolean hasTranslucentMeshes() { return hasTranslucent; }

    private boolean buildMeshAncestors(IPolyMeshBone bone, Deque<String> path) {
        String name = bone.getName();
        path.addLast(name);
        boolean has = meshMap.containsKey(name);
        for (IPolyMeshBone child : bone.getChildren()) if (buildMeshAncestors(child, path)) has = true;
        if (has) meshAncestorBones.addAll(path);
        path.removeLast();
        return has;
    }

    private void parsePolyMeshes(JsonObject rawJson) {
        JsonArray geometries = rawJson.has("minecraft:geometry") ? rawJson.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) return;
        JsonObject geo = geometries.get(0).getAsJsonObject();
        float texW = geo.getAsJsonObject("description").get("texture_width").getAsFloat();
        float texH = geo.getAsJsonObject("description").get("texture_height").getAsFloat();
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) return;
        for (JsonElement boneElem : bones) {
            JsonObject boneObj = boneElem.getAsJsonObject();
            if (!boneObj.has("poly_mesh") || !boneObj.has("name")) continue;
            String name = boneObj.get("name").getAsString();
            float pX=0, pY=0, pZ=0;
            if (boneObj.has("pivot")) {
                JsonArray p = boneObj.getAsJsonArray("pivot");
                pX=p.get(0).getAsFloat(); pY=p.get(1).getAsFloat(); pZ=p.get(2).getAsFloat();
            }
            PolyMesh mesh = new PolyMesh(boneObj.getAsJsonObject("poly_mesh"), texW, texH, new float[]{pX,pY,pZ});
            if (mesh.getVertexCount() > 0) meshMap.computeIfAbsent(name, k -> new ArrayList<>()).add(mesh);
        }
    }

    // Render entry points

    public void renderWithTranslucentSplit(PoseStack ps, MultiBufferSource buf,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderCutoutOnly(ps, buf, tex, light, overlay, useVBO);
        if (hasTranslucent) renderTranslucentOnly(ps, buf, tex, light, overlay, useVBO);
    }

    public void renderCutoutOnly(PoseStack ps, MultiBufferSource buf,
                                 ResourceLocation tex, int light, int overlay, boolean useVBO) {
        if (useVBO && allVboReady(light)) {
            renderVBO(ps, tex, light, false);
        } else {
            if (useVBO) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, false);
        }
    }

    public void renderTranslucentOnly(PoseStack ps, MultiBufferSource buf,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        if (!hasTranslucent) return;
        if (useVBO && allVboReady(light)) {
            renderVBO(ps, tex, light, true);
        } else {
            if (useVBO) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(TRANSLUCENT_CULL.apply(tex));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, true);
        }
    }

    // VBO rendering path

    private boolean allVboReady(int light) {
        if (meshMap.isEmpty()) return false;
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) { if (!m.isVboReady(light)) return false; }
        }
        return true;
    }

    private void ensureAllUploaded(int light) {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.ensureUploaded(light);
        }
    }

    private void renderVBO(PoseStack ps, ResourceLocation tex, int light, boolean translucentPass) {
        RenderType renderType = translucentPass
                ? TRANSLUCENT_CULL.apply(tex)
                : RenderType.entityCutoutNoCull(tex);

        renderType.setupRenderState();
        renderBonesVBO(root, ps, light, translucentPass);
        renderType.clearRenderState();
    }

    private void renderBonesVBO(IPolyMeshBone bone, PoseStack ps, int light, boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = bone.isIlluminated() ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    mesh.drawVBO(ps.last().pose(), actualLight);
                }
            }
        }

        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesVBO(child, ps, light, translucentPass);
        }

        ps.popPose();
    }

    // VertexConsumer fallback path

    private void renderBonesConsumer(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = bone.isIlluminated() ? 15728880 : light;
                for (PolyMesh mesh : meshes) mesh.compileConsumer(ps.last(), buf, actualLight, overlay, r, g, b, a);
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesConsumer(child, ps, buf, light, overlay, r, g, b, a, translucentPass);
        }

        ps.popPose();
    }

    public void close() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) {
                if (m != null) m.close();
            }
        }
        meshMap.clear();
        translucentBones.clear();
        meshAncestorBones.clear();
    }
}