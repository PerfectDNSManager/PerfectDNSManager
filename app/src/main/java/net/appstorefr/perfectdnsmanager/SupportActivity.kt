package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import net.appstorefr.perfectdnsmanager.util.pdmDanger

class SupportActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    data class CryptoOption(
        val label: String,
        val address: String,
        val qrDrawable: Int,
        val color: Int,
        val networks: String = ""
    )

    private val cryptos by lazy { listOf(
        CryptoOption("₿ Bitcoin (BTC)", "bc1qfaqkqzvkj2cdlqhmzkh22lm5lcuk6a5y8raulz", R.drawable.qr_btc, 0xFFF7931A.toInt()),
        CryptoOption("⬡ EVM (ETH / BNB / USDT / USDC…)", "0x71528115FA8aee4D9420F6caDEBfD0C043a316f7", R.drawable.qr_evm, 0xFF627EEA.toInt(), "Base · Polygon · Ethereum · BNB · Gnosis"),
        CryptoOption("⚡ TRON (USDT / USDC / TRX)", "TV14NpEfmhz72CUBpVyYYRDrwB6hHXUQBu", R.drawable.qr_tron, pdmDanger()),
        CryptoOption("💎 TON (USDT / TON)", "UQBvUM90I_T0_e1HAtamIYyCe_bPR4IkMmhzbOMrRde-OUt_", R.drawable.qr_ton, 0xFF0088CC.toInt()),
        CryptoOption("◎ Solana (USDT / USDC / SOL)", "34i3oYht6hfyS6QdZbXnRZtmzQJYXqPcQtXwTmfEBM9y", R.drawable.qr_sol, 0xFF9945FF.toInt()),
        CryptoOption("Ł Litecoin (LTC)", "ltc1qehl698y09u3e3p54zvuxe3d93d6tsfc0d5hdkr", R.drawable.qr_ltc, 0xFFBFBBBB.toInt()),
        CryptoOption("🛡 Zcash (ZEC)", "t1VbmN4XXoUZuU4BcUQLMCCaH7CrMCyVf5g", R.drawable.qr_zec, 0xFFF4B728.toInt()),
        CryptoOption("✕ XRP (XRP / RLUSD)", "rJcav6qAFHorerfAHFPbu8z2cLtEZGiJUM", R.drawable.qr_xrp, 0xFF00AAE4.toInt()),
    ) }

    private var hasSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)

        val btnBack: Button = findViewById(R.id.btnBack)
        val btnSelectCrypto: Button = findViewById(R.id.btnSelectCrypto)
        val tvCryptoName: TextView = findViewById(R.id.tvCryptoName)
        val ivQrCode: ImageView = findViewById(R.id.ivQrCode)
        val tvAddress: TextView = findViewById(R.id.tvAddress)
        val tvNetworks: TextView = findViewById(R.id.tvNetworks)

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }

        // Affichage par défaut : cœur + invitation
        tvCryptoName.text = "❤️"
        tvCryptoName.textSize = 60f
        tvCryptoName.setTextColor(pdmDanger())
        ivQrCode.visibility = View.GONE
        tvAddress.visibility = View.GONE
        tvNetworks.visibility = View.GONE

        btnSelectCrypto.text = getString(R.string.select_crypto_button)

        btnSelectCrypto.setOnClickListener {
            val labels = cryptos.map { it.label }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.choose_crypto_title))
                .setItems(labels) { _, which ->
                    hasSelected = true
                    val crypto = cryptos[which]

                    tvCryptoName.textSize = 18f
                    tvCryptoName.text = crypto.label
                    tvCryptoName.setTextColor(crypto.color)

                    ivQrCode.visibility = View.VISIBLE
                    ivQrCode.setImageResource(crypto.qrDrawable)

                    tvAddress.visibility = View.VISIBLE
                    tvAddress.text = crypto.address
                    tvAddress.setTextColor(crypto.color)

                    if (crypto.networks.isNotEmpty()) {
                        tvNetworks.text = crypto.networks
                        tvNetworks.visibility = View.VISIBLE
                    } else {
                        tvNetworks.visibility = View.GONE
                    }

                    btnSelectCrypto.text = getString(R.string.select_crypto_button)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }
}
