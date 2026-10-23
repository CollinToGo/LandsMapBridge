plugins {
    java
}

base {
    archivesName.set("landsmapbridge-paper")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.incredibleplugins.com/releases")
}

// The wire protocol is shared with the Fabric mod.
sourceSets.main {
    java.srcDir("../common/src/main/java")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")
    compileOnly("com.incredibleplugins:lands-api:${property("lands_api_version")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}
