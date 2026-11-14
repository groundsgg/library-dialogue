import org.gradle.api.tasks.testing.logging.TestExceptionFormat
plugins { base }
group = "gg.grounds"
version = providers.gradleProperty("versionOverride").orElse("0.1.0-SNAPSHOT").get()
subprojects {
    if (!name.startsWith("dialogue-")) return@subprojects
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    group = rootProject.group
    version = rootProject.version
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = TestExceptionFormat.FULL }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Jar>().configureEach { from(rootProject.file("LICENSE")) { into("META-INF") }; from(rootProject.file("NOTICE")) { into("META-INF") } }
    dependencyLocking { lockAllConfigurations() }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom { licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit"); distribution.set("repo") } } }
        }
        repositories {
            maven { name = "Review"; url = rootProject.layout.projectDirectory.dir(".evidence/maven").asFile.toURI() }
            maven {
                name = "GitHubPackages"; url = uri("https://maven.pkg.github.com/groundsgg/library-dialogue")
                credentials { username = System.getenv("GITHUB_ACTOR"); password = System.getenv("GITHUB_TOKEN") }
            }
        }
    }
}
