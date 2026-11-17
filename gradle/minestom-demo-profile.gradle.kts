import java.io.File
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer

// Shared demo application profile; the libraries themselves remain resolved Maven JARs.
val sets=the<SourceSetContainer>()
val selectedFile=providers.gradleProperty("groundsClasspathFile")
val nativeRuntime=providers.provider {
    if(!selectedFile.isPresent) emptyList<File>() else {
        val config=File(selectedFile.get())
        require(config.isAbsolute&&config.isFile) {"Supply an existing absolute Grounds classpath file"}
        val entries=config.readText().trim().split(File.pathSeparator).map(::File)
        require(entries.all {it.isAbsolute&&it.isFile&&it.extension=="jar"}) {"Grounds runtime needs existing absolute JARs"}
        val engines=entries.filter {it.name.startsWith("minestom-")}
        require(engines.size==1&&engines.single().name=="minestom-2026.11.08-26.3-grounds.jar") {"Use only the pinned Grounds runtime"}
        require(entries.none {it.name.startsWith("dialogue-")||it.name.startsWith("npc-")}) {"Do not substitute application libraries"}
        entries
    }
}
val ownLibraries=providers.provider {configurations.getByName("runtimeClasspath").filter {it.name.startsWith("dialogue-")||it.name.startsWith("npc-")||it.name.startsWith("kotlin-stdlib-")}}
val native=sets.create("groundsProfile") {
    java.setSrcDirs(sets.getByName("main").java.srcDirs+file("src/grounds/java"))
    resources.setSrcDirs(sets.getByName("main").resources.srcDirs)
    compileClasspath=files(nativeRuntime,ownLibraries)
    runtimeClasspath=output+files(nativeRuntime,ownLibraries)
}
tasks.register<JavaExec>("runGroundsProfile") {
    if(selectedFile.isPresent) dependsOn(tasks.named(native.classesTaskName))
    mainClass.set(if(project.the<JavaApplication>().mainClass.get().contains("Personal"))"example.GroundsPersonalDialogueDemo" else "example.GroundsDialogueDemo")
    classpath=native.runtimeClasspath
    systemProperty("dialogue.profile","grounds-26.3")
    systemProperty("java.net.preferIPv4Stack","true")
    doFirst {require(selectedFile.isPresent) {"Supply -PgroundsClasspathFile with the complete pinned Grounds runtime"}}
}
