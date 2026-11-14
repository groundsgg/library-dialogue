import org.gradle.api.tasks.testing.logging.TestExceptionFormat
plugins { base }
repositories { mavenCentral() }
dependencyLocking { lockAllConfigurations() }
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

val javaFormatterTool = configurations.create("javaFormatterTool") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(org.gradle.api.attributes.java.TargetJvmEnvironment.STANDARD_JVM))
}
dependencies { add(javaFormatterTool.name, "com.google.googlejavaformat:google-java-format:1.35.0") }
listOf("formatJava" to false, "checkJavaFormat" to true).forEach { (taskName, checkOnly) ->
    tasks.register<JavaExec>(taskName) {
        classpath = javaFormatterTool
        mainClass.set("com.google.googlejavaformat.java.Main")
        jvmArgs(listOf("api", "file", "parser", "tree", "util", "code").map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" })
        doFirst {
            val sources = fileTree(rootDir) {
                include("**/*.java")
                exclude("**/build/**", ".evidence/**", ".superpowers/**", "**/.gradle/**")
            }.files.sortedBy { it.path }.map { it.absolutePath }
            setArgs((if (checkOnly) listOf("--dry-run", "--set-exit-if-changed") else listOf("--replace")) + sources)
        }
    }
}
tasks.named("check") { dependsOn("checkJavaFormat") }
