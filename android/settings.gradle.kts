pluginManagement {
    buildscript {
        repositories { google();mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.57") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal(); maven("https://maven.aliyun.com/repository/google"); maven("https://maven.aliyun.com/repository/gradle-plugin") }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://maven.aliyun.com/repository/google"); maven("https://maven.aliyun.com/repository/central") }
}
rootProject.name = "BlackBoxDiary"
include(":app", ":core")
