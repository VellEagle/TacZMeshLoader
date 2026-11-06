plugins {
    id("net.neoforged.moddev") version "2.0.+"
}

val modId = "taczmeshloader"
group = "com.example"
version = "1.0.0"

base {
    archivesName.set("taczmeshloader-neoforge-1.21.1")
}

neoForge {
    version = "21.1.233"   // 必要に応じて更新

    parchment {
        mappingsVersion = "2024.11.17"
        minecraftVersion = "1.21.1"
    }

    mods {
        create(modId) {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        create("client") {
            client()
        }
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.rtyley")
            includeGroup("com.github.FiguraMC.luaj")
            // 【TacZ 1.2.0（公式 NeoForge 版）対応】SimpleBedrockModel（SBM）の取得元（TacZ 公式と同じ）
            includeGroup("com.github.mcmodderanchor")
        }
    }
    maven("https://maven.shedaniel.me")
    maven("https://maven.kosmx.dev")
    maven("https://maven.blamejared.com")
    maven {
        url = uri("https://maven.architectury.dev")
        content {
            includeGroup("dev.architectury")
        }
    }
    maven {
        url = uri("https://maven.latvian.dev/releases")
        content {
            includeGroup("dev.latvian.mods")
            includeGroup("dev.latvian.apps")
        }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "CurseForge"
                url = uri("https://cursemaven.com")
            }
        }
        filter {
            includeGroup("curse.maven")
        }
    }
    flatDir {
        dir("libs")
    }
}

dependencies {
    // 【TacZ 1.2.0（公式 NeoForge 版）対応】MUKSC 氏の非公式移植版から、TacZ 公式の NeoForge 1.21.1 版に切り替えた。
    // 9454361 = tacz-neoforge-1.21.1-1.2.0.jar（CurseForge）
    // 旧: implementation("curse.maven:tacz-1-21-1-1353462:8167430")  ← MUKSC 版
    implementation("curse.maven:timeless-and-classics-zero-1028108:9454361")
    // TacZ 1.2.0 が内部で使う SimpleBedrockModel（ビルド時と開発環境の起動用。実際の配布物では TacZ に同梱されている）
    implementation("com.github.mcmodderanchor:simplebedrockmodel:2.6.2-neoforge-mc1.21.1")

    implementation("curse.maven:accelerated-rendering-1314021:8448200")

    implementation("curse.maven:lr-tactical-1-21-1-1432620:8745260")

}
