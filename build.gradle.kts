fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.damnhandy:handy-uri-templates:2.1.8")
    implementation("org.apache.commons:commons-text:1.12.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        phpstorm(properties("platformVersion"))
        bundledPlugin("com.jetbrains.php")
        bundledPlugin("com.jetbrains.twig")
        plugin("de.espend.idea.php.annotation", "12.0.1")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = properties("pluginName")
        version = properties("pluginVersion")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN").orElse("")
        privateKey = environment("PRIVATE_KEY").orElse("")
        password = environment("PRIVATE_KEY_PASSWORD").orElse("")
    }

    publishing {
        token = environment("PUBLISH_TOKEN").orElse("")
        channels = properties("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(properties("javaVersion").get())
    targetCompatibility = JavaVersion.toVersion(properties("javaVersion").get())
}

tasks {
    test {
        useJUnitPlatform()
    }

    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }
}
