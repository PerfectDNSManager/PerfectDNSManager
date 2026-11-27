package app.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.perfectdnsmanager.data.DnsPrefilterRepository
import app.perfectdnsmanager.data.DnsPrefilter
import app.perfectdnsmanager.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsPrefilterActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LocaleHelper.applyLocale(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_prefilter)
        val repository = DnsPrefilterRepository(this)
        val enabled = findViewById<CheckBox>(R.id.prefilterEnabled)
        val builtin = findViewById<CheckBox>(R.id.prefilterBuiltin)
        val blocked = findViewById<EditText>(R.id.prefilterBlocked)
        val allowed = findViewById<EditText>(R.id.prefilterAllowed)
        val save = findViewById<Button>(R.id.prefilterSave)
        if (savedInstanceState == null) {
            val settings = repository.settings()
            enabled.isChecked = settings.enabled; builtin.isChecked = settings.builtin
            blocked.setText(settings.blocked); allowed.setText(settings.allowed)
            enabled.requestFocus()
        }
        findViewById<Button>(R.id.prefilterCancel).setOnClickListener { finish() }
        save.setOnClickListener {
            for (field in listOf(blocked, allowed)) {
                if (runCatching { DnsPrefilter.parseDomains(field.text.toString()) }.isFailure) {
                    field.error = getString(R.string.prefilter_invalid)
                    field.requestFocus()
                    return@setOnClickListener
                }
            }
            val settings = DnsPrefilterRepository.Settings(enabled.isChecked, builtin.isChecked,
                blocked.text.toString(), allowed.text.toString())
            save.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { repository.save(settings) } }
                if (result.isSuccess) finish() else {
                    save.isEnabled = true
                    Toast.makeText(this@DnsPrefilterActivity, R.string.prefilter_save_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
