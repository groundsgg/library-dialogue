import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.dokka-javadoc") version "2.2.0" apply false
    id("com.diffplug.spotless") version "8.10.4"
}

repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

group = "gg.grounds"

version = providers.gradleProperty("versionOverride").orElse("0.1.0-SNAPSHOT").get()

subprojects {
    if (!name.startsWith("dialogue-")) return@subprojects
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka-javadoc")
    group = rootProject.group
    version = rootProject.version
    repositories { mavenCentral() }
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
            javaParameters.set(true)
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }
    tasks.named<Jar>("javadocJar") {
        from(
            tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationJavadoc").flatMap {
                it.outputDirectory
            }
        )
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    dependencies {
        "api"(kotlin("stdlib"))
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from(rootProject.file("NOTICE")) { into("META-INF") }
    }
    dependencyLocking { lockAllConfigurations() }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "Review"
                url = rootProject.layout.projectDirectory.dir(".evidence/maven").asFile.toURI()
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/groundsgg/library-dialogue")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", ".evidence/**", ".superpowers/**", "**/.gradle/**")
        ktfmt("0.63").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".evidence/**", ".superpowers/**", "**/.gradle/**")
        ktfmt("0.63").kotlinlangStyle()
    }
    java {
        target(
            "fixtures/java-consumer/**/*.java",
            "fixtures/cli-consumer/**/*.java",
        )
        googleJavaFormat("1.35.0")
    }
}
