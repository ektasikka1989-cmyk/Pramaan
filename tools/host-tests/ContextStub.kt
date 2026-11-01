package android.content
// Minimal Context/SharedPreferences shape so pure helpers in Context-taking files
// (ArogyaLang) compile on the host. Tests only call the pure functions.
open class Context {
    open val applicationContext: Context get() = this
    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = throw UnsupportedOperationException()
    companion object { const val MODE_PRIVATE = 0 }
}
interface SharedPreferences {
    fun contains(key: String?): Boolean
    fun getBoolean(key: String?, defValue: Boolean): Boolean
    fun getString(key: String?, defValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putBoolean(key: String?, value: Boolean): Editor
        fun putString(key: String?, value: String?): Editor
        fun apply()
    }
}
