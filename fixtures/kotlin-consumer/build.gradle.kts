plugins { kotlin("jvm") version "2.4.0"; application }
repositories {
    val source = maven {
        url = uri(providers.gradleProperty("dialogueRepository").get())
        if (url.scheme == "https") credentials {
            username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
    exclusiveContent { forRepositories(source); filter { includeModule("gg.grounds", "dialogue-core"); includeModule("gg.grounds", "dialogue-json"); includeModule("gg.grounds", "dialogue-presentation") } }
    mavenCentral()
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val dialogueVersion = providers.gradleProperty("dialogueVersion").orElse("0.1.0-SNAPSHOT")
dependencies {
    implementation("gg.grounds:dialogue-core:${dialogueVersion.get()}")
    implementation("gg.grounds:dialogue-json:${dialogueVersion.get()}")
    implementation("gg.grounds:dialogue-presentation:${dialogueVersion.get()}")
}
kotlin { jvmToolchain(25); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
application { mainClass.set("example.ConsumerKt") }
