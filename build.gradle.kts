import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "dev.skrety"
version = "0.14.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the oldest supported platform; sinceBuild 242 + open
        // untilBuild keeps the same ZIP installable on 2024.2 through current IDEs.
        intellijIdeaCommunity("2024.2.5")
        // Apex/SOQL highlighting via the IDE's TextMate engine (bundled plugin).
        bundledPlugin("org.jetbrains.plugins.textmate")
        // LSP client that works on Community Edition (native LSP API is Ultimate-only).
        plugin("com.redhat.devtools.lsp4ij", "0.20.1")
        // Repo IDE dists ship without a runtime — needed by runIde/tests only.
        jetbrainsRuntime()
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.runIde {
    // Sandbox diagnostics: open a project without the trust dialog blocking headless boots.
    jvmArgs("-Didea.trust.all.projects=true")
    // ./gradlew runIde -PopenProject=/path/to/project opens that project on boot.
    val openProject = providers.gradleProperty("openProject")
    argumentProviders.add(CommandLineArgumentProvider { listOfNotNull(openProject.orNull) })
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2") // newest resolvable in the artifact repo
        }
    }
}
