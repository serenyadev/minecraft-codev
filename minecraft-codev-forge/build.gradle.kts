plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevForge") {
        id = project.name
        description = "A Minecraft Codev module that allows providing Forge patched versions of Minecraft."
        implementationClass = "net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin"
    }
}

dependencies {
    implementation(group = "io.arrow-kt", name = "arrow-core", version = "1.2.4")
    implementation(group = "io.arrow-kt", name = "arrow-core-serialization", version = "1.2.4")

    implementation(group = "net.minecraftforge", name = "accesstransformers", version = "8.0.7") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
    }

    implementation(group = "org.cadixdev", name = "lorenz", version = "0.5.8")

    implementation(group = "de.siegmar", name = "fastcsv", version = "2.2.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.12.4")

    implementation(group = "com.electronwill.night-config", name = "toml", version = "3.6.0")

    implementation(projects.minecraftCodevAccessWidener)
    implementation(projects.minecraftCodevRemapper)
    implementation(projects.minecraftCodevRuns)
    implementation(projects.minecraftCodevMixins)
    implementation(projects.minecraftCodevIncludes)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
