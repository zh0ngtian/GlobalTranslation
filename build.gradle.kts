// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Dependency resolution strategy for reproducible builds
subprojects {
    // IMPORTANT: Only mutate resolution strategy on resolvable/consumable configurations.
    // Dependency-scope buckets like androidTestApi are 'Declarable' and must not be mutated.
    configurations.configureEach {
        if (isCanBeResolved || isCanBeConsumed) {
            resolutionStrategy {
                // Force specific versions for critical dependencies to avoid conflicts
                force("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
                force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
                force("org.jetbrains:annotations:24.0.1")

                // Prefer higher versions to resolve conflicts automatically
                preferProjectModules()
            }
        }
    }
}

// Enhanced clean task that handles Windows file locking issues
tasks.register("cleanAll") {
    group = "build"
    description = "Clean all projects including build cache"
    dependsOn(gradle.includedBuilds.map { it.task(":clean") })
    doLast {
        delete(layout.buildDirectory)
        delete(fileTree("build") { include("**/*") })
    }
}

// Add a forceful clean task for Windows that retries with delays
abstract class ForceCleanTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun cleanBuildDirectories() {
        val rootDir = rootDirectory.get().asFile
        val buildDirs = listOf(
            File(rootDir, "app/build"),
            File(rootDir, "core/build"),
            File(rootDir, "data/build"),
            File(rootDir, "build")
        )

        buildDirs.forEach { dir ->
            if (dir.exists()) {
                var attempts = 0
                val maxAttempts = 3
                var success = false

                while (attempts < maxAttempts && !success) {
                    try {
                        dir.deleteRecursively()
                        success = true
                        println("Successfully cleaned: ${dir.path}")
                    } catch (e: Exception) {
                        attempts++
                        if (attempts < maxAttempts) {
                            println("Attempt $attempts failed for ${dir.path}, retrying in 1 second...")
                            Thread.sleep(1000)
                        } else {
                            println("ERROR: Failed to clean ${dir.path} after $maxAttempts attempts: ${e.message}")
                            println("ERROR: Try closing your IDE and run './gradlew forceClean' from command line")
                        }
                    }
                }
            }
        }
    }
}

tasks.register<ForceCleanTask>("forceClean") {
    group = "build"
    description = "Force clean all build directories with retry logic (Windows-friendly)"
    rootDirectory.set(layout.projectDirectory)
}