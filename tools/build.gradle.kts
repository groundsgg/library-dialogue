import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

repositories { mavenCentral() }

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}

dependencies { implementation(project(":dialogue-core")) }

tasks.withType<JavaExec>().configureEach {
    group = "dialogue assets"
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir(rootProject.projectDir)
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("generateDefaultMetrics") {
    description = "Regenerates the default theme's headless font metrics."
    mainClass.set("GenerateDefaultMetrics")
}

tasks.register<JavaExec>("generateMinecraftAssets") {
    description = "Regenerates the frozen assets for the supported Minecraft profiles."
    mainClass.set("GenerateMinecraftAssets")
}
