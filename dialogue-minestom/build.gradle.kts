dependencies {
    api(project(":dialogue-core"))
    api(project(":dialogue-presentation"))
    compileOnly("net.minestom:minestom:2026.10.07-26.2")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("net.minestom:testing:2026.10.07-26.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
tasks.test { systemProperty("minestom.inside-test", "true"); systemProperty("java.awt.headless", "true") }

tasks.test { dependsOn(tasks.named("jar")); systemProperty("dialogue.adapterJar",tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath) }
