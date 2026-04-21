package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class LanguageSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val language = prefs.getString("language", null)
        val forceShow = intent.getBooleanExtra("FORCE_SHOW", false)

        // Si langue déjà choisie et pas forcé (ex: depuis Settings), aller directement au MainActivity
        if (language != null && !forceShow) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_language_selection)

        findViewById<Button>(R.id.btnFrancais).apply {
            requestFocus()
            setOnClickListener { saveLanguageAndStart("fr") }
        }
        findViewById<Button>(R.id.btnEnglish).setOnClickListener { saveLanguageAndStart("en") }
        findViewById<Button>(R.id.btnEspanol).setOnClickListener { saveLanguageAndStart("es") }
        findViewById<Button>(R.id.btnItaliano).setOnClickListener { saveLanguageAndStart("it") }
        findViewById<Button>(R.id.btnPortugues).setOnClickListener { saveLanguageAndStart("pt") }
        findViewById<Button>(R.id.btnRussian).setOnClickListener { saveLanguageAndStart("ru") }
        findViewById<Button>(R.id.btnChinese).setOnClickListener { saveLanguageAndStart("zh") }
        findViewById<Button>(R.id.btnArabic).setOnClickListener { saveLanguageAndStart("ar") }
        findViewById<Button>(R.id.btnHindi).setOnClickListener { saveLanguageAndStart("hi") }
        findViewById<Button>(R.id.btnBengali).setOnClickListener { saveLanguageAndStart("bn") }
        findViewById<Button>(R.id.btnJapanese).setOnClickListener { saveLanguageAndStart("ja") }
        findViewById<Button>(R.id.btnDeutsch).setOnClickListener { saveLanguageAndStart("de") }
    }

    private fun saveLanguageAndStart(langCode: String) {
        prefs.edit().putString("language", langCode).apply()
        startMainActivity()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
