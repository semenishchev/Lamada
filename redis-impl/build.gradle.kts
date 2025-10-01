plugins {
    id("java")
}

group = "cc.olek.lamada"
version = "1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("redis.clients:jedis:6.0.0")
    compileOnly(parent!!)
}

tasks.test {
    useJUnitPlatform()
}