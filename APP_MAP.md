# PerfectDNSManager — Carte UI v1.0.76

## PAGE PRINCIPALE (MainActivity)

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [1] 🌐 Langue        "DNS Switcher"           [2] ⚙ Param  ║
║                                                              ║
║  Fournisseur DNS :                                           ║
║  [3] [icône DNS] Cloudflare - Unfiltered (DoH / VPN)        ║
║                                                              ║
║  Activation :                                                ║
║  [4] ████████ ACTIVER / DÉSACTIVER ████████                  ║
║                                                              ║
║  Outils de test :                                            ║
║  [5] Test DNS rapide        [6] Générer rapport              ║
║  [7] Testeur débit avancé   [8] Partager rapport             ║
║                                                              ║
║  [9] Status DNS/IP (scroll) │ [10] Rapport (scroll)         ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 1 | btnLanguage | Button | Ouvre sélection langue |
| 2 | btnSettings | ImageButton | Ouvre Paramètres |
| 3 | layoutSelectDns + ivSelectedDnsIcon + tvSelectDns | LinearLayout | Ouvre Sélection DNS |
| 4 | btnToggle | Button | Active/Désactive VPN ou ADB |
| 5 | btnDomainTester | Button | Test DNS rapide + leak test |
| 6 | btnGenerateReport | Button | Génère rapport DNS |
| 7 | btnSpeedtest | Button | Ouvre Testeur débit avancé |
| 8 | btnShareReport | Button | Partage rapport chiffré |
| 9 | scrollStatus / tvStatusInfo | ScrollView | Affiche DNS actif, IP, etc. (gauche) |
| 10 | scrollReport / tvReportContent | ScrollView | Affiche le rapport (droite) |

---

## SÉLECTION DNS (DnsSelectionActivity)
*Ouvert en cliquant sur [3]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [11] ← Retour     "Choisir un serveur DNS"                 ║
║                     [12] + Ajouter   [13] DNS Speedtest      ║
║                                                              ║
║  ┌─ Ligne fournisseur (item_profile) ───────────────────┐   ║
║  │                                                       │   ║
║  │  [14] Cloudflare          [15] 4 profils · DoH · DoT │   ║
║  │                                                       │   ║
║  │  [16] Vitesse: ★★★★★  Vie privée: ★★★★★             │   ║
║  │                                                       │   ║
║  └───────────────────────────────────────────────────────┘   ║
║                                                              ║
║  (glisser-déposer = réorganiser les fournisseurs)            ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 11 | btnBack | Button | Retour page principale |
| 12 | btnAddProfile | Button | Ajouter profil DNS custom (gauche) |
| 13 | btnSpeedtest | Button | Ouvre DNS Speedtest (droite) |
| 14 | tvName | TextView (focusable) | Clic = connexion 1-click (meilleur profil DoH>DoQ>DoT>Standard) |
| 15 | tvType | TextView (focusable) | Clic = ouvre page détails fournisseur |
| 16 | layoutRatings (tvSpeedLabel/Stars + tvPrivacyLabel/Stars) | LinearLayout | Étoiles vitesse/privacy (non cliquable, masqué si pas de rating) |

---

## DÉTAILS FOURNISSEUR (DnsProviderDetailActivity)
*Ouvert en cliquant sur [15]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [17] ← Retour    [18] [icône] Cloudflare                   ║
║                                                              ║
║  [19] Vitesse: ★★★★★  Vie privée: ★★★★★                    ║
║                                                              ║
║  [20] + Ajouter profil NextDNS (NextDNS uniquement)         ║
║                                                              ║
║  [21] Liste des profils par type (DoH, DoT, DoQ, Standard)  ║
║       ┌──────────────────────────────────────────────┐       ║
║       │ ▼ DoH                                        │       ║
║       │   Unfiltered — https://dns.cloudflare.com    │       ║
║       │   Malware — https://security.cloudflare...   │       ║
║       │ ▼ DoT                                        │       ║
║       │   Unfiltered — one.one.one.one               │       ║
║       └──────────────────────────────────────────────┘       ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 17 | btnBack | Button | Retour sélection DNS |
| 18 | ivProviderIcon + tvProviderName | ImageView + TextView | Icône + nom du fournisseur |
| 19 | layoutRatings | LinearLayout | Étoiles vitesse/privacy |
| 20 | btnAddProfile | Button | Ajouter profil NextDNS custom (visible uniquement pour NextDNS) |
| 21 | rvProfiles | RecyclerView | Liste profils groupés par type, clic = sélection du profil |

---

## DNS SPEEDTEST (DnsSpeedtestActivity)
*Ouvert via [13]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [22] ← Retour     "DNS Speedtest"                          ║
║                                                              ║
║  [23] Démarrer/Stop  │  [24] Progression test en cours       ║
║                                                              ║
║  [25] Fournisseurs (scroll)  │  [26] Classement (scroll)    ║
║  ┌────────────────────┐      │  ┌────────────────────┐      ║
║  │ ▶ Cloudflare (2/4) │      │  │ 🥇 Cloudflare DoH │      ║
║  │ ▶ Google     (3/3) │      │  │ 🥈 Quad9 DoH      │      ║
║  │ ▶ Quad9      (1/2) │      │  │ 🥉 Google DoH     │      ║
║  └────────────────────┘      │  └────────────────────┘      ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 22 | btnBack | Button | Retour |
| 23 | btnStartStop | Button | Démarre/Arrête le test DNS |
| 24 | tvCurrentTest | TextView | Progression en temps réel |
| 25 | scrollProviders / layoutProviders | ScrollView | Fournisseurs testés (expandable par clic) |
| 26 | scrollRanking / tvRanking | ScrollView | Classement final (podium + liste complète) |

---

## TESTEUR DÉBIT AVANCÉ (InternetSpeedtestActivity)
*Ouvert via [7]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [27] ← Retour     "Testeur de débit avancé"                ║
║                                                              ║
║  [28] Serveur : Amsterdam (NL)                               ║
║                                                              ║
║  [29] ████████ Démarrer le test ████████                     ║
║                                                              ║
║  ┌───────────────────────────────────────┐                   ║
║  │  PING: -- ms        JITTER: -- ms    │                   ║
║  │  ⬇ DOWNLOAD: -- Mbps  [████░░░░░░]  │                   ║
║  │  ⬆ UPLOAD:   -- Mbps  [████░░░░░░]  │                   ║
║  │  IP : --                              │                   ║
║  └───────────────────────────────────────┘                   ║
║                                                              ║
║  [30] Journal du test (scroll console)                       ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 27 | btnBack | Button | Retour |
| 28 | btnServerPicker | Button | Ouvre dialogue choix serveur LibreSpeed |
| 29 | btnStartStop | Button | Démarre/Arrête le test débit |
| 30 | scrollConsole / tvConsole | ScrollView | Log en temps réel du test |

---

## TEST DE NOMS DE DOMAINES (DomainTesterActivity)
*Ouvert via [5]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [31] ← Retour     "Test de noms de domaines"               ║
║                                                              ║
║  [32] Résultats (scroll) — tableau domaine / sans VPN /     ║
║       avec VPN / status                                      ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 31 | btnBack | Button | Retour |
| 32 | - | ScrollView | Résultats des tests de résolution DNS |

---

## PARAMÈTRES (SettingsActivity)
*Ouvert via [2]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [33] ← Retour                                              ║
║                                                              ║
║  ⚙ Fonctions avancées                                       ║
║  [34] Toggle ON/OFF  (focus initial ici)                     ║
║  (si activé :)                                               ║
║    [35] 📋 Afficher les différents profils     [switch]      ║
║    [36] 🧭 DNS over QUIC                      [switch]      ║
║    [37] 📡 DNS DoT via ADB                    [switch]      ║
║       (si DoT activé : section ADB/Shizuku)                 ║
║    [38] 📂 Afficher les DNS classiques         [switch]      ║
║    [39] 🇫🇷 Afficher les DNS opérateurs        [switch]      ║
║    [40] ⚠ URL Rewrite warning text                          ║
║    [41] 🔀 URL Rewrite (bouton)                             ║
║    [42] Split tunneling (bouton)                             ║
║                                                              ║
║  Import / Export                                             ║
║  [43] Toggle ON/OFF (collapsible)                            ║
║    [44] Exporter config                                      ║
║    [45] Importer config                                      ║
║    [46] 🔄 Restaurer DNS initiaux                           ║
║    [47] ⚠ Réinitialiser l'application                       ║
║                                                              ║
║  [48] ❤ Nous soutenir (bouton)                              ║
║  [49] ℹ À propos (bouton)                                   ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 33 | btnBack | Button | Retour |
| 34 | switchAdvanced | Switch | Active/désactive fonctions avancées (focus initial) |
| 35 | rowProfileVariants / switchProfileVariants | LinearLayout | Afficher variantes de profils |
| 36 | rowDoqDns / switchDoqDns | LinearLayout | Afficher profils DNS over QUIC |
| 37 | rowAdbDot / switchAdbDot | LinearLayout | Activer DNS DoT via ADB (+ section Shizuku) |
| 38 | rowStandardDns / switchStandardDns | LinearLayout | Afficher DNS classiques (non chiffré) |
| 39 | rowOperatorDns / switchOperatorDns | LinearLayout | Afficher DNS opérateurs FR |
| 40 | tvUrlRewriteWarning | TextView | Warning URL Rewrite (DoH, DoQ, Standard via VPN uniquement) |
| 41 | btnUrlRewrite | Button | Ouvre dialogue URL Rewrite |
| 42 | btnSplitTunnel | Button | Ouvre dialogue Split Tunneling |
| 43 | rowImportExport | LinearLayout | Toggle section Import/Export |
| 44 | btnExportConfig | Button | Exporter config chiffrée |
| 45 | btnImportConfig | Button | Importer config chiffrée |
| 46 | btnRestoreDns | Button | Restaurer DNS par défaut |
| 47 | btnResetApp | Button | Réinitialiser l'application |
| 48 | btnSupport | Button | Ouvre page Nous soutenir |
| 49 | btnAbout | Button | Ouvre page À propos |

---

## À PROPOS (AboutActivity)
*Ouvert via [49]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [50] ← Retour                                              ║
║                                                              ║
║  "PerfectDNSManager"                                         ║
║  [51] Texte communautaire                                    ║
║  [52] ❤ Nous soutenir                                       ║
║  [53] Version 1.0.76                                         ║
║  [54] Vérifier les mises à jour                              ║
║  [55] Licence GPL v3                                         ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 50 | btnBack | Button | Retour |
| 51 | - | TextView | Texte communautaire |
| 52 | btnSupportAbout | Button | Ouvre page Soutenir |
| 53 | tvVersion | TextView | Version actuelle |
| 54 | btnCheckForUpdate | Button | Vérifie MAJ sur GitHub |
| 55 | - | TextView | Texte licence GPL v3 + lien GitHub |

---

## NOUS SOUTENIR (SupportActivity)
*Ouvert via [48] ou [52]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [56] ← Retour                                              ║
║                                                              ║
║  "Nous soutenir"                                             ║
║  Message de remerciement                                     ║
║                                                              ║
║  [57] Choisir une crypto (bouton → dialogue)                ║
║                                                              ║
║  [58] Nom de la crypto sélectionnée                         ║
║  [59] QR Code                                                ║
║  [60] Adresse crypto                                         ║
║  [61] Réseaux supportés                                      ║
║                                                              ║
║  Cryptos: BTC, EVM, TRON, TON, Solana, LTC, Zcash, XRP      ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

| # | ID | Type | Action |
|---|-----|------|--------|
| 56 | btnBack | Button | Retour |
| 57 | btnSelectCrypto | Button | Ouvre sélecteur de crypto (AlertDialog) |
| 58 | tvCryptoName | TextView | Nom de la crypto choisie |
| 59 | ivQrCode | ImageView | QR code de l'adresse |
| 60 | tvAddress | TextView | Adresse crypto (sélectionnable) |
| 61 | tvNetworks | TextView | Réseaux supportés (Base, Polygon, etc.) |

---

## SÉLECTION LANGUE (LanguageSelectionActivity)
*Ouvert via [1]*

```
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║  [62] ← Retour                                              ║
║                                                              ║
║  Liste des langues disponibles (12 langues)                  ║
║  FR / EN / ES / DE / IT / PT / NL / AR / RU / ZH / JA / KO ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

---

## NAVIGATION GLOBALE

```
[1] Langue ──────────────────────────► Sélection Langue
[2] Paramètres ──────────────────────► Paramètres
    ├─ [48] ─────────────────────────► Nous Soutenir
    └─ [49] ─────────────────────────► À Propos
        └─ [52] ─────────────────────► Nous Soutenir
[3] Sélection DNS ───────────────────► Sélection DNS
    ├─ [14] Nom fournisseur ─────────► 1-click connexion (retour MainActivity)
    ├─ [15] Badge type ──────────────► Détails Fournisseur
    │   └─ [21] Profil ─────────────► Sélection profil (retour MainActivity)
    └─ [13] DNS Speedtest ───────────► DNS Speedtest
[5] Test DNS rapide ─────────────────► DomainTester (inline)
[7] Testeur débit avancé ────────────► InternetSpeedtest
```
