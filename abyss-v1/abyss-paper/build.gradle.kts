import java.util.zip.ZipFile
plugins {
    java
}

version = "0.5.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.4") {
        isTransitive = false
    }

    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.4") {
        isTransitive = false
    }

    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    )
}

// Verify that the deployable JAR includes every dungeon class needed at runtime.
tasks.register("verifyDungeonJar") {
    dependsOn(tasks.jar)
    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        ZipFile(artifact).use { zip ->
            val required = listOf(
                "dev/abyss/AbyssPlugin.class",
                "dev/abyss/dungeon/FloorSelector.class",
                "dev/abyss/dungeon/FloorType.class",
                "dev/abyss/dungeon/DungeonRun.class",
                "dev/abyss/dungeon/ArenaBuilder.class",
                "dev/abyss/dungeon/SchematicMapManager.class"
            )
            val missing = required.filter { zip.getEntry(it) == null }
            check(missing.isEmpty()) { "JAR is missing required classes: $missing" }
        }
        println("Verified dungeon classes in: ${artifact.absolutePath}")
    }
}

tasks.build {
    dependsOn("verifyDungeonJar")
}
