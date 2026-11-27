# Revue des quatre issues ouvertes — préparation 2.4.13-beta.1

Lecture directe de l’API GitHub le 27 novembre 2026. Les quatre tickets n’ont
aucun commentaire et fournissent peu d’informations de reproduction. Aucun
commentaire envoyé, aucune issue fermée. Stable 2.4.11 non modifiée.

## #1 — Reading & writing in the clipboard

https://github.com/PerfectDNSManager/PerfectDNSManager/issues/1

Le code ne contient aucun `getPrimaryClip`/`hasPrimaryClip`. Les deux appels
`ShareClipboard.copy` sont dans les boutons Copier des dialogues de partage
(MainActivity et SettingsActivity). Ils écrivent sur action explicite, marquent
le contenu sensible sur API 24+, et ne relisent pas le contenu. Aucun
READ_CLIPBOARD/WRITE_CLIPBOARD déclaré dans le manifeste source ou fusionné.
Android peut présenter l’opération système de presse-papiers même sans permission
à demander. Pas de suppression aveugle du bouton de copie ni de prétendue
permission retirée. Aucun comportement de lecture automatique identifié dans
le code PDM ; le signalement d’origine ne précise pas la version examinée.

## #2 — Coupure Internet sur Formuler Z7+ / Zx

https://github.com/PerfectDNSManager/PerfectDNSManager/issues/2

La cause matérielle n’est pas reproduite : appareils non disponibles. Une cause
logicielle plausible et vérifiable a néanmoins été éliminée : le bootstrap
DoH n’utilisait que Cloudflare/dns.sb en UDP public, et une seule adresse IPv4.
Un réseau bloquant les DNS UDP externes pouvait donc empêcher le démarrage du
DoH malgré un fournisseur HTTPS joignable.

Le fournisseur est désormais résolu via `Network.getAllByName` sur les réseaux
physiques INTERNET, jamais via le réseau VPN. Les réseaux validés sont prioritaires
sur API 23+. Toutes les adresses A/AAAA sont conservées pour les tentatives OkHttp.
Les sockets restent protégés avant connect ; TLS/SNI et chaîne restent vérifiés.
Seul le nom du fournisseur utilise ce bootstrap, pas les requêtes applicatives.
Les deux anciens bootstraps protégés restent un dernier recours si les DNS des
réseaux physiques échouent. Pas de résolution récursive via le DNS du VPN.

Limites : pas de correctif firmware Formuler revendiqué, pas de nouveau magasin
CA DoH pour anciens Android ; le moteur Unbound dispose déjà de ses propres CA.
Le transport DoQ n’est pas modifié par ce correctif DoH.

Le premier test OFF/ON/OFF sur Android 5 avec Unbound a échoué au redémarrage,
avant la création de la nouvelle interface. Unbound utilisait encore la résolution
globale InetAddress, susceptible de viser l’ancien VPN pendant sa suppression
asynchrone. Son bootstrap utilise maintenant le même sélecteur de réseau physique
que DoH ; aucun fallback vers le résolveur global/VPN. Les erreurs de démarrage
journalisent leur classe sans exposer le nom du fournisseur personnel.

## #3 — IPv6 bloqué pendant les tests

https://github.com/PerfectDNSManager/PerfectDNSManager/issues/3

Vérifié : préférence `disable_ipv6=false` par défaut, `allowFamily(AF_INET6)`
quand le blocage est désactivé, route `::/0` seulement quand il est activé.
Pas de filtrage global des réponses AAAA par le proxy. L’état historique de
migration est couvert par les tests Ipv6Settings existants.

Deux défauts supplémentaires corrigés :
- La préférence IPv6 est observée par le service, y compris lors d’un import.
  Le VPN redémarre avec son endpoint vivant plutôt qu’un profil sauvegardé qui
  pourrait différer. Les commandes plus récentes prennent priorité. Le réglage
  UI n’émet plus un second redémarrage en parallèle.
- Les mesures Whoami ouvrent une connexion neuve (zéro connexion inactive
  conservée), pour ne pas réutiliser un socket issu de l’état précédent du VPN.

Test instrumenté ajouté : OFF → ON → OFF par écriture directe de la préférence,
contrôle de la route IPv6 du VPN et réponses DNS A/AAAA à chaque étape. Cela ne
constitue pas un test de connectivité Internet IPv6 native, absente de l’hôte.

## #4 — Davantage de résolveurs pour les tests

https://github.com/PerfectDNSManager/PerfectDNSManager/issues/4

Le benchmark charge désormais les profils du ProfileManager, donc les profils
personnels/importés. La déduplication porte sur protocole + endpoint, plus sur
fournisseur + protocole : les variantes distinctes ne disparaissent plus. Les
restrictions de profils filtrants/opérateurs/DoT restent appliquées et Unbound
reste exclu des tests automatiques. Le nom du profil est visible dans le résultat.

Outil explicite `tools/resolver-catalog/convert.py` pour les deux catalogues
mentionnés : lecture HTTPS bornée, sélection par nom, conservation des profils
exportés, import JSON sans changement de paramètres/selection. DoH uniquement.
Les entrées avec pins non représentables sont rejetées ; aucun assouplissement
TLS. `relays.md` ne contient pas des DoH directement utilisables et n’est donc
pas injecté comme liste de fournisseurs. Détails et relevé réel dans son README.
Une interface d’abonnement automatique aux catalogues n’est pas implémentée.

## Validation

- `testDebugUnitTest`, `lintDebug`, `assembleRelease` : succès ; 53 tests JVM,
  aucun échec. 515 ressources × 12 langues validées.
- 3 tests Python du convertisseur, lecture des trois catalogues réels et essai
  de fusion d’un export : succès, profil existant conservé, aucun paramètre importé.
- APK optimisé (copie signée debug, jamais distribuée) sur AVD Android 11/API 30 :
  Argon2, ADB natif, import/export Gson, écrans, DNS DoH A/AAAA, HTTPS et routes
  IPv6 OFF/ON/OFF : `ALL PASSED`.
- Même contrôle réseau avec règles iptables sur cet AVD bloquant UDP/53 vers
  1.1.1.1 et 185.222.222.222 : `ALL PASSED`. Règles retirées ensuite.
- Quatre ABI conservées ; alignement natif et APK 16 Ko validés.
- Après correction du bootstrap Unbound, APK optimisé 2.4.13-beta.1 sur Android
  5/API 21 x86 : Argon2, import/export, écrans, Unbound DoT A/AAAA et bascule des
  routes IPv6 OFF/ON/OFF : `ALL PASSED`. La sonde HTTPS système est omise sur
  cette image ancienne (CA périmées), jamais la vérification TLS d’Unbound.
- Le test attend la disponibilité DNS après chaque recréation de VPN (comme au
  démarrage initial), car l’apparition de la route précède la disponibilité du
  transport. Un timeout isolé de 3 secondes ne suffit pas pour juger un moteur
  dont le délai de requête natif est 8 secondes. Après bootstrap corrigé, attente
  bornée / nouvelles tentatives : succès ; aucune erreur de démarrage persistante.
- Recompilation finale et 53 tests / lint : succès. Publication bêta lancée après
  ces contrôles ; la signature et les pointeurs sont contrôlés après la CI.


## Publication vérifiée

- Tag `v2.4.13-beta.1`, code 20413, source `89cf8e342b9c094632a88db397183d24cde710c7`.
- CI réussie : https://github.com/PerfectDNSManager/PerfectDNSManager/actions/runs/45974550236
- Release : https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/v2.4.13-beta.1
- APK numéroté et alias `latest-beta` retéléchargés : identiques, 20 475 876 octets,
  SHA-256 `999ec4e950757f6ce97bc5442b40a58094ddeb56cd7848cb56f67fa31aaf356d`,
  conforme aux empreintes des assets GitHub.
- Signature de production inchangée :
  `c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`.
- Vérification indépendante de l’alignement 16 Ko, des quatre ABI et minSdk 21.
- Installation réelle de l’APK publié par-dessus la bêta 2.4.12-beta.1 signée
  production sur l’AVD API 30 : `install -r` réussi sans désinstallation entre
  ces deux versions ; lancement à froid réussi (1462 ms).
- Le dernier APK local optimisé a également repassé DoH A/AAAA/HTTPS et les
  routes IPv6 OFF/ON/OFF avec les bootstraps publics UDP bloqués, après extraction
  du helper commun utilisé par Unbound.
- Stable inchangée : release `v2.4.11`, ID 436358160, SHA-256 de l’APK stable
  `ef6086e0b950e4b168d6911f855923b0ed9440f87e7ec4af035ec1d1917be361`.
- Aucune modification de main, aucune publication stable, aucun déploiement Worker,
  aucun commentaire ou changement d’état des issues.

La CI conserve les avertissements connus de dépréciation des actions Node 20
(forcées sous Node 24) et setup-java v4 ; ils n’ont pas empêché les contrôles.
Les essais physiques Formuler / accès Internet IPv6 natif restent nécessaires.
