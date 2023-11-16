import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Property

plugins {
    kotlin("jvm") version "1.9.0"
    java
    application
    id("com.google.cloud.tools.jib") version "3.3.1"
    id("nu.studer.jooq") version "8.0"

}

group = "net.barashev.embot"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.bardsoftware:libbotanique:1.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    implementation("com.github.ajalt.clikt:clikt:4.2.0")
    implementation("org.telegram:telegrambots:6.5.0")
    implementation("org.telegram:telegrambotsextensions:6.5.0")
    implementation("javax.xml.bind:jaxb-api:2.3.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    implementation("org.postgresql:postgresql:42.5.4")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("org.jooq:jooq:3.18.0")
    implementation("com.michael-bull.kotlin-result:kotlin-result:1.1.16")
    jooqGenerator("org.jooq:jooq-meta-extensions:3.18.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.bardsoftware.embot.MainKt")
}

sourceSets {
    main {
        kotlin {
            srcDirs.add(file("$buildDir/generated-src/jooq/main"))
            exclude("com/bardsoftware/libbotanique/*.kt")
        }
    }
}


jooq {
    configurations {
        create("main") {
            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                        properties.apply {
                            add(Property().apply {
                                key = "scripts"
                                value = "src/main/resources/database-schema.sql"
                            })
                            add(Property().apply {
                                key = "defaultNameCase"
                                value = "lower"
                            })
                        }
                    }
                    target.apply {
                        packageName = "com.bardsoftware.embot.db"
                    }
                }
            }
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(":generateJooq")
}

jib {
    to.image = "dbarashev/event-manager-bot"
    from.image = "eclipse-temurin:17"
    container.ports = listOf("8080")
}
