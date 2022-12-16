/*
 * Copyright (c) 2022 Leon Linhart
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import com.github.themrmilchmann.build.*
import com.github.themrmilchmann.build.BuildType
import io.github.themrmilchmann.gradle.publish.curseforge.*

plugins {
    java
    alias(libs.plugins.curseforge.publish)
    alias(libs.plugins.loom)
}

version = "1.0.0-1.18.2-FABRIC-0.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(19))
    }
}

tasks {
    compileJava {
        options.release.set(17)
    }

    processResources {
        inputs.property("version", version)

        filesMatching("fabric.mod.json") {
            expand("version" to "${project.version}")
        }
    }
}

publishing {
    repositories {
        curseForge {
            apiKey.set(deployment.cfApiKey)
        }
    }
    publications {
        create<CurseForgePublication>("curseForge") {
            projectID.set(715346) // https://www.curseforge.com/minecraft/mc-mods/ae2cc-bridge

            artifact(tasks.remapJar) {
                changelog = changelog()
                displayName = "AE2CC Bridge ${project.version}"
                releaseType = ReleaseType.RELEASE
            }
        }
    }
}

fun changelog(): Changelog {
    if (deployment.type == BuildType.SNAPSHOT) return Changelog("", ChangelogType.TEXT)

    val mc = project.version.toString() // E.g. 1.0.0-1.16.5-FABRIC-1.0
        .substringAfter('-')            //            1.16.5-FABRIC-1.0
        .substringBefore('-')           //            1.16.5-FABRIC
        .substringBefore('-')           //            1.16.5
        .let {
            if (it.count { it == '.' } == 1)
                it
            else
                it.substringBeforeLast('.')
        }                               //            1.16

    return Changelog(
        content = File(rootDir, "docs/changelog/$mc/${project.version}.md").readText(),
        type = ChangelogType.MARKDOWN
    )
}

repositories {
    mavenCentral()

    maven(url = "https://maven.bai.lol") {
        content {
            includeGroup("lol.bai")
            includeGroup("mcp.mobius.waila")
        }
    }

    maven(url = "https://maven.shedaniel.me/") {
        content {
            includeGroup("me.shedaniel.cloth")
            includeGroup("me.shedaniel.cloth.api")
        }
    }

    maven(url = "https://maven.terraformersmc.com/") {
        content {
            includeGroup("com.terraformersmc")
        }
    }

    maven(url = "https://modmaven.dev/") {
        content {
            includeGroup("appeng")
            includeGroup("mezz.jei")
        }
    }

    maven(url = "https://squiddev.cc/maven") {
        content {
            includeGroup("org.squiddev")
        }
    }

    mavenLocal {
        content {
            includeGroup("dan200.computercraft")
        }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())

    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.loader)

    modImplementation(libs.ae2)
    modImplementation(libs.cc.restitched)
}