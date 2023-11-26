/*
 * Copyright (c) 2022-2023 Leon Linhart
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
import io.github.themrmilchmann.gradle.publish.curseforge.*

plugins {
    alias(libs.plugins.loom)
    id("io.github.themrmilchmann.java-conventions")
    id("io.github.themrmilchmann.curseforge-publish-conventions")
}

tasks {
    processResources {
        inputs.property("version", version)

        filesMatching("fabric.mod.json") {
            expand("version" to "${project.version}")
        }
    }
}

curseforge {
    publications {
        named("fabric") {
            projectId = "715346" // https://www.curseforge.com/minecraft/mc-mods/ae2cc-bridge

            artifact {
                changelog = changelog()
                displayName = "AE2CC Bridge ${project.version}"
                releaseType = ReleaseType.RELEASE
            }
        }
    }
}

fun changelog(): Changelog {
    val modVersionSegment = version.toString().substringBefore('-')
    val mcVersionSegment = version.toString().substring(startIndex = modVersionSegment.length + 1).substringBefore('-')
    val mcVersionGroup = if (mcVersionSegment.count { it == '.' } == 1) mcVersionSegment else mcVersionSegment.substringBeforeLast('.')
    val loaderVersionSegment = version.toString().substring(startIndex = modVersionSegment.length + mcVersionSegment.length + 2)

    return Changelog(
        content = File(rootDir, "docs/changelog/$mcVersionGroup/${modVersionSegment}-${mcVersionSegment}-${loaderVersionSegment}.md").readText(),
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
            includeGroup("cc.tweaked")
            includeGroup("org.squiddev")
        }
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())

    compileOnly(libs.jsr305)

    modImplementation(libs.fabric.api)
    modImplementation(libs.fabric.loader)

    modImplementation(libs.ae2)
    modImplementation(libs.ccTweakedApi)
    modRuntimeOnly(libs.ccTweaked)
}