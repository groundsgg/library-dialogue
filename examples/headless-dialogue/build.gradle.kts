plugins { application }
repositories { mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies { implementation(project(":dialogue-core")); implementation(project(":dialogue-json")); testImplementation(platform("org.junit:junit-bom:5.13.4")); testImplementation("org.junit.jupiter:junit-jupiter"); testRuntimeOnly("org.junit.platform:junit-platform-launcher") }
application { mainClass.set("example.HeadlessDialogue") }
tasks.test { useJUnitPlatform() }
