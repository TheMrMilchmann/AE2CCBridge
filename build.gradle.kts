plugins {
    java
    alias(libs.plugins.loom)
}

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
    maven(url = "https://squiddev.cc/maven")
    maven(url = "https://cursemaven.com") {
        content {
            includeGroup("curse.maven")
        }
    }

    maven(url = "https://maven.shedaniel.me/")
    maven(url = "https://maven.terraformersmc.com/")
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())

    modImplementation("dan200.computercraft:cc-restitched:v1.18.2-1.100.8")

    modImplementation("curse.maven:ae2-223794:4023496") {
        exclude(group="net.fabricmc.fabric-api")
    }
}