import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // For parsing the OpenAI API's JSON response in the AI-summary feature (dev.spaghetti.ai).
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        // Compile and test against the Maven (non-installer) build of IntelliJ IDEA.
        // The retail installer's licensing code refuses to start inside the test sandbox.
        intellijIdea(providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }

        // HTML support ships with the platform; CSS and JavaScript are bundled plugins.
        bundledPlugins("com.intellij.css", "JavaScript")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
}

// `gradlew runIdeLocal` launches the plugin inside the IntelliJ IDEA installed on this machine.
val localIdePath = providers.gradleProperty("localIdePath").orNull
if (localIdePath != null && file(localIdePath).exists()) {
    intellijPlatformTesting {
        runIde {
            register("runIdeLocal") {
                localPath = file(localIdePath)
                // `gradlew runIdeLocal -PideProject=<folder>` opens that folder straight away.
                providers.gradleProperty("ideProject").orNull?.let { project -> task { args = listOf(project) } }
            }
        }
    }
}
