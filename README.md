# TextGrabber

تطبيق أندرويد (Kotlin + Jetpack Compose) يقرأ النص المعروض على الشاشة تلقائيا من أي تطبيق آخر مفتوح، عبر خدمة إمكانية الوصول (Accessibility Service)، وينسخه إلى الحافظة (Clipboard) مع إشعار بسيط.

## سجل التعديلات

### 2026-09-04 — إنشاء المشروع كاملا
- تم إنشاء هيكل مشروع Android Studio قياسي كامل (Gradle Kotlin DSL، Version Catalog).
- **AndroidManifest.xml**: تعريف `MainActivity` وخدمة `MyAccessibilityService` مع صلاحية `BIND_ACCESSIBILITY_SERVICE` وصلاحية `POST_NOTIFICATIONS`.
- **accessibility_service_config.xml**: تفعيل أحداث `typeWindowContentChanged` و`typeViewFocused` و`typeViewTextSelectionChanged`، `feedbackGeneric`، و`canRetrieveWindowContent=true`.
- **MyAccessibilityService.kt**: قراءة `AccessibilityNodeInfo` من الحدث ومن العقدة المصدر، نسخ النص تلقائيا للحافظة مع تفادي تكرار نفس النص المتتالي، وإظهار إشعار Notification بمقتطف من النص (60 حرفا كحد أقصى).
- **CopiedTextStore.kt**: مخزن بسيط في الذاكرة (باستخدام `SnapshotStateList` من Compose بدل Room) يحتفظ بآخر 10 نصوص منسوخة، مشترك بين الخدمة والواجهة لأنهما في نفس العملية.
- **MainActivity.kt**: واجهة Compose تعرض حالة الخدمة (مفعّلة/غير مفعّلة عبر التحقق الرسمي من `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`)، زر لفتح إعدادات إمكانية الوصول مباشرة (`Settings.ACTION_ACCESSIBILITY_SETTINGS`)، قائمة بآخر 10 نصوص منسوخة، ونافذة شرح (Dialog) تظهر عند أول فتح فقط توضح بشفافية سبب طلب صلاحية إمكانية الوصول (متوافقة مع سياسات Google Play).
- **build.gradle.kts / libs.versions.toml**: Kotlin 2.2.10، AGP 8.12.0، Compose BOM 2025.08.00، minSdk 26، compileSdk/targetSdk 36.
- أيقونة تطبيق تكيفية (Adaptive Icon) بسيطة كـ Vector Drawable بدل PNG.

## ملاحظة مهمة (شفافية الصلاحية)
الخدمة **لا** تلتقط صورا للشاشة ولا تستخدم OCR إطلاقا. تعتمد فقط على شجرة `AccessibilityNodeInfo` التي يوفرها نظام أندرويد لأي خدمة إمكانية وصول مفعّلة، وهذا موضح للمستخدم صراحة في نافذة الشرح داخل التطبيق وفي وصف الخدمة في الإعدادات.

### 2026-09-04 — البناء والرفع إلى GitHub
- تم بناء نسخة release من APK محليا عبر Gradle 8.14.5 (`app/build/outputs/apk/release/app-release.apk`).
- أُضيف `signingConfigs.debug` وربط `buildTypes.release.signingConfig` به، لتوقيع نسخة الإصدار بمفتاح Debug القياسي حتى تكون قابلة للتثبيت مباشرة على الأجهزة (بدل توليد APK غير موقّع).
- إصلاح خطأين في الكود ظهرا أثناء البناء الفعلي: استبدال `ComponentActivity.MODE_PRIVATE` غير الصحيح بـ `Context.MODE_PRIVATE`، وإضافة `@OptIn(ExperimentalMaterial3Api::class)` لاستخدام `TopAppBar`.
- تم رفع المشروع إلى GitHub: https://github.com/almobarakamjd/TextGrabber
- تم إنشاء إصدار (Release) `v1.0.0` مع إرفاق ملف APK جاهز للتحميل: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.0.0

## روابط المشروع
- المستودع: https://github.com/almobarakamjd/TextGrabber
- تحميل آخر APK: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.0.0

## كيفية التشغيل
راجع قسم "طريقة التشغيل والاختبار" الذي شرحه المساعد في المحادثة (فتح المشروع في Android Studio، مزامنة Gradle، تشغيله على جهاز حقيقي، تفعيل الخدمة من الإعدادات). بديلا عن ذلك، يمكن تحميل ملف APK الجاهز مباشرة من رابط الإصدار أعلاه وتثبيته على أي جهاز (بعد السماح بالتثبيت من مصادر غير معروفة).
