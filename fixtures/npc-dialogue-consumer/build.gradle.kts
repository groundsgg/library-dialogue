plugins { application }
repositories {
    val dialogue = maven { name="DialogueArtifacts";url=uri(providers.gradleProperty("dialogueRepository").get());if(url.toString()=="https://maven.pkg.github.com/groundsgg/library-dialogue")credentials { username=System.getenv("GITHUB_ACTOR");password=System.getenv("GITHUB_TOKEN") } }
    exclusiveContent { forRepositories(dialogue);filter { includeModule("gg.grounds","dialogue-core");includeModule("gg.grounds","dialogue-presentation");includeModule("gg.grounds","dialogue-minestom") } }
    val npc=maven {name="NpcArtifacts";url=uri(providers.gradleProperty("npcRepository").orElse("https://maven.pkg.github.com/groundsgg/library-npc").get());if(url.toString()=="https://maven.pkg.github.com/groundsgg/library-npc")credentials {username=System.getenv("GITHUB_ACTOR");password=System.getenv("GITHUB_TOKEN")}}
    exclusiveContent {forRepositories(npc);filter {includeModule("gg.grounds","npc-core");includeModule("gg.grounds","npc-minestom")}}
    mavenCentral()
}
java {toolchain.languageVersion.set(JavaLanguageVersion.of(25))}
configurations.configureEach { resolutionStrategy.cacheChangingModulesFor(0,"seconds") }
// Only common demo-application sources are shared. Every library is a Maven JAR.
sourceSets.main { java.srcDir("../../examples/standalone-minestom/src/main/java") }
dependencies {
    implementation("gg.grounds:dialogue-minestom:${providers.gradleProperty("dialogueVersion").orElse("0.1.0-SNAPSHOT").get()}")
    implementation("gg.grounds:npc-minestom:${providers.gradleProperty("npcVersion").orElse("1.0.0").get()}")
    implementation("net.minestom:minestom:2026.10.07-26.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("net.minestom:testing:2026.10.07-26.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application {mainClass.set("example.PersonalDialogueDemo")}
tasks.test {useJUnitPlatform();systemProperty("minestom.inside-test","true")}
tasks.withType<JavaExec>().configureEach {systemProperty("java.net.preferIPv4Stack","true");providers.gradleProperty("dialoguePort").orNull?.let {systemProperty("dialogue.port",it)};providers.gradleProperty("dialoguePackPort").orNull?.let {systemProperty("dialogue.packPort",it)}}
apply(from="../../gradle/minestom-demo-profile.gradle.kts")

sourceSets.named("groundsProfile") {java.srcDir("../../examples/standalone-minestom/src/grounds/java")}
