plugins {
    java
    id("com.gradleup.shadow") version "9.6.0"
}

group = "dev.pyroforge"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// paper-api is compileOnly (provided by the server at runtime), but tests still need it
// on the classpath to compile against and for MockBukkit to mock against.
configurations.testImplementation.get().extendsFrom(configurations.compileOnly.get())

dependencies {
    // Paper 26.1.2 uses the new "{version}.build.{n}-{channel}" coordinate scheme
    // (no more -R0.1-SNAPSHOT). Pinned to the latest "-stable" build as of writing;
    // bump as needed by checking:
    // https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // Shaded into the plugin jar (relocated below) — see spec: SQLite writes must be serialized
    // through a single guarded connection once the storage layer is implemented.
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.1.2:4.114.0")
}

tasks.test {
    useJUnitPlatform()
}

// The shaded jar (with sqlite-jdbc bundled) is the only artifact anyone should ever deploy — the
// plain `jar` task's output is missing sqlite-jdbc entirely (a non-functional plugin jar) since
// `implementation` dependencies aren't bundled by the plain jar task, only by shadowJar. Disabling
// `jar` means shadowJar's classifier-less output at build/libs/cheese-plugin-<version>.jar is
// unambiguously the one real build product, instead of two tasks racing to write the same path.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "dev.pyroforge.cheese.lib.sqlite")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
