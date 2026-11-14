import java.io.File
import java.security.MessageDigest

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

// Opt-in compatibility checks. File dependencies never enter the main publication.
val groundsClasspathFile = providers.gradleProperty("groundsClasspathFile")
val groundsRuntime = providers.provider {
    if (!groundsClasspathFile.isPresent) emptyList<File>() else {
        val config = File(groundsClasspathFile.get())
        require(config.isAbsolute && config.isFile) { "Supply an existing absolute Grounds classpath file" }
        val entries = config.readText().trim().split(File.pathSeparator).map(::File)
        require(entries.all { it.isAbsolute && it.isFile && it.extension == "jar" }) { "Grounds runtime must contain existing absolute JARs" }
        val engines = entries.filter { it.name.startsWith("minestom-") }
        require(engines.size == 1 && engines.single().name == "minestom-2026.11.08-26.3-grounds.jar") { "Use only the pinned Grounds 26.3 Minestom runtime" }
        require(entries.none { it.name.startsWith("dialogue-") || it.name.startsWith("npc-") }) { "Runtime file must not substitute application libraries" }
        entries
    }
}
val groundsProfile = sourceSets.create("groundsProfile") {
    java.setSrcDirs(sourceSets.main.get().java.srcDirs + file("src/grounds/java"))
    resources.setSrcDirs(sourceSets.main.get().resources.srcDirs)
}
val groundsProfileTest = sourceSets.create("groundsProfileTest") {
    java.setSrcDirs(sourceSets.test.get().java.srcDirs + file("src/groundsTest/java"))
    compileClasspath += groundsProfile.output
    runtimeClasspath += groundsProfile.output
}
dependencies {
    add(groundsProfile.implementationConfigurationName, project(":dialogue-core"))
    add(groundsProfile.implementationConfigurationName, project(":dialogue-presentation"))
    add(groundsProfile.implementationConfigurationName, files(groundsRuntime))
    add(groundsProfileTest.implementationConfigurationName, project(":dialogue-core"))
    add(groundsProfileTest.implementationConfigurationName, project(":dialogue-presentation"))
    add(groundsProfileTest.implementationConfigurationName, files(groundsRuntime))
    add(groundsProfileTest.implementationConfigurationName, "net.minestom:testing:2026.10.07-26.2") { exclude(group="net.minestom",module="minestom") }
    add(groundsProfileTest.implementationConfigurationName, platform("org.junit:junit-bom:5.13.4"))
    add(groundsProfileTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(groundsProfileTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}
val runGroundsTests = tasks.register<Test>("groundsProfileTest") {
    testClassesDirs = groundsProfileTest.output.classesDirs
    classpath = groundsProfileTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
    systemProperty("minestom.inside-test","true")
    systemProperty("java.awt.headless","true")
    systemProperty("dialogue.adapterJar",tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
}
val groundsJar=tasks.register<Jar>("groundsProfileJar") {
    archiveClassifier.set("grounds-26.3")
    from(groundsProfile.output)
}
val groundsManifest=tasks.register("writeGroundsProfileManifest") {
    dependsOn(groundsJar)
    doLast {
        val runtime=groundsRuntime.get()
        fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val data=mapOf("profile" to "grounds-26.3","protocol" to 777,"pack" to listOf(97,1),"runtime" to runtime.map { mapOf("name" to it.name,"sha256" to digest(it)) },"compiledAdapterSha256" to digest(groundsJar.get().archiveFile.get().asFile))
        val output=rootProject.layout.projectDirectory.file(".evidence/grounds-profile.json").asFile
        output.parentFile.mkdirs();output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(data))+"\n")
    }
}
tasks.register("checkGroundsProfile") {
    if(groundsClasspathFile.isPresent) dependsOn(runGroundsTests,groundsManifest)
    else doLast {error("Supply -PgroundsClasspathFile with the complete explicit Grounds 26.3 runtime")}
}
tasks.named<Jar>("sourcesJar") {from("src/grounds/java") {into("grounds-26.3")}}
