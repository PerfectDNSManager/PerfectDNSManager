# MAP COMPLÈTE — PerfectDNSManager v1.0.78

## Arborescence du projet

```
app/
├── version.properties                          # VERSION_MAJOR=1, VERSION_MINOR=0, VERSION_BUILD=78
├── build.gradle.kts                            # Config Gradle, dépendances, APK naming
├── src/main/
│   ├── AndroidManifest.xml                     # Permissions, activities, services, receivers
│   ├── aidl/
│   │   └── .../service/IShellService.aidl      # Interface AIDL pour Shizuku
│   ├── java/net/appstorefr/perfectdnsmanager/
│   │   ├── MainActivity.kt                     # [ÉCRAN] Page principale — VPN toggle, tests, rapports
│   │   ├── DnsSelectionActivity.kt             # [ÉCRAN] Sélection DNS — liste providers, drag-drop
│   │   ├── DnsProviderDetailActivity.kt        # [ÉCRAN] Détail provider — catégories extensibles
│   │   ├── DnsSpeedtestActivity.kt             # [ÉCRAN] Speedtest DNS — latence par provider
│   │   ├── DomainTesterActivity.kt             # [ÉCRAN] Test de domaines bloqués — ISP vs VPN
│   │   ├── InternetSpeedtestActivity.kt        # [ÉCRAN] Speedtest internet — LibreSpeed natif
│   │   ├── SettingsActivity.kt                 # [ÉCRAN] Paramètres — toggles, ADB, Shizuku, split tunnel
│   │   ├── DnsRewriteActivity.kt               # [ÉCRAN] Réécriture DNS — règles domaine→domaine
│   │   ├── AboutActivity.kt                    # [ÉCRAN] À propos — version, màj, GPL, crédits
│   │   ├── HowToActivity.kt                    # [ÉCRAN] Guide ADB — instructions débogage
│   │   ├── LanguageSelectionActivity.kt        # [ÉCRAN] Choix langue — 12 langues
│   │   ├── SupportActivity.kt                  # [ÉCRAN] Support — dons crypto (8 monnaies)
│   │   │
│   │   ├── data/
│   │   │   ├── DnsProfile.kt                   # [MODÈLE] Profil DNS + 56 presets + ratings + icônes
│   │   │   ├── DnsType.kt                      # [ENUM] DEFAULT, DOH, DOT, DOQ
│   │   │   ├── ProfileManager.kt               # [DATA] CRUD profils + auto-sync presets
│   │   │   ├── DnsProfileRepository.kt         # [DATA] Repository profils (defaults + custom)
│   │   │   ├── DnsRewriteRule.kt               # [MODÈLE] Règle réécriture (from→to, enabled)
│   │   │   ├── DnsRewriteRepository.kt         # [DATA] CRUD règles réécriture
│   │   │   └── ConfigManager.kt                # [DATA] Export/Import config JSON chiffrée
│   │   │
│   │   ├── service/
│   │   │   ├── DnsVpnService.kt                # [SERVICE] VPN local — DoH/DoQ/Standard, TUN, threads
│   │   │   ├── BootReceiver.kt                 # [RECEIVER] Boot — auto-reconnect VPN après 8s
│   │   │   ├── AdbDnsManager.kt                # [SERVICE] Private DNS via ADB TCP (ports 5555-5557)
│   │   │   ├── ShizukuManager.kt               # [SERVICE] Private DNS via Shizuku
│   │   │   ├── ShellService.kt                 # [AIDL] Service shell pour Shizuku (exec commands)
│   │   │   ├── DoQClient.kt                    # [CLIENT] DNS over QUIC (kwik, connection pooling)
│   │   │   └── UpdateManager.kt                # [SERVICE] Mise à jour via GitHub API + Fuel
│   │   │
│   │   ├── ui/
│   │   │   ├── ProviderAdapter.kt              # [ADAPTER] RecyclerView providers (icône, nom, badges)
│   │   │   ├── AddProfileDialog.kt             # [DIALOG] Ajout profil custom (DoH/DoQ/DoT/Standard)
│   │   │   └── DnsRewriteAdapter.kt            # [ADAPTER] RecyclerView règles réécriture
│   │   │
│   │   └── util/
│   │       ├── DnsColors.kt                    # [UTIL] Couleurs protocoles (violet/cyan/vert/gris)
│   │       ├── DnsLeakTester.kt                # [UTIL] Test fuite DNS — ISP vs VPN comparison
│   │       ├── DnsTester.kt                    # [UTIL] Requête DNS brute + mesure latence
│   │       ├── UrlBlockingTester.kt            # [UTIL] Test blocage domaine — ISP vs actif
│   │       ├── EncryptedSharer.kt              # [UTIL] Chiffrement AES-256-GCM + upload tmpfiles
│   │       ├── LocaleHelper.kt                 # [UTIL] Application locale (12 langues)
│   │       ├── SpeedTester.kt                  # [UTIL] Speedtest rapide (Cloudflare/tele2)
│   │       └── BlockingAuthoritiesManager.kt   # [UTIL] Base IPs de blocage (sync GitHub Pages)
│   │
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml               # Layout principal
│       │   ├── activity_dns_selection.xml       # Layout sélection DNS
│       │   ├── activity_dns_provider_detail.xml # Layout détail provider
│       │   ├── activity_dns_speedtest.xml       # Layout speedtest DNS
│       │   ├── activity_dns_rewrite.xml         # Layout réécriture DNS
│       │   ├── activity_settings.xml            # Layout paramètres
│       │   ├── activity_about.xml               # Layout à propos
│       │   ├── activity_howto.xml               # Layout guide ADB
│       │   ├── activity_language_selection.xml   # Layout choix langue
│       │   ├── activity_support.xml             # Layout support crypto
│       │   ├── item_profile.xml                 # Item provider (icône + nom + badges + ratings)
│       │   ├── item_dns_category_header.xml     # Header catégorie extensible (détail provider)
│       │   ├── item_dns_profile_detail.xml      # Item profil individuel (détail provider)
│       │   ├── item_dns_rewrite_rule.xml        # Item règle réécriture
│       │   ├── dialog_add_profile.xml           # Dialog ajout profil
│       │   └── dialog_add_rewrite_rule.xml      # Dialog ajout règle
│       │
│       ├── drawable/
│       │   ├── ic_launcher_foreground.png       # Icône app (logo PDM cyberpunk)
│       │   ├── ic_launcher_background.xml       # Fond icône app
│       │   ├── banner.png                       # Banner Android TV
│       │   ├── ic_settings.xml                  # Icône engrenage
│       │   ├── btn_activate_background.xml      # Fond bouton activer (vert)
│       │   ├── btn_deactivate_background.xml    # Fond bouton désactiver (rouge)
│       │   ├── btn_focus_foreground.xml         # Overlay focus boutons
│       │   ├── focusable_item_background.xml    # Fond items focusables (D-pad)
│       │   ├── focusable_header_background.xml  # Fond headers focusables
│       │   ├── default_background.xml           # Fond par défaut
│       │   ├── ic_adguard.png                   # Logo AdGuard
│       │   ├── ic_cloudflare.png                # Logo Cloudflare
│       │   ├── ic_controld.png                  # Logo ControlD
│       │   ├── ic_dnssb.png                     # Logo dns.sb
│       │   ├── ic_fdn.xml                       # Logo FDN (vector)
│       │   ├── ic_google.png                    # Logo Google
│       │   ├── ic_mullvad.png                   # Logo Mullvad
│       │   ├── ic_nextdns.png                   # Logo NextDNS
│       │   ├── ic_quad9.png                     # Logo Quad9
│       │   ├── ic_surfshark.png                 # Logo Surfshark
│       │   ├── ic_yandex.png                    # Logo Yandex
│       │   ├── ic_orange.png                    # Logo Orange
│       │   ├── ic_free.png                      # Logo Free
│       │   ├── ic_sfr.png                       # Logo SFR
│       │   ├── ic_bouygues.png                  # Logo Bouygues
│       │   ├── ic_ovh.xml                       # Logo OVH (vector)
│       │   ├── ic_dns_custom.xml                # Logo DNS custom (vector)
│       │   ├── qr_btc.png                       # QR Bitcoin
│       │   ├── qr_evm.png                       # QR EVM
│       │   ├── qr_tron.png                      # QR TRON
│       │   ├── qr_ton.png                       # QR TON
│       │   ├── qr_sol.png                       # QR Solana
│       │   ├── qr_ltc.png                       # QR Litecoin
│       │   ├── qr_zec.png                       # QR Zcash
│       │   └── qr_xrp.png                       # QR XRP
│       │
│       ├── values/
│       │   ├── strings.xml                      # Strings FR (langue par défaut)
│       │   ├── colors.xml                       # Couleurs
│       │   └── themes.xml                       # Thèmes (NoActionBar)
│       │
│       ├── values-en/strings.xml                # Strings EN
│       ├── values-es/strings.xml                # Strings ES
│       ├── values-de/strings.xml                # Strings DE
│       ├── values-it/strings.xml                # Strings IT
│       ├── values-pt-rBR/strings.xml            # Strings PT-BR
│       ├── values-ru/strings.xml                # Strings RU
│       ├── values-zh-rCN/strings.xml            # Strings ZH-CN
│       ├── values-ar/strings.xml                # Strings AR
│       ├── values-hi/strings.xml                # Strings HI
│       ├── values-bn/strings.xml                # Strings BN
│       ├── values-ja/strings.xml                # Strings JA
│       │
│       ├── mipmap-*/                            # Icônes app (hdpi → xxxhdpi)
│       │   ├── ic_launcher.png
│       │   └── ic_launcher_round.png
│       ├── mipmap-anydpi-v26/
│       │   ├── ic_launcher.xml                  # Adaptive icon
│       │   └── ic_launcher_round.xml
│       │
│       └── xml/
│           ├── backup_rules.xml                 # Règles backup Android
│           ├── data_extraction_rules.xml        # Règles extraction données
│           └── file_paths.xml                   # FileProvider paths (pour APK update)
│
docs/                                            # GitHub Pages
├── index.html                                   # Landing page (redirection par langue)
├── fr.html                                      # Page FR
├── en.html                                      # Page EN
├── es.html                                      # Page ES
├── de.html                                      # Page DE
├── it.html                                      # Page IT
├── pt.html                                      # Page PT
├── ru.html                                      # Page RU
├── zh.html                                      # Page ZH
├── ar.html                                      # Page AR
├── hi.html                                      # Page HI
├── bn.html                                      # Page BN
├── ja.html                                      # Page JA
├── decrypt.html                                 # Déchiffreur de rapports/configs
├── dns-providers.html                           # Liste fournisseurs DNS
├── support.html                                 # Page support/dons
└── blocking-authorities.json                    # Base IPs de blocage
```

---

## Flux de navigation

```
LanguageSelectionActivity (premier lancement)
         │
         ▼
    MainActivity ◄──────────────────────────────────────┐
    ├── [Sélection DNS] ──► DnsSelectionActivity         │
    │                          ├── [Clic nom] → retour profil à Main
    │                          ├── [Clic badge] ──► DnsProviderDetailActivity
    │                          │                      ├── [Clic profil] → retour à Main
    │                          │                      └── [Long-clic custom] → éditer/supprimer
    │                          ├── [+ Profil] ──► AddProfileDialog
    │                          └── [Speedtest] ──► DnsSpeedtestActivity
    │
    ├── [Test domaines] ──► DomainTesterActivity
    ├── [DNS Speedtest] ──► DnsSpeedtestActivity
    ├── [Internet Speedtest] ──► InternetSpeedtestActivity
    │
    ├── [Paramètres] ──► SettingsActivity
    │                      ├── [Guide ADB] ──► HowToActivity
    │                      ├── [À propos] ──► AboutActivity
    │                      │                    └── [Support] ──► SupportActivity
    │                      ├── [DNS Rewrite] ──► DnsRewriteActivity
    │                      └── [Split Tunnel] → AlertDialog (liste apps)
    │
    ├── [Langue] ──► LanguageSelectionActivity
    └── [À propos] ──► AboutActivity
```

---

## Flux VPN

```
Utilisateur clique "Activer"
         │
         ▼
  Profil sélectionné ?
  ├── NON → "Sélectionnez un DNS"
  └── OUI → Quel type ?
       ├── DoT → AdbDnsManager.enablePrivateDns()
       │          ├── Settings API directe (si permission)
       │          ├── Shizuku (si disponible)
       │          └── ADB TCP localhost:5555-5557
       │
       └── DoH/DoQ/Standard → VPN
            │
            ▼
       VPN Permission accordée ?
       ├── NON → VpnService.prepare() → demande système
       └── OUI → DnsVpnService.ACTION_START
                    │
                    ▼
              startForeground() ← IMMÉDIAT
                    │
                    ▼
              Créer TUN (192.168.50.1/24)
              ├── Ajouter routes DNS
              ├── Ajouter routes IPv6 (si blocage)
              ├── Exclure apps (split tunnel)
              └── Démarrer threads
                   ├── tunReaderThread → lit paquets TUN
                   │    └── Filtre DNS (port 53)
                   │         ├── Appliquer rewrite rules
                   │         ├── Résoudre via protocole :
                   │         │    ├── DoH → OkHttp POST
                   │         │    ├── DoQ → kwik QUIC stream
                   │         │    └── Standard → UDP protect()
                   │         └── Écrire réponse dans TUN
                   └── dnsReceiverThread → reçoit réponses async
```

---

## Flux partage chiffré

```
Utilisateur clique "Partager rapport"
         │
         ▼
  Génère contenu rapport/config
         │
         ▼
  AES-256-GCM encrypt (clé aléatoire, IV 12 bytes)
         │
         ▼
  Base64 encode → upload tmpfiles.org
         │
         ▼
  Raccourcit URL via is.gd (code numérique)
         │
         ▼
  Affiche dialog avec :
  ├── Code court (ex: is.gd/abc123)
  ├── Lien decrypt : https://appstorefr.github.io/.../decrypt.html#url|key
  └── Liens cliquables (URLSpan + LinkMovementMethod)
```

---

## Flux mise à jour

```
AboutActivity → "Vérifier mise à jour"
         │
         ▼
  GitHub API: /repos/.../releases/latest
         │
         ▼
  Compare versions (semver)
  ├── À jour → Toast "App à jour"
  └── Nouvelle version →
       Dialog :
       ├── Titre: version + taille (Mo)
       ├── [Installer] → Fuel download → FileProvider → installer
       └── [Plus tard] → sauvegarde dismissed_version
```

---

## Flux boot automatique

```
Système → BOOT_COMPLETED
         │
         ▼
  BootReceiver.onReceive()
         │
         ▼
  auto_start_enabled ?
  ├── NON → rien
  └── OUI → last_method ?
       ├── ADB/Shizuku/Settings → rien (DNS persiste)
       └── VPN → auto_reconnect_dns ?
            ├── NON → notification "Ouvrir l'app"
            └── OUI → délai 8s → DnsVpnService.ACTION_START
                 └── Si permission VPN manquante → notification
```

---

## SharedPreferences — Vue complète

### `prefs` (MODE_PRIVATE)
```
language                    : String    # Code langue (fr, en, es...)
selected_profile_json       : String    # JSON du profil DNS sélectionné
default_profile_json        : String    # JSON du profil par défaut (★)
vpn_active                  : Boolean   # VPN en cours
vpn_label                   : String    # Label affiché
last_method                 : String    # VPN / ADB / Settings / Shizuku
auto_start_enabled          : Boolean   # Démarrage auto au boot
auto_reconnect_dns          : Boolean   # Reconnexion auto VPN au boot
operator_dns_enabled        : Boolean   # Afficher DNS opérateurs
adb_dot_enabled             : Boolean   # Afficher/activer DoT via ADB
advanced_features_enabled   : Boolean   # Fonctions avancées
show_standard_dns           : Boolean   # Afficher DNS standard non chiffré
show_profile_variants       : Boolean   # Afficher toutes les variantes
show_doq_dns                : Boolean   # Afficher profils DoQ
disable_ipv6                : Boolean   # Bloquer IPv6 via VPN
provider_order_json         : String    # Ordre providers JSON array
test_domains_json           : String    # Domaines de test JSON array
excluded_apps_json          : String    # Apps exclues du VPN JSON array
adb_permission_granted      : Boolean   # Permission ADB accordée
```

### `dns_profiles_v2`
```
profiles                    : String    # JSON array de DnsProfile
```

### `custom_dns_profiles`
```
profiles                    : String    # JSON array de DnsProfile (custom only)
```

### `dns_rewrite_rules`
```
rules                       : String    # JSON array de DnsRewriteRule
```

### `nextdns_profiles`
```
profile_ids                 : StringSet # IDs NextDNS custom
```

### `update_prefs`
```
dismissed_version           : String    # Version de màj refusée
```

### `adb_prefs`
```
adb_permission_self_granted : Boolean   # Auto-grant ADB réussi
last_adb_port               : Int       # Dernier port ADB qui a fonctionné
```

### `blocking_authorities`
```
authorities_json            : String    # JSON des autorités de blocage
authorities_version         : Int       # Version du fichier
```

---

## Classes par couche

### Présentation (Activities) — 12
| Classe | Layout | Description |
|--------|--------|-------------|
| `MainActivity` | `activity_main.xml` | Page principale |
| `DnsSelectionActivity` | `activity_dns_selection.xml` | Choix DNS |
| `DnsProviderDetailActivity` | `activity_dns_provider_detail.xml` | Détail provider |
| `DnsSpeedtestActivity` | `activity_dns_speedtest.xml` | Speedtest DNS |
| `DomainTesterActivity` | *(programmatique)* | Test domaines bloqués |
| `InternetSpeedtestActivity` | *(programmatique)* | Speedtest internet |
| `SettingsActivity` | `activity_settings.xml` | Paramètres |
| `DnsRewriteActivity` | `activity_dns_rewrite.xml` | Réécriture DNS |
| `AboutActivity` | `activity_about.xml` | À propos |
| `HowToActivity` | `activity_howto.xml` | Guide ADB |
| `LanguageSelectionActivity` | `activity_language_selection.xml` | Choix langue |
| `SupportActivity` | `activity_support.xml` | Dons crypto |

### Données (Data) — 7
| Classe | Type | Description |
|--------|------|-------------|
| `DnsProfile` | data class | Profil DNS (56 presets + custom) |
| `DnsType` | enum | DEFAULT, DOH, DOT, DOQ |
| `ProfileManager` | class | CRUD + auto-sync presets |
| `DnsProfileRepository` | class | Repository defaults + custom |
| `DnsRewriteRule` | data class | Règle from→to |
| `DnsRewriteRepository` | class | CRUD règles |
| `ConfigManager` | class | Export/Import config |

### Services — 7
| Classe | Type | Description |
|--------|------|-------------|
| `DnsVpnService` | VpnService | Service VPN local |
| `BootReceiver` | BroadcastReceiver | Auto-start au boot |
| `AdbDnsManager` | class | Private DNS via ADB |
| `ShizukuManager` | class | Private DNS via Shizuku |
| `ShellService` | AIDL Stub | Shell commands pour Shizuku |
| `DoQClient` | class | Client DNS over QUIC |
| `UpdateManager` | class | Mises à jour GitHub |

### UI Components — 3
| Classe | Type | Description |
|--------|------|-------------|
| `ProviderAdapter` | RecyclerView.Adapter | Liste providers DNS |
| `AddProfileDialog` | Dialog | Ajout profil custom |
| `DnsRewriteAdapter` | RecyclerView.Adapter | Liste règles réécriture |

### Utilitaires — 8
| Classe | Type | Description |
|--------|------|-------------|
| `DnsColors` | object | Couleurs protocoles |
| `DnsLeakTester` | object | Test fuite DNS |
| `DnsTester` | object | Requête DNS brute + latence |
| `UrlBlockingTester` | object | Test blocage domaine |
| `EncryptedSharer` | class | Chiffrement AES-256 + upload |
| `LocaleHelper` | object | Gestion locale/langue |
| `SpeedTester` | object | Speedtest rapide |
| `BlockingAuthoritiesManager` | object | Base IPs blocage |

**Total : 37 classes Kotlin + 16 layouts XML + 12 fichiers strings + 17 icônes providers**
