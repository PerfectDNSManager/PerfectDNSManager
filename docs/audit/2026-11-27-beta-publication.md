# Publication bêta 2.4.12-beta.1 — 27 novembre 2026

Publication bêta explicitement autorisée par le propriétaire, sans promotion stable.
Source : tag `v2.4.12-beta.1`, commit `6b8cd83970abe9d9845a8037ad19190456c37018`.
Branche `codex/pdm-unbound-beta`, sans mise à jour de main.

Exécution GitHub réussie :
https://github.com/PerfectDNSManager/PerfectDNSManager/actions/runs/45932178892

Reconstruction native ADB et Unbound, traductions, 48 tests JVM/lint, compilation
optimisée, alignement, signature et publication : étapes réussies. Les actions
anciennes exécutées sous Node 24 émettent des avertissements de maintenance ;
leur mise à niveau reste distincte de la validation de cet APK.

## Artefacts et canal

Release https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/v2.4.12-beta.1
marquée prerelease. Le canal `latest-beta` annonce exactement 2.4.12-beta.1 et
contient le même changelog que cette version. APK numéroté et alias téléchargés,
identiques octet pour octet : 20 475 098 octets, SHA256
`d2b92711b767d39df16de343d96ffe3b96633247aa12384d611c2be90a53e6d6`.

Signature de production inchangée, SHA256 du certificat :
`c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`.
Schémas v1 et v2 vérifiés. package `app.perfectdnsmanager`, versionCode 20412,
versionName 2.4.12-beta.1, minSdk 21, targetSdk 34 ; quatre ABI présentes.
Contrôle ELF/APK et zipalign -P 16 réussis sur l'APK téléchargé.

GitHub latest stable reste `v2.4.11`, release ID 436358160. APK stable de référence
SHA256 `ef6086e0b950e4b168d6911f855923b0ed9440f87e7ec4af035ec1d1917be361`.
Aucun déploiement du site ou du Worker de secours.

## Installation réelle et limites

Sur un AVD API 36 appartenant à cette session, pages réelles de 16 Ko : installation
stable signée 20411, ajout d'un profil synthétique Cloudflare et d'un marqueur
local, puis `adb install -r --no-incremental` de l'APK publié 20412 réussi sans
suppression des données. Le profil et le marqueur sont conservés. Après fermeture
du dialogue initial de notification, MainActivity de la bêta démarre avec statut
OK (3,2 secondes), sans crash applicatif observé.

La préparation d'un contrôle instrumenté supplémentaire sur une copie locale
resignée a rencontré un crash du **system_server PackageManager** à la
désinstallation : NullPointerException dans AppIdPermissionPolicy.onPackageRemoved.
Cet essai interrompu n'est pas compté comme test fonctionnel Unbound du release
sur API 36. Les tests Unbound/IPv6/16 Ko avant publication restent documentés dans
`2026-11-27-unbound-beta.md`. Le payload de la copie resignée est identique aux
454 entrées hors META-INF de l'APK publié ; elle n'est jamais distribuée.

## Contrôle optimisé Android 5

La copie de l'APK publié, resignée uniquement pour le runner de test local, a
passé le contrôle sur un AVD API 21 x86 avec pages de 4096 octets : vecteur Argon2,
absence de dépendance ADB sous API 30, import/export de configuration historique,
écrans principal/paramètres/fournisseurs/langues et VPN Unbound réel en DoT avec
réponses A/AAAA. Résultat du runner : `ALL PASSED`. Consentement VPN accepté dans
le dialogue Android normal sur cet AVD jetable.

Le runner ajoute l'option de diagnostic `dnsOnly=true` pour ne pas confondre le
DoT authentifié du moteur Unbound avec une requête HTTPS séparée utilisant les
anciennes racines système Android 5. Aucun contrôle TLS du moteur n'est désactivé.
Ce changement concerne uniquement l'outil de test, pas le payload publié.

## Sujets encore ouverts

Les fonctions demandées récemment sont présentes : réglage Adblock déplacé,
préfiltre local, Unbound facultatif, DNSForge/DNS4EU, et archives/secours xdp.
Le Worker reste préparé mais non déployé. Le script anti-DPI historique n'a pas
été retrouvé, seul le dépôt évoquant ByeByeDPI est identifié. Les tests physiques
sur box anciennes et la connectivité native IPv6 des nouveaux fournisseurs
restent à confirmer.

Relecture des tickets GitHub : quatre ouverts (#1 presse-papiers, #2 Formuler,
#3 IPv6, #4 catalogues supplémentaires de résolveurs). Aucun n'a été fermé ni
commenté sans validation supplémentaire. Aucune PR ouverte au moment du contrôle.
