dependencies {
    implementation(project(":core:workflow-api"))
    implementation(project(":core:tenant"))
    implementation(project(":plugins:plugin-api"))
    implementation("org.slf4j:slf4j-api:2.0.11")

    testImplementation("ch.qos.logback:logback-classic:1.4.14")
}
