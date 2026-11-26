plugins {
    java
}

group = "ru.rpcore"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT")

    // Эти две библиотеки сервер сам скачает при старте (см. "libraries:" в plugin.yml),
    // поэтому в jar они не упаковываются.
    compileOnly("com.zaxxer:HikariCP:6.3.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
