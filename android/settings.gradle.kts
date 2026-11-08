pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: Central itself rate-limits busy build machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central: Central itself rate-limits busy build machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "Tuned"
include(":core") // shared: logins, guide, backup, player engine
include(":app")   // Tuned for Android TV / Google TV / Fire TV
include(":phone") // Tuned Remote: the phone companion
include(":mobile") // Tuned for phones and tablets
