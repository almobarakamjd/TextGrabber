# TextGrabber

تطبيق أندرويد (Kotlin + Jetpack Compose) يتيح تحديد أي نص معروض في أي تطبيق آخر مفتوح، عبر خدمة إمكانية الوصول (Accessibility Service) وزر عائم يُستخدم لرسم مربع تحديد بإصبعك، وينسخ النص الواقع داخل ذلك المربع فقط إلى الحافظة (Clipboard) مع إشعار بسيط.

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

### 2026-09-04 — إصلاح: الخدمة لا تستقبل أي أحداث (v1.0.1)
- بعد التجربة الفعلية على جهاز حقيقي، تبيّن أن الخدمة لا تنسخ أي شيء رغم تفعيلها. السبب: `android:packageNames=""` في `accessibility_service_config.xml` كان يُفسَّر كقائمة تطبيقات فارغة، فتُمنع الخدمة من استقبال أي أحداث من أي تطبيق. تم حذف الخاصية بالكامل.
- إصدار: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.0.1

### 2026-09-04 — تحويل من النسخ التلقائي إلى تحديد يدوي بمربع (v1.1.0)
- بعد التجربة الفعلية، تبيّن أن النسخ التلقائي عند أي تغيّر نص (`onAccessibilityEvent`) يلتقط عناصر غير مقصودة (نصوص إعلانات، تسميات أزرار مثل "Open screenshot editor"، طوابع زمنية...) وليس بالضرورة المحتوى الذي يريده المستخدم فعليا.
- **التغيير**: حذف منطق النسخ التلقائي بالكامل من `onAccessibilityEvent`. بدلا منه:
  - الخدمة تضيف الآن **زرا عائما دائريا قابلا للسحب** فوق كل التطبيقات الأخرى عبر `WindowManager` بنوع نافذة `TYPE_ACCESSIBILITY_OVERLAY` (لا يحتاج صلاحية `SYSTEM_ALERT_WINDOW` المنفصلة لأنه صادر من خدمة إمكانية وصول).
  - الضغط على الزر يفتح **وضع تحديد**: طبقة شفافة تغطي الشاشة (`SelectionOverlayView.kt` جديد) يرسم فيها المستخدم مربعا بإصبعه.
  - عند رفع الإصبع، تُقرأ فقط عناصر `AccessibilityNodeInfo` (من `rootInActiveWindow`) التي تتقاطع حدودها مع المربع المرسوم، ويُنسخ نصها فقط للحافظة.
  - تم تحديث كل نصوص الشرح والشفافية (`strings.xml`، نافذة الشرح الأولى، وصف الخدمة في الإعدادات) لتعكس السلوك الجديد.
- إصدار: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.1.0

### 2026-09-04 — إصلاح حرج: منع الكتابة وزر الرجوع في كل التطبيقات (v1.1.1)
- بعد تجربة v1.1.0 فعليا، تبيّن خطأ حرج جدا: بمجرد ظهور الزر العائم، أصبح تعذّر **الكتابة في أي تطبيق آخر** وتعطّل **زر الرجوع** في النظام بالكامل، وكأن المستخدم "محبوس" داخل TextGrabber.
- **السبب**: نافذتا الزر العائم وطبقة التحديد (`WindowManager.LayoutParams`) لم تحملا خاصية `FLAG_NOT_FOCUSABLE`. أي نافذة `TYPE_ACCESSIBILITY_OVERLAY` بدون هذه الخاصية تسرق تركيز لوحة المفاتيح وزر الرجوع من **كامل النظام**، ولو كانت مجرد زر دائري صغير حجمه 56dp.
- **الإصلاح**: إضافة `FLAG_NOT_FOCUSABLE` لكلتا النافذتين (اللمس يستمر بالعمل بشكل طبيعي بدون الحاجة للتركيز، فقط لوحة المفاتيح وزر الرجوع لا يتأثران بعد الآن).
- **إصلاح إضافي لدقة اللمس**: طبقة التحديد لم تكن تُضاف بخاصيتي `FLAG_LAYOUT_IN_SCREEN` و`FLAG_LAYOUT_NO_LIMITS`، فكانت لا تغطي كامل الشاشة الفعلية (تتوقف قبل مناطق الشريط العلوي/السفلي)، مما يسبب عدم تطابق بين مكان لمس الإصبع الفعلي (`event.rawX/rawY`) ومكان رسم المربع على الشاشة. تمت إضافة الخاصيتين لتطابق الإحداثيات تماما.
- إصدار: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.1.1

## روابط المشروع
- المستودع: https://github.com/almobarakamjd/TextGrabber
- تحميل آخر APK: https://github.com/almobarakamjd/TextGrabber/releases/tag/v1.1.1

## كيفية التشغيل
راجع قسم "طريقة التشغيل والاختبار" الذي شرحه المساعد في المحادثة (فتح المشروع في Android Studio، مزامنة Gradle، تشغيله على جهاز حقيقي، تفعيل الخدمة من الإعدادات). بديلا عن ذلك، يمكن تحميل ملف APK الجاهز مباشرة من رابط الإصدار أعلاه وتثبيته على أي جهاز (بعد السماح بالتثبيت من مصادر غير معروفة).
