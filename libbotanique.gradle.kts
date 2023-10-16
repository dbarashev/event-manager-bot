import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.9.0"
  java
  id("maven-publish")
}

repositories {
  mavenCentral()
}

dependencies {
  api("org.telegram:telegrambots:6.5.0")
  api("com.michael-bull.kotlin-result:kotlin-result:1.1.16")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
  implementation("org.telegram:telegrambotsextensions:6.5.0")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
//  implementation("org.postgresql:postgresql:42.5.4")
//  implementation("com.zaxxer:HikariCP:5.0.1")

  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "17"
}

sourceSets {
  main {
    kotlin {
      include("com/bardsoftware/libbotanique/*.kt")
    }
  }
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      groupId = "com.bardsoftware"
      artifactId = "libbotanique"
      version = "1.0"
      from(components["java"])
    }
  }
}


