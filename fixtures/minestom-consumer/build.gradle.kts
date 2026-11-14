plugins { application }
repositories {
    val artifacts = maven { url = uri(providers.gradleProperty("dialogueRepository").get()); if (url.toString() == "https://maven.pkg.github.com/groundsgg/library-dialogue") credentials { username = System.getenv("GITHUB_ACTOR"); password = System.getenv("GITHUB_TOKEN") } }
    exclusiveContent { forRepositories(artifacts); filter { includeGroupByRegex("gg\\.grounds") } }
    mavenCentral()
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0,"seconds") }
val dialogueVersion = providers.gradleProperty("dialogueVersion").orElse("0.1.0-SNAPSHOT")
dependencies {
    implementation("gg.grounds:dialogue-minestom:${dialogueVersion.get()}")
    implementation("net.minestom:minestom:2026.10.07-26.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("net.minestom:testing:2026.10.07-26.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("example.Consumer") }
tasks.test { useJUnitPlatform(); systemProperty("minestom.inside-test","true") }
tasks.withType<JavaExec>().configureEach { systemProperty("java.net.preferIPv4Stack","true");providers.gradleProperty("dialoguePort").orNull?.let { systemProperty("dialogue.port",it) };providers.gradleProperty("dialoguePackPort").orNull?.let { systemProperty("dialogue.packPort",it) } }
