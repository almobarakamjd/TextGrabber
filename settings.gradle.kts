// إعدادات المشروع: مستودعات الإضافات والمكتبات + اسم المشروع والوحدات
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // مصدر مكتبة Tesseract4Android (التعرّف الضوئي على النص) — غير متاحة
        // على Maven Central، يوزّعها المطوّر عبر JitPack فقط.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TextGrabber"
include(":app")
