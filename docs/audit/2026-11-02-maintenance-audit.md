# Audit de maintenance et sécurité — 2 novembre 2026

Base Android : 2132d26 (2.4.8-beta.1 publiée). Site : b0f7081. Corrections de cette passe préparées dans les branches de travail ; aucune nouvelle release ni aucun déploiement.

## Périmètre et méthode

Inventaire des sources Android/Kotlin/Java, ressources, manifeste, Gradle et dépendances runtime résolues ; lecture ciblée des transports DNS/VPN/TLS, imports, partage chiffré, ADB, démarrage et mises à jour. Côté site : Worker, dons, téléchargement/déchiffrement, dépendances npm et source legacy. Recherche de références, lint, tests JVM, tests adversariaux serveur, navigateur et signature workerd. Les binaires natifs vendus ne sont pas reconstruits ni audités intégralement ici. Ce travail ne garantit pas l’absence de toute faille ou de tout code mort dynamique.

## Constats corrigés

1. **Code et ressources inutilisés.** Material et ConstraintLayout n’ont aucun appelant ni layout/thème utilisateur dans le projet : retrait des deux dépendances. Suppression des alias Shizuku inutilisés (le code ADB embarqué reste utilisé), de la table de notes fictives des fournisseurs, du rendu des étoiles et d’une ancienne notification privée de BootReceiver sans appelant. Les 42 ressources signalées par lint représentent 244 définitions/fichiers avec leurs traductions : anciens QR crypto, anciens filtres, textes, couleurs/styles. Inventaire joint. Les icônes de fournisseurs retirés du catalogue restent utiles aux profils personnels/importés et ne sont pas supprimées aveuglément.
2. **Parseur DNS.** dnsjava 3.6.5 → 3.7.1. Le changelog amont 3.7.0 annonce notamment des corrections de validation/allocation. L’avis GHSA-rwxq-7vpj-2rm5 indique de façon ambiguë « affected <3.6.5 » et « patched 3.7.0 » ; OSV ne signalait donc pas notre 3.6.5. Ne pas présenter ce scan vide comme une garantie. Les points d’entrée de DnsMessages bornent maintenant aussi les requêtes et les messages à réécrire/restaurer à 8192 octets ; test de paquets surdimensionnés et compteurs de sections forgés.
3. **JSON.** Gson 2.11.0 → 2.15.0 ; tests de sauvegarde/import et catalogue conservés. Recherche des profils prédéfinis par index local lors d’un import, au lieu d’un parcours de la liste pour chaque profil.
4. **Chemin DNS fréquent.** Suppression des champs Pending.time/origId jamais lus et des appels d’horloge associés. Les ID DNS réels restent gérés par les transports et DnsMessages.
5. **Cycle de vie.** Les dialogues de mise à jour refusent désormais aussi une Activity détruite, notamment après recréation/rotation ; le contrôle isFinishing seul ne couvre pas ce cas.
6. **Serveur.** Un expires_in correspondant à une propriété héritée d’objet JavaScript (constructor/toString/__proto__) provoquait une date invalide et une réponse d’erreur. La table TTL n’accepte plus que ses clés propres ; les autres valeurs prennent 1h, comme les autres entrées invalides. Correctif principal et legacy, test de régression.
7. **Maintenance serveur.** Retrait du type SlugMetadata et paramètres inutilisés ; activation permanente de noUnusedLocals/noUnusedParameters. Wrangler et workers-types actualisés dans le lockfile. Contrôle SHA-256 de la distribution Gradle ajouté depuis la source officielle.

## Dépendances : état vérifié, pas toutes à la dernière version

Métadonnées des dépôts Maven officiels et registre npm consultées le 2 novembre 2026. Les dépendances runtime passent de **75 à 63 coordonnées Maven** après nettoyage. OSV : aucun avis retourné pour les 63 versions finales ; npm audit : aucun avis connu. Un scan de motifs de clés privées/tokens dans les fichiers suivis ne retourne aucun résultat. Ces contrôles ne couvrent pas les secrets sous toutes leurs formes ni toutes les CVE des composants natifs.

| Dépendance | Avant | Dernière stable publiée | Décision |
|---|---|---|---|
| androidx.appcompat:appcompat | 1.7.0 | 1.8.0 | Migration à valider séparément |
| com.google.android.material:material | 1.12.0 | 1.14.0 | Supprimée : inutilisée |
| androidx.constraintlayout:constraintlayout | 2.1.4 | 2.2.2 | Supprimée : inutilisée |
| androidx.recyclerview:recyclerview | 1.3.2 | 1.4.0 | Migration à valider séparément |
| com.google.code.gson:gson | 2.11.0 | 2.15.0 | Actualisée vers 2.15.0 |
| com.squareup.okhttp3:okhttp | 4.12.0 | 5.5.1 | Migration à valider séparément |
| dnsjava:dnsjava | 3.6.5 | 3.7.1 | Actualisée vers 3.7.1 |
| org.slf4j:slf4j-nop | 1.7.36 | 2.0.20 | Migration à valider séparément |
| org.bouncycastle:bcpkix-jdk18on | 1.86 | 1.86 | Déjà à jour |
| org.bouncycastle:bcprov-jdk18on | 1.86 | 1.86 | Déjà à jour |
| org.conscrypt:conscrypt-android | 2.5.2 | 2.8.0 | Migration à valider séparément |
| tech.kwik:kwik | 0.10.8 | 0.12.1 | Migration à valider séparément |
| tech.kwik:agent15 | 3.1 | 4.0 | Migration à valider séparément |
| com.lambdapioneer.argon2kt:argon2kt | 1.5.0 | 1.6.0 | Migration à valider séparément |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | 1.11.0 | Migration à valider séparément |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.4 | 2.11.0 | Migration à valider séparément |
| com.android.tools:desugar_jdk_libs | 2.0.4 | 2.1.5 | Migration à valider séparément |
| junit:junit | 4.13.2 | 4.13.2 | Déjà à jour |
| androidx.test.ext:junit | 1.2.1 | 1.3.0 | Migration à valider séparément |
| androidx.test.espresso:espresso-core | 3.6.1 | 3.7.0 | Migration à valider séparément |
| androidx.core:core-ktx | 1.13.1 | 1.19.1 | Migration à valider séparément |
| com.android.tools.build:gradle | 8.7.3 | 9.5.0 | Migration à valider séparément |
| org.jetbrains.kotlin:kotlin-gradle-plugin | 2.0.0 | 2.4.21 | Migration à valider séparément |

Les métadonnées AAR des versions récentes sont jointes : AppCompat 1.8.0, Core 1.19.1 et Lifecycle 2.11.0 exigent API 23, alors que PDM supporte API 21. Core demande aussi compileSdk 37 / AGP 9.1 ; RecyclerView 1.4 exige compileSdk 35. Passer toutes les versions à latest en bloc changerait donc la compatibilité des box. Conscrypt/Argon2, QUIC/agent15 et la chaîne Kotlin/AGP nécessitent des essais dédiés ADB, chiffrement interopérable et réseau TV avant migration. SLF4J doit rester cohérent avec son API résolue, pas seulement son binding.

Côté npm : Wrangler **4.156.2**, workers-types **5.20261102.1**, TypeScript **5.9.3**. TypeScript 7.0.2 est disponible mais constitue une migration majeure non faite dans cette passe. Les outils legacy sans lockfile restent une dette de maintenance ; ce source n’est pas déployé.

## Points à traiter dans une migration dédiée

- **Priorité haute : compatibilité mémoire 16 Ko confirmée incomplète.** Dans l’APK arm64, `readelf -lW` donne des segments LOAD alignés sur `0x1000` (4 Ko) pour libargon2jni.so, libargon2native.so et libconscrypt_jni.so, contre `0x4000` pour libadb.so. Les trois premiers ne satisfont pas le critère ELF 16 Ko ; il faut actualiser/recompiler ces dépendances et tester sur un système 16 Ko. Voir https://developer.android.com/guide/practices/page-sizes. Aucun appareil 16 Ko testé dans cette passe.
- **Priorité haute : bibliothèques natives.** Conscrypt 2.5.2 et Argon2kt 1.5.0 sont anciens ; versions disponibles 2.8.0 et 1.6.0. Contrôler TLS/ADB, formats chiffrés, ABI et pages mémoire 16 Ko sur Android récent. Le JNI ADB livré n’a pas de chaîne C/C++ de reconstruction complète dans ce dépôt.
- **Priorité moyenne : toolchain.** AGP 8.7.3, Kotlin 2.0.0, compile/target SDK 34 ne sont pas récents. La migration nécessite de vérifier les restrictions de services au démarrage, VPN, permissions et affichage TV ; pas de hausse implicite du minSdk.
- **Priorité moyenne : taille APK.** R8/minification et resource shrinking ne sont pas activés. Ne pas les activer sans règles testées pour Gson/réflexion, JNI ADB, QUIC et les parcours chiffrés ; retrait explicite des dépendances mortes effectué ici.
- **Optimisations possibles non mesurées :** indexer les règles de réécriture DNS si les listes deviennent grandes ; suspendre le polling des dons en arrière-plan ; fractionner les très grandes Activity pour isoler UI/réseau et faciliter les tests. Aucun gain de vitesse DNS n’est promis sans benchmark.
- Les alertes lint restantes concernent surtout localisation, mise en page et compatibilité. Le HTTP clair autorisé sert aux tests Ookla ; les flux sensibles restent HTTPS/TLS. Les implémentations trust-all dans les JAR ne prouvent pas leur utilisation : le chemin DoQ impose TlsTrust.forHost. Le pairing ADB local conserve son modèle distinct.
- Le mot de passe de partage reste à six caractères selon le choix historique : Argon2id ralentit la recherche hors ligne, l’expiration serveur ne détruit pas une copie déjà téléchargée.

## GitHub

Trois issues ouvertes (#1 presse-papiers, #2 Formuler Z7+/Zx, #3 IPv6), aucune PR ouverte au moment de la revue. Pas de commentaire ni clôture automatique. Les correctifs antérieurs ne prouvent pas la résolution du cas Formuler sans essai sur ce matériel.

## Sources primaires

- https://github.com/dnsjava/dnsjava/blob/master/Changelog
- https://github.com/dnsjava/dnsjava/security/advisories/GHSA-rwxq-7vpj-2rm5
- https://github.com/google/gson/releases/tag/gson-parent-2.15.0
- https://developer.android.com/jetpack/androidx/versions
- https://services.gradle.org/distributions/gradle-9.1.0-bin.zip.sha256
- URLs Maven exactes dans dependency-versions.json ; résultats OSV et métadonnées AAR joints.

## Validation

Première passe Android : compilation debug, 33 tests JVM et lint réussis ; ressources inutilisées 42 → 0. Passe finale et test TV consignés ci-dessous après exécution. Site : tests unitaires/adversariaux, typecheck principal et legacy, parcours navigateur dons FR/EN, signature native workerd et dry-run Worker bêta réussis. Aucun don réel, aucune transaction ni aucun déploiement pendant cet audit.

Mesure de taille : APK release local 13 024 775 octets contre 15 220 005 pour la bêta publiée. Pour limiter l’écart de signature/META-INF, le contenu ZIP compressé hors META-INF passe de 15 000 984 à 12 961 307 octets (**−13,6 %**, environ 2,04 Mo). Ce n’est pas une mesure de latence DNS.

Validation finale : assembleDebug, assembleRelease, testDebugUnitTest et lintDebug réussis. **34 tests JVM, 0 échec ; lint 0 erreur, 287 avertissements, 0 UnusedResources.** Neuf tests instrumentés réussis sur émulateur Android TV API 31 : navigation verticale fournisseurs, filtres de protocoles, préférences IPv6, progression/annulation du téléchargement et rejet d’un APK invalide. Aucun test sur appareil 16 Ko ni box physique. Les anciennes API Android conditionnelles et avertissements de style restent documentés, sans suppression globale des contrôles.
