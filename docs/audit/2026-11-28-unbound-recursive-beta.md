# Unbound autonome — 2.4.14-beta.1

Branche `codex/pdm-unbound-beta`, publication bêta uniquement. Stable 2.4.11
conservée. Aucun changement du Worker ou de la branche main.

## Fonctionnement et garde-fous

- Profil optionnel Unbound → Autonome (`unbound://recursive`), caché par le
  réglage existant des profils Unbound. Les profils avec fournisseur DoT restent
  disponibles. Démarrage uniquement à l'activation du profil.
- Mode autonome : hints racine embarqués dans libunbound, aucun forwarder,
  aucune lecture de resolv.conf, UDP/TCP 53, cache, DNSSEC, minimisation QNAME.
- DoT : nom TLS authentifié, CA embarquées, aucun repli implicite en récursion.
- Marqueur autonome strict ; variantes et mélange autonome/secondaire refusés
  dans les profils et à l'entrée du service. Contrôle natif indépendant.
- Préfiltre commun en amont ; IPv6 autorisé par défaut, préférence de blocage
  transmise à Unbound sans supprimer les réponses AAAA.
- Quatre ABI/API 21 conservées ; ressources bornées, plage virtuelle du VPN
  exclue des destinations Unbound pour éviter une boucle.

## Vérifications locales

- Construction debug, release optimisée et instrumentation ; lint et 54 tests
  JVM réussis. Traductions : 517 ressources dans les 12 langues.
- Quatre bibliothèques natives reconstruites ; contrôle ELF/APK réussi, toutes
  les bibliothèques 64 bits alignées 16 Ko.
- API 21 x86 : quatre tests natifs passent (DoT authentifié A/AAAA, DNSSEC,
  domaine invalide, mauvais nom TLS, fermeture concurrente, modes natifs).
- API 36 x86_64/16 Ko : hiérarchie DNS contrôlée, racine séparée du serveur
  autoritaire, délégation, repli UDP→TCP et réponses A/AAAA vérifiés. Les règles
  temporaires DNAT ne concernent que l'UID de l'application de test dans l'AVD
  jetable ; elles sont supprimées après le test.
- APK optimisé sur API 21/4 Ko et API 36/16 Ko : VPN Unbound avec DoT Cloudflare, A/AAAA,
  DNSSEC signé et invalide, préfiltre et routes IPv6 OFF/ON/OFF réussis. HTTPS vérifié sur API 36 ;
  sonde HTTPS système omise sur API 21 (magasin de CA ancien).
- API 21 et API 36 : sur ce réseau intercepté, le VPN autonome renvoie bien
  SERVFAIL ; le préfiltre local renvoie NXDOMAIN en A et AAAA comme attendu.
- Cette hiérarchie est volontairement non signée et utilise un fichier d'ancres
  vide uniquement dans l'instrumentation. Le backend de production charge
  toujours les ancres IANA embarquées. Les validations DNSSEC sont séparées.

## Limite du réseau de test et résultats négatifs

UDP/TCP 53 sont interceptés par le réseau hôte : même une requête adressée à
192.0.2.254 reçoit une réponse publique. Les captures dans l'AVD montrent que
les requêtes de priming vers les racines reçoivent des réponses non autoritaires
sans les informations attendues. La récursion publique à travers le VPN échoue
sur ce réseau et renvoie SERVFAIL ; ce n'est pas un test Internet réussi.
Le succès initial du test natif isolé n'est pas une preuve d'accès direct aux
racines. La hiérarchie contrôlée vérifie séparément le véritable suivi des
délégations, sans dépendre de l'interception.

Le harnais attend maintenant l'existence du VPN avant ses sondes : une réponse
port 53 obtenue avant son établissement ne prouve pas le fonctionnement du VPN.
Les tests avec filtrage local vérifient aussi le passage effectif par PDM.

Les anciennes box ARM et les réseaux IPv6 de bout en bout ne sont pas reproduits
physiquement ici. Le mode autonome nécessite un réseau autorisant réellement
l'accès aux serveurs DNS publics ; sinon choisir explicitement un fournisseur
DoT. Aucun abaissement de DNSSEC ni désactivation IPv6 par défaut n'est ajouté
pour contourner les particularités de l'environnement de test.

## Publication vérifiée

- Release `v2.4.14-beta.1` et pointeur `latest-beta` publiés en prerelease ;
  workflow 46087797902 réussi. Stable `v2.4.11` et digest de son APK inchangés.
- APK téléchargés indépendamment : fichiers numéroté et rolling identiques,
  20 484 713 octets, SHA256
  `777f203007d3879d2731d09b981cf1b0d56ac900d7b1342569bc36e01fca2bfa`.
- Certificat de production inchangé, minSdk 21, versionCode 20414, quatre ABI
  et alignement 16 Ko vérifiés sur l'APK publié.
- Sur l'AVD API 21, installation de la bêta de production 2.4.13, puis mise à
  jour directe vers 2.4.14 sans désinstallation : succès et lancement réussi.
  Option Unbound conservée, blocage IPv6 toujours désactivé. Le profil autonome
  et sa description apparaissent après ouverture du groupe Unbound ; le marqueur
  technique n'est pas affiché comme une adresse DNS.

Les fichiers JSON voisins consignent les résultats et limites. Les émulateurs
jetables utilisés pour cette vérification sont arrêtés après les tests.
