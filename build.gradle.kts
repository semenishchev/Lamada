import org.gradle.internal.extensions.stdlib.toDefaultLowerCase

plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

group = "cc.olek.lamada"
version = "1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("com.esotericsoftware:kryo:5.6.2")
    api("it.unimi.dsi:fastutil:8.5.16")
    api("org.slf4j:slf4j-api:2.0.17")
    api("org.jetbrains:annotations:26.0.2")
    api("org.ow2.asm:asm:9.8")
    api("org.ow2.asm:asm-tree:9.8")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
    environment("SYNC_DEBUG", "1")
    jvmArgs("-Dsync.save-msg=1")
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
    configure<JavaPluginExtension> {
        withSourcesJar()
    }
    afterEvaluate {
        val targetJavaVersion = 16
        java {
            val javaVersion = JavaVersion.toVersion(targetJavaVersion)
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            if (JavaVersion.current() < javaVersion) {
                toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
            }
        }
        publishing {
            repositories {
                maven {
                    name = "lamadaRepo"
                    url = uri(rootProject.properties[name]?.toString() ?: "https://not.specified/")
                    credentials(PasswordCredentials::class)
                    authentication {
                        create<BasicAuthentication>("basic")
                    }
                }
            }

            publications {
                create<MavenPublication>("maven") {
                    groupId = rootProject.group.toString()
                    val name = project.name.toDefaultLowerCase()
                    artifactId = if(project == rootProject) name else "${rootProject.name.toDefaultLowerCase()}-$name"
                    version = project.version.toString()
                    println("Publishing $group:$artifactId:${project.version}")
                    from(project.components["java"])
                }
            }
        }
    }
}