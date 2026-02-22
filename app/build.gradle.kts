plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":core:workflow-api"))
    implementation(project(":core:workflow-engine"))
    implementation(project(":core:tenant"))
    implementation(project(":plugins:plugin-api"))
    implementation(project(":plugins:email-plugin"))
    implementation(project(":plugins:webhook-plugin"))
    implementation(project(":plugins:slack-plugin"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:messaging"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.h2database:h2")
}
