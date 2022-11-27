plugins {
    java
    alias(libs.plugins.loom)
}

version = "0.0.1-1.18.2-FABRIC-0.0"

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

repositories {
    mavenCentral()

    maven(url = "https://modmaven.dev/") {
        content {
            includeGroup("appeng")
            includeGroup("mezz.jei")
        }
    }

    // TODO clean up the mezz below

    maven(url = "https://squiddev.cc/maven")
    maven(url = "https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }

    maven(url = "https://maven.shedaniel.me/")
    maven(url = "https://maven.terraformersmc.com/")


    maven(url = "https://maven.bai.lol") {
        content{
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


    modImplementation("dan200.computercraft:cc-restitched:v1.18.2-1.100.8")
}