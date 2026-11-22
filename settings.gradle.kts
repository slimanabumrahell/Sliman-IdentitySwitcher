pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        jcenter() // مطلوب لبعض نسخ مكتبة Xposed API القديمة
    }
}

rootProject.name = "Sliman-IdentitySwitcher"
include(":app")
