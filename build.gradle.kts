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
    implementation("curse.maven:tacz-1-21-1-1353462:8167430")

    implementation("curse.maven:accelerated-rendering-1314021:8448200")

}
