plugins {
java
id("com.github.johnrengelman.shadow") version "8.1.1"
}


group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()


java {
toolchain.languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get()))
}


repositories {
mavenCentral()
maven("https://repo.papermc.io/repository/maven-public/")
}


dependencies {
compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
implementation("io.github.cdimascio:dotenv-java:3.0.0")
implementation("com.zaxxer:HikariCP:5.1.0")
implementation("commons-codec:commons-codec:1.16.1")

}


tasks.processResources {
filteringCharset = "UTF-8"
filesMatching("plugin.yml") {
expand(
"version" to version
)
}
}


tasks.shadowJar {
archiveClassifier.set("")
// при желании можно relocate зависимости
}


tasks.build { dependsOn(tasks.shadowJar) }