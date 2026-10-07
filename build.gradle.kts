import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.TestIdeTask
import java.io.File
import java.security.MessageDigest

val remoteRobotVersion = "0.11.21"
val testPlatformKotlinVersion = "2.2.20"
val minimumIdeBuild = "WS-253.28294.332"
val currentIdeVersion = "2026.2.3"
val currentIdeBuild = "WS-262.10968.77"

plugins {
  id("java") // Java support
  alias(libs.plugins.kotlin) // Kotlin support
  alias(libs.plugins.kotlinxSerialization) // Kotlinx Serialization
  alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
  jvmToolchain(21)
}

// Configure project's dependencies
repositories {
  mavenCentral()

  // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
  intellijPlatform {
    defaultRepositories()
  }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
  implementation(libs.kotlinxSerialization)
  testImplementation(enforcedPlatform("org.jetbrains.kotlin:kotlin-bom:$testPlatformKotlinVersion"))

  testImplementation(libs.junit)
  // TODO:
  //  Warning:(37, 22)  Provides transitive vulnerable dependency maven:org.assertj:assertj-core:3.17.2 CVE-2026-24400 7.3 AssertJ has XML External Entity (XXE) vulnerability when parsing untrusted XML via isXmlEqualTo assertion  Results powered by Mend.io
  testImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
  testImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
  // Remote Robot still brings an older Gson that breaks package.json parsing and the LSP JSON adapter at runtime.
  testImplementation("com.google.code.gson:gson:2.11.0")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.3")

  // Logging Network Calls
  testImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion")) {
      // TODO: consider using the installer release and drop the below line
      useInstaller = false
    }

    // IDE tooling needs the matching JetBrains Runtime when the SDK has no bundled runtime.
    jetbrainsRuntime()

    // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
    bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

    // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
    plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

    testFramework(TestFrameworkType.Platform)
  }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")

    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild")
      untilBuild = providers.gradleProperty("pluginUntilBuild")
    }
  }

  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }

  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
    // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
    // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
  }

  pluginVerification {
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
      VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
    )
    ides {
      val minimumPath = providers.gradleProperty("minimumIdePath")
      if (minimumPath.isPresent) local(minimumPath.get())
      else create(IntelliJPlatformType.WebStorm, "2025.3") { useInstaller = false }
      val currentPath = providers.gradleProperty("currentIdePath")
      if (currentPath.isPresent) local(currentPath.get())
      else create(IntelliJPlatformType.WebStorm, currentIdeVersion)
    }
  }
}

// Test targets must never change the SDK used to compile the publishable plugin.
val requireMinimumCompileSdk by tasks.registering {
  val platformType = providers.gradleProperty("platformType")
  val platformVersion = providers.gradleProperty("platformVersion")
  val sinceBuild = providers.gradleProperty("pluginSinceBuild")
  inputs.property("platformType", platformType)
  inputs.property("platformVersion", platformVersion)
  inputs.property("pluginSinceBuild", sinceBuild)
  doLast {
    require(platformType.get() == "WS" && platformVersion.get() == "2025.3" && sinceBuild.get() == "253") {
      "Compatibility/release artifacts must compile against WS 2025.3 (253); select a test runtime with currentIdePath."
    }
  }
}

tasks {
  wrapper {
    gradleVersion = providers.gradleProperty("gradleVersion").get()
  }

  test {
    useJUnitPlatform()
    // Biome shares its daemon by cache directory, even across separate IDE test JVMs.
    // Keep the socket path short enough for Unix domain sockets in deeply nested checkouts.
    val cacheKey = MessageDigest.getInstance("SHA-256")
      .digest(projectDir.absolutePath.toByteArray(Charsets.UTF_8))
      .take(8).joinToString("") { "%02x".format(it) }
    environment("XDG_CACHE_HOME", File(System.getProperty("java.io.tmpdir"), "biome-test-$cacheKey").absolutePath)
    systemProperty("biome.test.expected.build", minimumIdeBuild)
    systemProperty("biome.test.compile.build", minimumIdeBuild)
  }
  buildPlugin { dependsOn(requireMinimumCompileSdk) }
  verifyPlugin { dependsOn(requireMinimumCompileSdk) }
}

intellijPlatformTesting {
  testIde {
    register("testCurrentIde") {
      type = IntelliJPlatformType.WebStorm
      version = currentIdeVersion
      providers.gradleProperty("currentIdePath").orNull?.let { localPath = file(it) }
      // Pin the closest platform framework below the WS build explicitly. The
      // extension's implicit framework version otherwise uses the compile SDK.
      testFramework(TestFrameworkType.Platform, "262.10968.67")
      plugins {
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").get().split(',') + listOf(
          "intellij.testRunner.plugin", "intellij.structureView.plugin", "com.intellij.modules.json", "NodeJS",
          "com.intellij.modules.jcef", "intellij.libraries.misc.plugin", "intellij.ssh.plugin",
          "intellij.bookmarks.plugin", "com.intellij.platform.images",
        ))
      }
      task {
        dependsOn(requireMinimumCompileSdk)
        useJUnitPlatform()
        systemProperty("biome.test.expected.build", currentIdeBuild)
        systemProperty("biome.test.compile.build", minimumIdeBuild)
        // TestIde flattens plugin libraries into the IDE classloader. Use the
        // target SDK's stdlib (in util-8.jar), as the installed plugin does.
        // Keep compilation and the publishable ZIP on the minimum dependencies.
        val cacheKey = MessageDigest.getInstance("SHA-256")
          .digest("${projectDir.absolutePath}:current".toByteArray(Charsets.UTF_8))
          .take(8).joinToString("") { "%02x".format(it) }
        environment("XDG_CACHE_HOME", File(System.getProperty("java.io.tmpdir"), "biome-test-$cacheKey").absolutePath)
      }
    }
  }
  runIde {
    register("runIdeForUiTests") {
      task {
        jvmArgumentProviders += CommandLineArgumentProvider {
          listOf(
            "-Drobot-server.port=8082",
            "-Dide.mac.message.dialogs.as.sheets=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",
            "-Dide.mac.file.chooser.native=false",
            "-DjbScreenMenuBar.enabled=false",
            "-Dapple.laf.useScreenMenuBar=false",
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Deap.require.license=false"
          )
        }
      }

      plugins {
        robotServerPlugin()
      }
    }
  }
}

tasks.withType<TestIdeTask>().configureEach {
  classpath = classpath.filter { !it.name.startsWith("kotlin-stdlib") }
}
