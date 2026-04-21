# SPEC — PerfectDNSManager v1.0.78

## Informations générales

| Champ | Valeur |
|-------|--------|
| Package | `net.appstorefr.perfectdnsmanager` |
| Version | 1.0.78 (build 78) |
| minSdk | 21 (Android 5.0) |
| targetSdk | 34 (Android 14) |
| Gradle | 9.1.0 |
| Kotlin | 2.0.0 |
| AGP | 8.7.3 |
| Java | 17 |
| Langues | FR, EN, ES, IT, PT, RU, ZH, AR, HI, BN, JA, DE (12) |
| Plateformes | Mobile, Android TV, Fire TV |
| Licence | GPL v3 |
| GitHub | https://github.com/appstorefr/PerfectDNSManager |
| GitHub Pages | https://appstorefr.github.io/PerfectDNSManager/ |

## Dépendances principales

| Librairie | Version | Usage |
|-----------|---------|-------|
| OkHttp | 4.12.0 | Requêtes HTTP, DoH, speedtest |
| Gson | 2.11.0 | Sérialisation JSON |
| Fuel | 2.3.1 | Téléchargement APK (UpdateManager) |
| Shizuku | 13.1.5 | Accès privilégié pour Private DNS |
| kwik | 0.10.8 | Client QUIC pour DoQ |
| AndroidX | RecyclerView, AppCompat, Activity | UI standard |

## Permissions

| Permission | Usage |
|------------|-------|
| `INTERNET` | Résolution DNS, speedtest, mises à jour |
| `ACCESS_NETWORK_STATE` | Détection réseau, DNS leak test |
| `FOREGROUND_SERVICE` | Service VPN en premier plan |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Type de service VPN |
| `BIND_VPN_SERVICE` | Liaison au service VPN |
| `REQUEST_INSTALL_PACKAGES` | Installation de mises à jour APK |
| `WRITE_SECURE_SETTINGS` | Configuration Private DNS (DoT) |
| `RECEIVE_BOOT_COMPLETED` | Démarrage automatique au boot |
| `QUERY_ALL_PACKAGES` | Liste d'apps pour split tunneling |

## Features Android TV / Leanback

```xml
<uses-feature android:name="android.software.leanback" android:required="false" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
```

---

## Méthodes DNS supportées

### 1. VPN Local (DoH / DoQ / Standard)
- Crée un tunnel VPN local (`192.168.50.1/24`, MTU 1500)
- Intercepte toutes les requêtes DNS du système
- Résout via le protocole choisi avec des sockets protégés (bypass VPN)
- Support DNS Rewrite (réécriture de domaines)
- Support IPv6 blocking (`::1/128` + route `::0/0`)
- Split tunneling (exclusion d'apps)

### 2. Private DNS via ADB (DoT)
- Modifie `private_dns_mode` et `private_dns_specifier` dans Settings.Global
- 3 méthodes en cascade :
  1. **Settings API directe** (si `WRITE_SECURE_SETTINGS` accordée)
  2. **Shizuku** (service privilégié, si installé et autorisé)
  3. **ADB TCP** (connexion localhost ports 5555-5557, échange RSA)

### 3. Protocoles DNS

| Protocole | Couleur | Code couleur | Port/Méthode |
|-----------|---------|-------------|--------------|
| DoH (DNS over HTTPS) | Violet | `#BF00FF` | HTTPS RFC 8484 via OkHttp |
| DoQ (DNS over QUIC) | Cyan | `#00D4FF` | QUIC RFC 9250 via kwik |
| DoT (DNS over TLS) | Vert | `#00FF66` | TLS RFC 7858 via ADB/Shizuku |
| Standard (UDP) | Gris | `#888888` | UDP port 53 |

**Priorité de sélection rapide** : DoH > DoQ > DoT > Standard

---

## Fournisseurs DNS intégrés (56 profils)

### ControlD — Speed: ★★★★★ Privacy: ★★★★★
| Profil | DoQ | DoH | DoT | Standard |
|--------|-----|-----|-----|----------|
| Unfiltered | `quic://freedns.controld.com/p0` | `https://freedns.controld.com/p0` | `p0.freedns.controld.com` | `76.76.2.0` / `76.76.10.0` |
| Malware | `quic://freedns.controld.com/p1` | `https://freedns.controld.com/p1` | `p1.freedns.controld.com` | `76.76.2.1` / `76.76.10.1` |
| Ads & Tracking | `quic://freedns.controld.com/p2` | `https://freedns.controld.com/p2` | `p2.freedns.controld.com` | `76.76.2.2` / `76.76.10.2` |

### NextDNS — Speed: ★★★★☆ Privacy: ★★★★★
| Profil | DoQ | DoH | DoT | Standard |
|--------|-----|-----|-----|----------|
| Standard | `quic://dns.nextdns.io` | `https://dns.nextdns.io` | `dns.nextdns.io` | `45.90.28.0` / `45.90.30.0` |
| *+ profils personnalisés via ID* | ✓ | ✓ | ✓ | — |

### AdGuard — Speed: ★★★★★ Privacy: ★★★★☆
| Profil | DoQ | DoH | DoT | Standard |
|--------|-----|-----|-----|----------|
| Unfiltered | `quic://unfiltered.adguard-dns.com` | `https://unfiltered.adguard-dns.com/dns-query` | `unfiltered.adguard-dns.com` | `94.140.14.140` / `94.140.14.141` |
| Standard | `quic://dns.adguard-dns.com` | `https://dns.adguard-dns.com/dns-query` | `dns.adguard-dns.com` | `94.140.14.14` / `94.140.15.15` |
| Family | `quic://family.adguard-dns.com` | — | — | `94.140.14.15` / `94.140.15.16` |

### Surfshark — Speed: ★★★☆☆ Privacy: ★★★★☆
| Profil | DoQ | DoH | DoT | Standard |
|--------|-----|-----|-----|----------|
| Standard | `quic://dns.surfsharkdns.com` | `https://dns.surfsharkdns.com/dns-query` | `dns.surfsharkdns.com` | `194.169.169.169` |

### Mullvad — Speed: ★★★★★ Privacy: ★★★★★
| Profil | DoQ | DoH | DoT | Standard |
|--------|-----|-----|-----|----------|
| Standard | `quic://dns.mullvad.net` | `https://dns.mullvad.net/dns-query` | `dns.mullvad.net` | — |
| Adblock | `quic://adblock.dns.mullvad.net` | `https://adblock.dns.mullvad.net/dns-query` | — | — |
| Base | `quic://base.dns.mullvad.net` | `https://base.dns.mullvad.net/dns-query` | `base.dns.mullvad.net` | — |

### Cloudflare — Speed: ★★★★★ Privacy: ★★★★★
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| Standard | `https://one.one.one.one/dns-query` | `one.one.one.one` | `1.1.1.1` / `1.0.0.1` |
| Malware Blocking | — | — | `1.1.1.2` / `1.0.0.2` |
| Family | — | — | `1.1.1.3` / `1.0.0.3` |

### Quad9 — Speed: ★★★★★ Privacy: ★★★★☆
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| Standard | `https://dns.quad9.net/dns-query` | `dns.quad9.net` | `9.9.9.9` / `149.112.112.112` |
| Unsecured | `https://dns10.quad9.net/dns-query` | `dns10.quad9.net` | `9.9.9.10` / `149.112.112.10` |

### FDN (French Data Network) — Speed: ★★★☆☆ Privacy: ★★★★★
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| ns0 | `https://ns0.fdn.fr/dns-query` | `ns0.fdn.fr` | `80.67.169.12` |
| ns1 | `https://ns1.fdn.fr/dns-query` | `ns1.fdn.fr` | `80.67.169.40` |

### dns.sb — Speed: ★★★☆☆ Privacy: ★★★★★
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| Standard | `https://doh.dns.sb/dns-query` | `dot.sb` | `185.222.222.222` / `45.11.45.11` |

### Yandex — Speed: ★★★★☆ Privacy: ★★★☆☆
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| Basic | `https://common.dot.dns.yandex.net/dns-query` | `common.dot.dns.yandex.net` | `77.88.8.8` / `77.88.8.1` |
| Safe | `https://safe.dot.dns.yandex.net/dns-query` | `safe.dot.dns.yandex.net` | `77.88.8.88` / `77.88.8.2` |
| Family | `https://family.dot.dns.yandex.net/dns-query` | `family.dot.dns.yandex.net` | `77.88.8.7` / `77.88.8.3` |

### Google — Speed: ★★★★★ Privacy: ★★★★★
| Profil | DoH | DoT | Standard |
|--------|-----|-----|----------|
| Standard | `https://dns.google/dns-query` | `dns.google` | `8.8.8.8` / `8.8.4.4` |

### DNS Opérateurs FR (Standard uniquement, isOperatorDns=true)
| Opérateur | Primaire | Secondaire |
|-----------|----------|------------|
| Orange | `80.10.246.2` | `80.10.246.129` |
| Free | `212.27.40.240` | `212.27.40.241` |
| SFR | `109.0.66.10` | `109.0.66.20` |
| Bouygues | `194.158.122.10` | `194.158.122.15` |
| OVH | `213.186.33.99` | `213.251.128.140` |

**Ordre d'affichage par défaut** : ControlD, NextDNS, AdGuard, Surfshark, Mullvad, Cloudflare, Quad9, FDN, dns.sb, Yandex, Google — puis opérateurs en dernier. Réorganisable par drag-and-drop (persisté en SharedPreferences).

---

## Écrans de l'application (12 Activities)

### 1. LanguageSelectionActivity
- Premier écran au premier lancement (si `language` pas encore défini)
- 12 boutons de langue avec drapeaux
- Sauvegarde le choix et lance MainActivity
- Skippé automatiquement si langue déjà choisie

### 2. MainActivity (écran principal)
- **Panneau DNS sélectionné** : icône provider + nom + type protocole (couleur)
- **Bouton Activer/Désactiver** : toggle VPN ou Private DNS
- **Panneau réseau** : infos réseau en temps réel (côté droit)
- **Boutons de test** :
  - Test rapide DNS (domaine + leak)
  - Test de noms de domaines (DomainTesterActivity)
  - DNS Leak Test
  - DNS Speedtest
  - Internet Speedtest
- **Boutons rapport** :
  - Générer rapport
  - Partager rapport chiffré (via EncryptedSharer)
  - Exporter/Importer config
- **Navigation** : Paramètres, Sélection DNS, À propos, Langue

### 3. DnsSelectionActivity (choix DNS)
- **RecyclerView** de providers groupés avec icônes
- **Clic simple** sur nom = sélection rapide du meilleur profil (DoH>DoQ>DoT>Standard)
- **Clic sur badge type** = ouvre DnsProviderDetailActivity
- **Drag-and-drop** pour réorganiser les providers (ItemTouchHelper)
- **Filtrage dynamique** selon les toggles :
  - `operator_dns_enabled` : masquer/afficher DNS opérateurs
  - `adb_dot_enabled` : masquer/afficher profils DoT
  - `show_standard_dns` : masquer/afficher DNS standard non chiffré
  - `show_profile_variants` : 1 profil/type/provider ou tous
  - `show_doq_dns` : masquer/afficher profils DoQ
- **Dédoublonnage** : sans variants, garde prioritairement Unfiltered/Standard/Basic
- **Provider par défaut** marqué avec ★
- **Boutons** : Ajouter profil personnalisé, DNS Speedtest, Retour

### 4. DnsProviderDetailActivity (détail provider)
- **Catégories extensibles** par type (DoH/DoQ/DoT/Standard)
- DoH étendu par défaut, autres repliés
- **Ratings** : étoiles vitesse + vie privée
- **Clic** sur profil = sélectionner et retour
- **Appui long** sur profil custom = éditer/supprimer
- **NextDNS** : bouton "Ajouter profil" avec saisie d'ID + choix protocole

### 5. DnsSpeedtestActivity (speedtest DNS)
- **Bouton Start/Stop** en haut
- **Panneau gauche** : résultats par provider (extensible)
- **Panneau droit** : classement avec médailles (top 5)
- **Couleurs latence** : <50ms=vert, <100ms=jaune, <200ms=orange, >200ms=rouge
- Teste tous les profils disponibles (dédup par provider|type)
- Protocoles testés : DoH via OkHttp, DoT via TLS, DoQ via kwik, Standard via UDP

### 6. DomainTesterActivity (test de blocage)
- **UI programmatique** (pas de layout XML)
- **Liste de domaines** avec toggle on/off (Switch) + appui long pour éditer/supprimer
- **Clic simple** sur une ligne = toggle on/off
- **Domaine par défaut** : `ygg.re`
- **Bouton Run/Stop** : lance les tests en thread background
- **Résultat par domaine** :
  - DNS FAI (résolu via socket protégé) : IP + icône ✅/❌
  - DNS Actif (résolu via système/VPN) : IP + icône ✅/❌
  - Statut : Débloqué / Non bloqué / Bloqué malgré DNS / etc.
- **Indicateur DNS** : affiche si VPN actif ou Private DNS configuré

### 7. InternetSpeedtestActivity (speedtest internet)
- **UI programmatique** (pas de layout XML)
- **Protocole LibreSpeed** natif (OkHttp, pas de WebView)
- **Sélecteur de serveur** parmi la liste LibreSpeed
- **Tests** : Ping (10 HEAD, médiane + jitter), Download (4 connexions, 10s), Upload (3 connexions, 10s)
- **Métriques affichées** : Ping ms, Jitter ms, Download Mbps, Upload Mbps
- **Barre de progression** pour download et upload
- **Console** : log détaillé avec auto-scroll
- **IP publique** affichée via `/getIpURL`

### 8. SettingsActivity (paramètres)
- **Toggles** :
  - Démarrage automatique au boot
  - Reconnexion automatique au boot
  - DNS opérateurs
  - DNS DoT via ADB (avec "Afficher les" en préfixe)
  - Fonctions avancées
  - DNS Standard (non chiffré)
  - Variantes de profils
  - DNS DoQ
  - Blocage IPv6
- **Section Shizuku** : statut + bouton demander/révoquer permission
- **Section ADB** : statut permission + bouton auto-grant
- **Boutons** : Réinitialiser DNS, Guide ADB (HowToActivity), À propos
- **Split tunneling** : sélecteur d'apps avec catégories (Streaming, VOD FR, Speedtest, Système, Autres)
- **DNS Rewrite** : accès à DnsRewriteActivity

### 9. DnsRewriteActivity (réécriture DNS)
- **RecyclerView** de règles `domaine → domaine`
- **Toggle** activer/désactiver par règle
- **Bouton supprimer** par règle
- **Dialog ajout** avec champs fromDomain + toDomain
- Stocké en SharedPreferences (`dns_rewrite_rules`)

### 10. AboutActivity (à propos)
- Version de l'app
- Bouton vérifier mise à jour (via GitHub API)
- Texte philosophique : "Libre aujourd'hui. Libre demain."
- Code source ouvert, Licence GPL v3
- Crédits : is.gd + tmpfiles.org
- Bouton Support (SupportActivity)

### 11. HowToActivity (guide ADB)
- Instructions pour activer le débogage ADB
- Statut ADB (vert=actif, rouge=inactif)
- Bouton ouvrir les options développeur

### 12. SupportActivity (dons crypto)
- 8 cryptomonnaies supportées avec QR codes :
  - BTC, EVM (ETH/BNB/USDT/USDC), TRON, TON, Solana, LTC, ZEC, XRP
- Adresses wallet + réseaux supportés
- Sélecteur de crypto avec QR affiché

---

## Services

### DnsVpnService
- **Type** : `VpnService` avec `foregroundServiceType="specialUse"`
- **Actions** : START, STOP, RESTART, RELOAD_RULES
- **Interface TUN** : `192.168.50.1/24`, MTU 1500
- **Threads** : tunReaderThread (lecture paquets) + dnsReceiverThread (réponses)
- **Protocoles** : DoH (OkHttp), DoQ (kwik), Standard (UDP DatagramSocket protégé)
- **DNS Rewrite** : appliqué au QNAME avant forwarding
- **IPv6** : adresse `::1/128` + route `::0/0` si blocage activé
- **SNI Mapping** : pour DoH avec IP directe (Quad9, Cloudflare, Google, etc.)
- **Notification** : NOTIF_ID 1001, canal permanent
- **`startForeground()`** : DOIT être appelé IMMÉDIATEMENT dans `onStartCommand()`

### BootReceiver
- **Événements** : BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED
- **Logique** : si `auto_start_enabled` + méthode VPN → délai 8s → relance VPN
- **JAMAIS** de `startActivity()` depuis BroadcastReceiver (Android 10+)

---

## Système de partage chiffré

### Export de rapport / config
1. Génère le contenu (rapport DNS ou config JSON)
2. Chiffre en **AES-256-GCM** (clé aléatoire, IV 12 bytes)
3. Upload le fichier chiffré en Base64 sur **tmpfiles.org**
4. Raccourcit l'URL via **is.gd**
5. Génère un lien decrypt : `https://appstorefr.github.io/PerfectDNSManager/decrypt.html#<fileUrl>|<keyBase64>`
6. Affiche le code court + liens cliquables dans un dialog

### Import de config
- Saisie du code court is.gd
- Résolution → téléchargement → déchiffrement AES-256-GCM
- Import sélectif : profils, règles rewrite, NextDNS, paramètres

---

## Système de mise à jour

- Vérifie via GitHub API (`/repos/appstorefr/PerfectDNSManager/releases/latest`)
- Cherche `latest.apk` ou premier `.apk` dans les assets
- Affiche taille du fichier (Mo) à côté du numéro de version
- Téléchargement via Fuel → installation via FileProvider + ACTION_INSTALL_PACKAGE
- Mémorise la version refusée (`dismissed_version`)

---

## SharedPreferences utilisées

| Nom | Clés principales |
|-----|-----------------|
| `prefs` | `language`, `selected_profile_json`, `default_profile_json`, `vpn_active`, `auto_start_enabled`, `auto_reconnect_dns`, `last_method`, `operator_dns_enabled`, `adb_dot_enabled`, `advanced_features_enabled`, `show_standard_dns`, `show_profile_variants`, `show_doq_dns`, `disable_ipv6`, `provider_order_json`, `test_domains_json`, `excluded_apps_json`, `adb_permission_granted` |
| `dns_profiles_v2` | `profiles` (JSON array de DnsProfile) |
| `custom_dns_profiles` | `profiles` (JSON array, custom uniquement) |
| `dns_rewrite_rules` | `rules` (JSON array de DnsRewriteRule) |
| `nextdns_profiles` | `profile_ids` (StringSet) |
| `update_prefs` | `dismissed_version` |
| `adb_prefs` | `adb_permission_self_granted`, `last_adb_port` |
| `blocking_authorities` | `authorities_json`, `authorities_version` |

---

## URLs et services externes

| Service | URL | Usage |
|---------|-----|-------|
| GitHub API | `https://api.github.com/repos/appstorefr/PerfectDNSManager/releases/latest` | Mise à jour |
| GitHub Pages | `https://appstorefr.github.io/PerfectDNSManager/` | Site web, decrypt, authorities |
| tmpfiles.org | `https://tmpfiles.org/api/v1/upload` | Upload rapport/config chiffré |
| is.gd | `https://is.gd/` | Raccourcisseur URL |
| LibreSpeed | `https://librespeed.org/backend-servers/servers.php` | Liste serveurs speedtest |
| Cloudflare Speed | `https://speed.cloudflare.com/` | Speedtest rapide (fallback) |
| tele2 Speedtest | `http://speedtest.tele2.net/1MB.zip` | Download fallback |
| Akamai whoami | `whoami.akamai.net` | DNS leak detection |
| OpenDNS myip | `myip.opendns.com` (208.67.222.222) | DNS leak detection |
| Blocking Authorities | `.../blocking-authorities.json` | IPs de blocage connues |

---

## IPs de blocage connues

| IP | Signification |
|----|--------------|
| `127.0.0.1` | Blocage générique (loopback) |
| `0.0.0.0` | Blocage générique (null route) |
| `::1` | Blocage IPv6 (loopback) |
| `::0` | Blocage IPv6 (null) |
| `90.85.16.52` | Orange France (page de blocage) |
| `194.6.135.126` | Autorité française (blocage judiciaire) |
| `54.246.190.12` | Blocage AWS (sinkhole) |

---

## Icônes des providers (drawables)

| Provider | Fichier | Format |
|----------|---------|--------|
| AdGuard | `ic_adguard.png` | PNG |
| Cloudflare | `ic_cloudflare.png` | PNG |
| ControlD | `ic_controld.png` | PNG |
| dns.sb | `ic_dnssb.png` | PNG |
| FDN | `ic_fdn.xml` | Vector XML |
| Google | `ic_google.png` | PNG |
| Mullvad | `ic_mullvad.png` | PNG |
| NextDNS | `ic_nextdns.png` | PNG |
| Quad9 | `ic_quad9.png` | PNG |
| Surfshark | `ic_surfshark.png` | PNG |
| Yandex | `ic_yandex.png` | PNG |
| Orange | `ic_orange.png` | PNG |
| Free | `ic_free.png` | PNG |
| SFR | `ic_sfr.png` | PNG |
| Bouygues | `ic_bouygues.png` | PNG |
| OVH | `ic_ovh.xml` | Vector XML |
| Custom | `ic_dns_custom.xml` | Vector XML |

---

## Problèmes connus et contraintes

1. **DoQ routing loop** : kwik crée son propre DatagramSocket → doit être protégé via `VpnService.protect()`
2. **VPN→DoT transition** : `dnsSelectionLauncher` doit vérifier `methodForProfile()` pour stopper le VPN
3. **IPv6 blocking** : nécessite `allowFamily(AF_INET6)` ET `addRoute("::", 0)` + adresse IPv6 sur TUN
4. **`allowFamily(AF_INET)` seul** : cause un bypass IPv6 du VPN
5. **`startForeground()`** : DOIT être appelé dans `onStartCommand()` AVANT toute logique
6. **`focusableInTouchMode="true"`** : cause un double-tap sur mobile → utiliser `focusable="true"` seul
7. **BootReceiver** : JAMAIS de `startActivity()` depuis BroadcastReceiver (Android 10+)
8. **Drag-and-drop Android TV** : ItemTouchHelper fonctionne via MotionEvent (tactile) — pas viable sur D-pad

---

## Build

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew clean assembleDebug
```

APK généré : `app/build/outputs/apk/debug/PerfectDNSManager-v{version}-debug.apk`
