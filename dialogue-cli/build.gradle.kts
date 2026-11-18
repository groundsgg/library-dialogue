plugins { application }
dependencies {
    implementation(project(":dialogue-core"))
    implementation(project(":dialogue-json"))
    implementation("com.google.code.gson:gson:2.13.2")
}
val generatedVersion = layout.buildDirectory.dir("generated/version")
val generateVersion by tasks.registering {
    inputs.property("version", rootProject.version.toString())
    outputs.dir(generatedVersion)
    doLast {
        val target = generatedVersion.get().file("gg/grounds/dialogue/cli/version.properties").asFile
        target.parentFile.mkdirs()
        target.writeText("version=${rootProject.version}\n", Charsets.UTF_8)
    }
}
sourceSets.main { resources.srcDir(generatedVersion) }
tasks.named("processResources") { dependsOn(generateVersion) }
tasks.test { systemProperty("cli.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath) }

application { mainClass.set("gg.grounds.dialogue.cli.DialogueCli"); applicationName = "dialogue" }
distributions.main {
    distributionBaseName.set("dialogue-cli")
    contents { from(rootProject.file("LICENSE")); from(rootProject.file("NOTICE")) }
}
