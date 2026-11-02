# 2.4.6-beta.1 — publication DoT externe

Publié le 2 novembre 2026 dans le canal bêta, sur demande explicite du propriétaire.
La stable demeure **2.4.0**. La nouvelle intégration de dons n'est pas incluse.

- Commit de version : `305940fe9efa4798d5cd9a9b8cebdc988c2a3a2c`.
- Version APK : `2.4.6-beta.1`, versionCode `20406`, package `app.perfectdnsmanager`.
- GitHub Actions : https://github.com/PerfectDNSManager/PerfectDNSManager/actions/runs/41471348195 — succès.
- Release : https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/v2.4.6-beta.1
- Canal : https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/latest-beta
- Notes des deux releases et CHANGELOG mis à jour.

## Vérifications

Compilation debug locale, 33 tests JVM, lint sans erreur ; contrôles répétés par
la CI. Les sources DoT publiées sont identiques aux sources testées sur Android
TV API 31 : reconnaissance du serveur externe, remplacement d'une sélection DoH
périmée, hostname personnel, reprise d'activité, arrêt/réactivation via le bouton
avec les autorisations Android accordées dans l'émulateur.

Les deux APK téléchargés (release numérotée et latest-beta) ont le même SHA-256 :
`467511f7ac90318bbeeb2e4391f8a5f4e589e72a54b872bc9177838404e97fb7`.

Signature vérifiée par apksigner ; certificat de production habituel :
`c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`.

Installation avec `adb install -r` sur l'AVD TV possédant déjà l'APK de production
2.4.5-beta.1/versionCode 20405 : succès, version installée confirmée 20406.
Aucune désinstallation ou purge de données nécessaire. Pas de validation physique
sur la Thomson dans cette session.
