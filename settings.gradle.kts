rootProject.name = "jerky"

// Composite build: Jerky is engine 11 of the ecosystem. Including SmokeHouse's build
// transitively includes SuperBeefSort and CSRBT (nested composites); Gradle substitutes
// every published coordinate with the live sibling sources.
includeBuild("../SmokeHouse")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
