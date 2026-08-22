group = "io.github.liongalahad.stremio"

patches {
    about {
        name = "Stremio Morphe Patches"
        description = "Morphe patches for the official Stremio Android TV application"
        source = "https://github.com/liongalahad/Stremio-Morphe-Patches"
        author = "liongalahad"
        contact = "https://github.com/liongalahad/Stremio-Morphe-Patches/issues"
        website = "https://github.com/liongalahad/Stremio-Morphe-Patches"
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

tasks.processResources {
    inputs.property("morphePatchVersion", project.version.toString())
    filesMatching("morphe-build.properties") {
        expand("version" to project.version.toString())
    }
}
