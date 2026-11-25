plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

version = providers.gradleProperty("pluginVersion").get()

val ideaHome = providers.gradleProperty("ideaHome").map { file(it) }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val installation = ideaHome.orNull
        if (installation == null) intellijIdea("2026.1.1") else local(installation)
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261.23567"
            untilBuild = "261.*"
        }
    }
    pluginVerification {
        ides {
            val installation = ideaHome.orNull
            if (installation == null) current() else local(installation)
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.buildPlugin {
    archiveBaseName = "ij-format-mcp"
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("META-INF/ij-format-version.txt") {
        expand("pluginVersion" to pluginVersion)
    }
}

tasks.jar {
    from(project.files("../LICENSE", "../NOTICE")) {
        into("META-INF")
    }
}
