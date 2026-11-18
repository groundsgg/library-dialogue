plugins { application }
repositories {
    val source = maven {
        url = uri(providers.gradleProperty("dialogueRepository").get())
        if (url.toString() == "https://maven.pkg.github.com/groundsgg/library-dialogue") credentials {
            username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
    exclusiveContent { forRepositories(source); filter { includeModule("gg.grounds", "dialogue-testkit"); includeModule("gg.grounds", "dialogue-core"); includeModule("gg.grounds", "dialogue-json"); includeModule("gg.grounds", "dialogue-presentation") } }
    mavenCentral()
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val dialogueVersion = providers.gradleProperty("dialogueVersion").orElse("0.1.0-SNAPSHOT")
dependencies {
    testImplementation("gg.grounds:dialogue-testkit:${dialogueVersion.get()}")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("gg.grounds:dialogue-core:${dialogueVersion.get()}")
    implementation("gg.grounds:dialogue-json:${dialogueVersion.get()}")
    implementation("gg.grounds:dialogue-presentation:${dialogueVersion.get()}")
}
application { mainClass.set("example.Consumer") }

configurations.configureEach {resolutionStrategy.cacheChangingModulesFor(0,"seconds")}

tasks.test { useJUnitPlatform() }
val verifyMainRuntime by tasks.registering {
    doLast { check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.none { it.moduleVersion.id.name == "dialogue-testkit" }) { "Testkit leaked into main runtime" } }
}
tasks.named("run") { dependsOn(verifyMainRuntime) }
tasks.test { dependsOn(verifyMainRuntime) }
tasks.register<JavaExec>("runBinaryCompatibility") {
    mainClass.set(application.mainClass)
    classpath = files(providers.gradleProperty("binaryClassesDir")) + configurations.runtimeClasspath.get()
    dependsOn(verifyMainRuntime)
    doFirst { check(providers.gradleProperty("binaryClassesDir").isPresent) { "Supply precompiled classes" } }
}
