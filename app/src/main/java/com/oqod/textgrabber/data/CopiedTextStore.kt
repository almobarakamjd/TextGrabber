package com.oqod.textgrabber.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * مخزن بسيط في الذاكرة (in-memory) لآخر النصوص المنسوخة.
 *
 * تم اختيار حل بسيط باستخدام SnapshotStateList بدلاً من قاعدة بيانات Room
 * لأن المتطلب هو الاحتفاظ بآخر 10 نصوص فقط أثناء تشغيل التطبيق، وهذا
 * يكفي لعرضها في واجهة Compose بشكل تفاعلي (تتحدث الواجهة تلقائيًا).
 *
 * ملاحظة: بما أن الخدمة (Service) والواجهة (Activity) يعملان في نفس العملية
 * (نفس التطبيق)، فإن الكائن singleton هذا مشترك بينهما مباشرة دون الحاجة
 * لأي آلية اتصال بين العمليات (IPC).
 */
object CopiedTextStore {

    private const val MAX_ITEMS = 10

    private val _items: SnapshotStateList<CopiedTextEntry> = mutableStateListOf()
    val items: SnapshotStateList<CopiedTextEntry> get() = _items

    @Synchronized
    fun addText(text: String) {
        _items.add(0, CopiedTextEntry(text = text, timestampMillis = System.currentTimeMillis()))
        while (_items.size > MAX_ITEMS) {
            _items.removeAt(_items.lastIndex)
        }
    }

    @Synchronized
    fun clear() {
        _items.clear()
    }
}

data class CopiedTextEntry(
    val text: String,
    val timestampMillis: Long
)
