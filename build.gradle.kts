plugins {
    id("fabric-loom") version "1.8.13"
    java
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

val modVersion = version
val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String

tasks.processResources {
    val props = mapOf(
        "version" to modVersion,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion,
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Optional diagnostics: run with -PmixinDebug to dump post-mixin bytecode to run/.mixin.out,
// which is how injection success is verified rather than assumed.
loom {
    runs {
        named("client") {
            if (project.hasProperty("mixinDebug")) {
                property("mixin.debug.export", "true")
                property("mixin.debug.export.decompile", "false")
            }
        }
    }
}

