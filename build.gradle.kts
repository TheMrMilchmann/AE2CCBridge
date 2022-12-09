import io.github.themrmilchmann.gradle.publish.curseforge.*

plugins {
    java
    alias(libs.plugins.curseforge.publish)
    alias(libs.plugins.loom)
}

version = "0.0.2-1.18.2-FABRIC-0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(19))
    }
}

tasks {
    compileJava {
        options.release.set(17)
    }
}

publishing {
    repositories {
        curseForge {
//            apiKey.set(deployment.cfApiKey)
        }
    }
    publications {
        create<CurseForgePublication>("curseForge") {
            projectID.set(715346) // https://www.curseforge.com/minecraft/mc-mods/ae2cc-bridge

            artifact {
                changelog = Changelog("", ChangelogType.TEXT) // TODO
                displayName = "AE2CC Bridge ${project.version}"
                releaseType = ReleaseType.RELEASE
            }
        }
    }
}

//fun changelog(): Changelog {
//    if (deployment.type == BuildType.SNAPSHOT) return Changelog("", ChangelogType.TEXT)
//
//    val mc = project.version.toString() // E.g. 1.0.0-1.16.5-1.0
//        .substringAfter('-')            //            1.16.5-1.0
//        .substringBefore('-')           //            1.16.5
//        .let {
//            if (it.count { it == '.' } == 1)
//                it
//            else
//                it.substringBeforeLast('.')
//        }                               //            1.16
//
//    return Changelog(
//        content = File(rootDir, "docs/changelog/$mc/${project.version}.md").readText(),
//        type = ChangelogType.MARKDOWN
//    )
//}

repositories {
    mavenCentral()

    maven(url = "https://modmaven.dev/") {
        content {
            includeGroup("appeng")
            includeGroup("mezz.jei")
        }
    }

    // TODO clean up the mess below

    maven(url = "https://squiddev.cc/maven")
    maven(url = "https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }

    maven(url = "https://maven.shedaniel.me/")
    maven(url = "https://maven.terraformersmc.com/")


    maven(url = "https://maven.bai.lol") {
        content {
            includeGroup("mcp.mobius.waila")
            includeGroup("lol.bai")
        }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())

    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.loader)

    // TODO only depend on the API
    modImplementation(libs.ae2)

//    modCompileOnly(libs.ae2) {
//        artifact { classifier = "api" }
//    }
//
//    modLocalRuntime(libs.ae2)


    modImplementation("dan200.computercraft:cc-restitched:1.100.8")
}