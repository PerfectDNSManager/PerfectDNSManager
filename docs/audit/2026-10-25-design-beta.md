# Revue PDM et bêta graphique — 25 octobre 2026

État : travail local uniquement. Aucun push, tag, release, déploiement ou commentaire GitHub. Base Android : `6b6a12b` (stable 2.4.0). Base site/worker : `b350488`. Branches locales `codex/pdm-design-beta` et `codex/pdm-site-design-beta`.

## Périmètre et méthode

Revue des chemins sensibles Android (manifest, composants exportés, transports DNS, TLS, IPv6, ADB, import de configuration, partage, presse-papiers, mises à jour), backend de partage et téléchargements, ressources du site, traduction et navigation du catalogue. Lecture des précédents rapports pour vérifier les garde-fous déjà introduits. Contrôles automatisés sur le projet, tests JVM et instrumentés, scan des dépendances Maven résolues via OSV, `npm audit`, inspection navigateur mobile/bureau et lecture des issues GitHub. Ce rapport distingue les problèmes confirmés, le durcissement et les limites ; ce n’est pas une preuve mathématique d’absence de faille ni une relecture exhaustive ligne par ligne des bibliothèques tierces.

## Corrections

| Sujet | Constat et modification |
|---|---|
| Dépendances Android | OSV signale deux avis sur `bcprov-jdk18on:1.84` : GHSA-9pwp-9qqc-pr26 et GHSA-qp49-qgx5-5m26. Alignement bcpkix/bcprov/bcutil sur 1.86. La présence d’un paquet concerné ne prouve pas l’exploitabilité de ces fonctions dans PDM. |
| Outillage du site | Undici transitif présent dans Wrangler/Miniflare : avis de déni de service et contournement SRI. Override compatible 7.30.1 minimum, verrouillage dans package-lock. Cela concerne les outils Node locaux, pas le moteur `fetch` de Cloudflare Workers. |
| Allocations réseau Android | Deux lectures `.string()` non bornées, sur whoami et la réponse d’upload. Plafond 16 Kio avant décodage, via le lecteur borné existant. Limite de contenu avant chiffrement et dérivation Argon2. |
| Rafraîchissements IP | Deux clients OkHttp recréés par rafraîchissement. Réutilisation de deux clients et pools distincts IPv4/IPv6 ; un pool partagé aurait faussé la distinction des familles. Réemploi du dispatcher partagé et délai global existant. Endpoint conforme au canal de la build. |
| Partage | Le parseur Android refusait les liens `/decrypt.html?s=...` reconnus par le site. Grammaire commune : codes nus, `/d/`, `/r/`, `/decrypt/` et formulaire, HTTPS seulement sur les trois domaines reconnus. Rejet ports, identifiants URL, chemins étrangers et entrées excessives. |
| Journaux | Les chemins DoH étaient masqués mais les identifiants dans les noms DoQ/DoT NextDNS et Control D restaient visibles. Masquage des labels personnels, requêtes et fragments ; suppression du message brut d’exception DoH susceptible de contenir une URL. |
| Téléchargements web | L’échappement HTML ne bloque pas une URL `javascript:`. Validation du schéma, hôte, dépôt et chemin de téléchargement avant affichage d’une URL issue des métadonnées GitHub. Repli sûr si invalide. Durcissement : aucun compromis GitHub constaté. |
| Filtres | Les profils personnels NextDNS pouvaient réintroduire des protocoles masqués. Transmission des types sélectionnés à la fiche fournisseur, y compris les profils personnels. Pas de mutation du catalogue source lors du filtrage. |
| Présentation | Suppression des étoiles statiques de vitesse/confidentialité dans les écrans catalogue Android : elles n’étaient pas des mesures. Palette commune plus lisible, cartes allégées, en-tête du sélecteur sans superposition titre/retour. |

## Bêta graphique

- Android : cases DoH, DoT, DoQ et UDP en haut du catalogue ; compteur et état vide ; conservation des cases à la recréation de l’écran. DoT reste conditionné à son activation préalable dans les paramètres. Les cases filtrent la liste sans modifier la connexion active ni les préférences réseau. L’option Adblock existante reste indépendante.
- APK debug séparé `app.perfectdnsmanager.design`, nommé **PDM Design Beta**, version `2.4.1-beta.design.1-local`. Il cohabite avec la stable et utilise ses propres données. Ce n’est pas une mise à jour signée avec la clé de production.
- Site : nouvel accueil, feuille de style partagée pour les 12 langues, explorateur DNS avec recherche + filtres cumulables + Adblock désactivé initialement. 17 fournisseurs / 144 profils présents ; 64 profils visibles sans Adblock. Métadonnées des profils produites par le générateur, sans duplication manuelle.
- Aucun framework ni police externe ajouté. Recherche web : texte indexé une fois, modification de visibilité uniquement.

## Protections revérifiées

- Blocage IPv6 opt-in : valeur par défaut `false`, migration ancienne préférence et `allowFamily(AF_INET6)` quand le blocage est désactivé. La route `::/0` n’est posée que sur demande de blocage.
- DNS : sockets protégées, validation de la question/réponse, bornes de taille, pool de requêtes borné et fermeture à l’arrêt. Validation du nom et de la chaîne TLS sur les chemins DoT/DoQ ; pas de nouvelle option de contournement TLS.
- Import : taille/profondeur/nombre d’éléments bornés ; validation avant écriture ; pas d’import de resource IDs arbitraires.
- ADB : hôte local, hostname validé avant commande, sorties et délais bornés. Le gestionnaire TLS du pairing local n’est pas celui des DNS publics.
- APK : HTTPS, hôtes de redirection restreints, plafond de téléchargement et contrôle package/signature avant installateur système.
- Manifest : services et écrans internes non exportés, FileProvider limité aux mises à jour, sauvegarde désactivée.
- Presse-papiers : un seul point d’écriture explicite dans ShareClipboard ; aucune lecture automatique trouvée.
- Worker : HMAC lié à l’IP, limiteurs fermés sur panne, lecture bornée même sans Content-Length, allocation R2 conditionnelle, expiration et purge. Déchiffrement côté navigateur avec Argon2 et AES-GCM ; mot de passe absent du stockage serveur.
- Scan de motifs de clés privées/tokens sur 140 fichiers texte suivis Android : aucun motif trouvé. Aucun chemin `.jks` ou archive de keystore retourné par l’historique interrogé. Ce scan ne couvre ni les secrets configurés hors dépôt ni les anciens clones externes.

## Retours GitHub

Trois issues ouvertes, aucune PR ouverte lors de la consultation. Aucune réponse ni clôture effectuée :

- [#1 Presse-papiers](https://github.com/PerfectDNSManager/PerfectDNSManager/issues/1) : lecture automatique absente, copie explicite déjà en place.
- [#2 Formuler Z7+/Zx](https://github.com/PerfectDNSManager/PerfectDNSManager/issues/2) : nécessite reproduction sur ces modèles ; un AVD ne démontre pas leur correction.
- [#3 IPv6](https://github.com/PerfectDNSManager/PerfectDNSManager/issues/3) : protections présentes ; tests détaillés dans le rapport IPv6 du 14 octobre.

L’API GitHub Pages du dépôt Android répond 404 avec le compte courant : aucun site Pages accessible n’a été confirmé. Les anciennes pages `docs/*.html` ne sont pas la source du site Cloudflare actuellement maintenu.

## Limites conservées explicitement

- Le mot de passe de partage à six caractères reste conservé conformément au choix utilisateur historique documenté le 15 septembre. Argon2 ralentit la recherche, mais l’expiration serveur n’empêche pas une attaque hors ligne sur une copie du fichier chiffré.
- HTTP non chiffré autorisé pour les serveurs de mesure de débit dynamiques ; ne pas confondre cette exception avec les transports DNS chiffrés ou l’updater.
- Bibliothèques natives ADB/Argon2/Conscrypt : pas d’audit C/C++ complet ou de preuve de reproductibilité binaire dans cette passe.
- Pas de test sur Firestick/Formuler physique, réseau IPv6 opérateur, coupures Wi-Fi réelles ou tous les niveaux Android. Les tests AVD et JVM ne couvrent pas ces cas.
- Les avertissements lint de traduction, ressources et maintenance doivent être distingués des erreurs et failles. Aucun masquage global des avertissements pour présenter artificiellement zéro problème.

## Sources des avis

- [Bouncy Castle : contraintes de nom](https://github.com/advisories/GHSA-9pwp-9qqc-pr26)
- [Bouncy Castle : profondeur ASN.1](https://github.com/advisories/GHSA-qp49-qgx5-5m26)
- [Undici : déni de service](https://github.com/advisories/GHSA-xjj6-f33q-929c)
- [Undici : SRI](https://github.com/advisories/GHSA-f72v-6w45-q472)

## Résultats finaux

- 26 tests JVM : zéro échec, zéro test ignoré, dont URL de partage, masquage des identifiants, import, catalogue, messages DNS et TLS.
- Build debug et APK instrumenté réussis après mise à jour Bouncy Castle 1.86.
- Lint : zéro erreur, 312 avertissements. Alertes trust-all localisées dans des dépendances ou le pairing ADB ; chemin DNS actif contrôlé séparément. Aucun nouveau contournement TLS ajouté.
- AVD Android TV API 31 : **7 tests réussis** sur l’APK final. Résolution DNS via le tunnel DoH + HTTPS `example.com`, IPv6 OFF/ON/OFF avec sonde locale, 4 scénarios de préférence/migration IPv6, filtres et recréation de l’écran. Capture du catalogue inspectée visuellement.
- OSV : **84 coordonnées Maven résolues, aucune alerte connue** après mise à jour. Résultat à la date de consultation, pas une garantie de sécurité des bibliothèques.
- Site : tests worker, traductions et sécurité des liens ; 32 combinaisons protocole/Adblock ; TypeScript ; catalogue régénéré identique ; build Wrangler `--dry-run` (aucun déploiement).
- `npm audit` final : zéro alerte connue sur les 43 paquets de l’arbre installé.
- Navigateur : accueil et 12 pages localisées + catalogue sans débordement horizontal à 390 px ; contrôle visuel mobile, bureau et arabe RTL.

Les compilations initiales ont inclus une erreur Kotlin transitoire corrigée et un échec interne de lint ; les exécutions finales ci-dessus les remplacent. Le dernier ajustement de masquage des logs est couvert par les tests JVM et le build final ; aucune modification de protocole DNS n’a été introduite dans cette refonte.
