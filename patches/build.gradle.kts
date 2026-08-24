group = "io.github.liongalahad.stremio"

patches {
    about {
        name = "Stremio Morphe Patches"
        description = "Morphe patches for the official Stremio Android TV application"
        source = "https://github.com/liongalahad/stremio-androidTV-morphe-patches"
        author = "liongalahad"
        contact = "https://github.com/liongalahad/stremio-androidTV-morphe-patches/issues"
        website = "https://github.com/liongalahad/stremio-androidTV-morphe-patches"
        license = "GPLv3"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
    sourceSets.named("main") {
        kotlin.srcDirs(
            "src/main/kotlin",
            "multi-account/morphe/src/main/kotlin",
            "addon-reordering/morphe/src/main/kotlin",
            "side-by-side-installation/morphe/src/main/kotlin"
        )
    }
}

// Gson is needed by the release-only patch list generator but must never be
// packaged into the patch bundle.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    processResources {
        inputs.property("morphePatchVersion", project.version.toString())
        filesMatching("morphe-build.properties") {
            expand("version" to project.version.toString())
        }
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build the Morphe bundle and generate patches-list.json"
        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
        args(project.version.toString())
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
