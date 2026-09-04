// ملف Gradle الجذري: يعلن الإضافات فقط دون تطبيقها (تُطبَّق داخل وحدة app)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
