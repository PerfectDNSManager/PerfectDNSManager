package app.perfectdnsmanager.util

import android.content.Context
import android.content.ContextWrapper
import app.perfectdnsmanager.R

/** Renders a fixed UI-thread snapshot; no Activity or mutable screen fields. */
class SecureReportBuilder(context: Context, private val snapshot: Snapshot) : ContextWrapper(context.applicationContext.createConfigurationContext(context.resources.configuration)) {
    data class Snapshot(
        val lastCarrierName: String?, val lastIpv4: String?, val ipv6Status: String,
        val statusFirstLineSnapshot: String, val profileLabel: String, val kernelVersion: String,
        val lastBlockingResult: UrlBlockingTester.BlockingResult?,
        val lastLeakResult: DnsLeakTester.LeakResult?, val lastLeakIspResult: DnsLeakTester.LeakResult?,
        val lastSpeedResult: SpeedTester.SpeedResult?
    )
    fun build(includeNetwork: Boolean, includeSpeed: Boolean, includeLeak: Boolean, includeBlocking: Boolean, includeDevice: Boolean): String {
        val appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        return buildString {
                    appendLine("# Perfect DNS Manager \u2014 ${getString(R.string.md_report_title)}")
                    appendLine("*${getString(R.string.label_value_fmt, getString(R.string.version_label), appVersion)}*")
                    appendLine()

                    // Always include network info from saved fields (was tvStatusInfo, désormais peuplé via les 3 cards status)
                    if (includeNetwork) {
                        val localIp = app.perfectdnsmanager.util.LocalNetworkInfo.ipv4Address()

                        val connType = try {
                            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                val nc = cm.getNetworkCapabilities(cm.activeNetwork)
                                when {
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                                    nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "4G/5G"
                                    else -> getString(R.string.report_conn_unknown)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                when (cm.activeNetworkInfo?.type) {
                                    android.net.ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                                    android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                                    android.net.ConnectivityManager.TYPE_MOBILE -> "4G/5G"
                                    else -> getString(R.string.report_conn_unknown)
                                }
                            }
                        } catch (_: Exception) { getString(R.string.report_conn_unknown) }

                        appendLine("## ${getString(R.string.md_network_info)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_field)} | ${getString(R.string.md_value)} |")
                        appendLine("|-------|--------|")
                        appendLine("| **${getString(R.string.connection_label)}** | $connType |")
                        if (!snapshot.lastCarrierName.isNullOrEmpty()) {
                            appendLine("| **${getString(R.string.carrier_label)}** | `${snapshot.lastCarrierName}` |")
                        }
                        appendLine("| **${getString(R.string.md_local_ip)}** | `$localIp` |")
                        appendLine("| **IPv4** | `${snapshot.lastIpv4 ?: "N/A"}` |")
                        val ipv6Status = snapshot.ipv6Status
                        appendLine("| **IPv6** | `$ipv6Status` |")
                        // Snapshot capturé sur UI thread avant le launch — pas de read direct ici
                        appendLine("| **${getString(R.string.md_active_dns)}** | `${snapshot.statusFirstLineSnapshot}` |")
                        appendLine("| **${getString(R.string.report_label_profile)}** | ${snapshot.profileLabel} |")
                        appendLine("| **${getString(R.string.md_date)}** | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} |")
                        appendLine()
                    }

                    // 1. Blocking test
                    if (includeBlocking && snapshot.lastBlockingResult != null) {
                        val b = snapshot.lastBlockingResult!!
                        appendLine("## ${getString(R.string.md_url_blocking_test)}")
                        appendLine()
                        appendLine("${getString(R.string.md_tested_domain)} : `${b.domain}`")
                        appendLine()
                        appendLine("| ${getString(R.string.md_step)} | ${getString(R.string.md_result)} | IP | ${getString(R.string.md_authority)} |")
                        appendLine("|-------|----------|----|----|")
                        val beforeIcon = "${blockIcon(b.ispDns)} ${blockLabel(b.ispDns)}"
                        val beforeIp = b.ispDns.ip ?: b.ispDns.error ?: "N/A"
                        val beforeAuth = b.ispDns.authorityLabel ?: ""
                        appendLine("| **${getString(R.string.md_isp_dns_no_vpn)}** | $beforeIcon | `$beforeIp` | $beforeAuth |")
                        val afterIcon = "${blockIcon(b.activeDns)} ${blockLabel(b.activeDns)}"
                        val afterIp = b.activeDns.ip ?: b.activeDns.error ?: "N/A"
                        val afterAuth = b.activeDns.authorityLabel ?: ""
                        appendLine("| **${getString(R.string.md_active_dns_with_vpn)}** | $afterIcon | `$afterIp` | $afterAuth |")
                        appendLine()
                        if (b.ispDns.isUnknown || b.activeDns.isUnknown) {
                            // Un test qui n'a pas abouti ne permet aucune conclusion
                            // sur le blocage — surtout dans un rapport partagé.
                            appendLine("> ${getString(R.string.md_blocking_undetermined)}")
                        } else if (b.ispDns.isBlocked && !b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_dns_unblocks_success)}")
                        } else if (!b.ispDns.isBlocked && !b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_domain_accessible_both)}")
                        } else if (b.activeDns.isBlocked) {
                            appendLine("> ${getString(R.string.md_domain_still_blocked)}")
                        }
                        appendLine()
                    }

                    // 2. DNS Leak test
                    if (includeLeak && snapshot.lastLeakResult != null) {
                        appendLine("## ${getString(R.string.share_toggle_leak)}")
                        appendLine()

                        // ISP DNS (sans VPN)
                        if (snapshot.lastLeakIspResult != null) {
                            appendLine("### ${getString(R.string.dns_leak_isp_label)}")
                            appendLine()
                            appendLine("| ${getString(R.string.md_resolver_ip)} | ${getString(R.string.md_country)} | ${getString(R.string.md_isp)} |")
                            appendLine("|--------------|------|-----|")
                            if (snapshot.lastLeakIspResult!!.error != null) {
                                appendLine("| *${snapshot.lastLeakIspResult!!.error}* | - | - |")
                            } else if (snapshot.lastLeakIspResult!!.resolverIps.isEmpty()) {
                                appendLine("| *\u2014* | - | - |")
                            } else {
                                for (r in snapshot.lastLeakIspResult!!.resolverIps) {
                                    appendLine("| `${r.ip}` | ${r.country ?: "-"} | ${r.isp ?: "-"} |")
                                }
                            }
                            appendLine()
                        }

                        // VPN DNS (avec VPN)
                        val leak = snapshot.lastLeakResult!!
                        appendLine("### ${getString(R.string.dns_leak_vpn_label)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_resolver_ip)} | ${getString(R.string.md_country)} | ${getString(R.string.md_isp)} |")
                        appendLine("|--------------|------|-----|")
                        if (leak.error != null) {
                            appendLine("| *${leak.error}* | - | - |")
                        } else if (leak.resolverIps.isEmpty()) {
                            appendLine("| *\u2014* | - | - |")
                        } else {
                            for (r in leak.resolverIps) {
                                appendLine("| `${r.ip}` | ${r.country ?: "-"} | ${r.isp ?: "-"} |")
                            }
                        }
                        appendLine()

                        // Comparaison
                        if (snapshot.lastLeakIspResult != null) {
                            val ispIps = snapshot.lastLeakIspResult!!.resolverIps.map { it.ip }.toSet()
                            val vpnIps = leak.resolverIps.map { it.ip }.toSet()
                            if (ispIps.isNotEmpty() && vpnIps.isNotEmpty() && ispIps != vpnIps) {
                                appendLine("> ${getString(R.string.report_no_leak)}")
                            } else if (ispIps.isNotEmpty() && ispIps == vpnIps) {
                                appendLine("> ${getString(R.string.report_leak_detected)}")
                            }
                            appendLine()
                        }
                    }

                    // 3. Speedtest (basique)
                    if (includeSpeed && snapshot.lastSpeedResult != null) {
                        val speed = snapshot.lastSpeedResult!!
                        appendLine("## ${getString(R.string.share_toggle_speedtest)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_measure)} | ${getString(R.string.md_result)} |")
                        appendLine("|--------|----------|")
                        if (speed.pingMs >= 0) appendLine("| **${getString(R.string.speed_ping_label)}** | ${speed.pingMs} ms |")
                        appendLine("| **${getString(R.string.speed_download_label)}** | ${String.format(java.util.Locale.getDefault(), "%.1f", speed.downloadMbps)} Mbps |")
                        appendLine("| **${getString(R.string.speed_upload_label)}** | ${String.format(java.util.Locale.getDefault(), "%.1f", speed.uploadMbps)} Mbps |")
                        appendLine()
                    }

                    // 4. Device info (last)
                    if (includeDevice) {
                        val devType = if (packageManager.hasSystemFeature("android.software.leanback")) {
                            getString(R.string.device_type_tv)
                        } else if (resources.configuration.smallestScreenWidthDp >= 600) {
                            getString(R.string.device_type_tablet)
                        } else {
                            getString(R.string.device_type_phone)
                        }
                        appendLine("## ${getString(R.string.md_device_info)}")
                        appendLine()
                        appendLine("| ${getString(R.string.md_field)} | ${getString(R.string.md_value)} |")
                        appendLine("|-------|--------|")
                        appendLine("| **${getString(R.string.md_device_model)}** | ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} |")
                        appendLine("| **${getString(R.string.md_device_type)}** | $devType |")
                        appendLine("| **${getString(R.string.md_android_version)}** | ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) |")
                        appendLine("| **${getString(R.string.md_kernel_version)}** | ${snapshot.kernelVersion} |")
                        appendLine("| **${getString(R.string.md_app_version)}** | $appVersion |")
                        appendLine()
                    }
                    appendLine("---")
                    appendLine("*${getString(R.string.md_generated_by)} [Perfect DNS Manager](https://perfectdnsmanager.app/)*")
                }
    }
    private fun blockIcon(r: UrlBlockingTester.ResolutionResult) = when {
        r.isUnknown -> "⚠️"
        r.isBlocked -> "❌"
        else -> "✅"
    }
    private fun blockLabel(r: UrlBlockingTester.ResolutionResult) = getString(when {
        r.isUnknown -> R.string.report_undetermined
        r.isBlocked -> R.string.report_blocked
        else -> R.string.report_accessible
    })
}
