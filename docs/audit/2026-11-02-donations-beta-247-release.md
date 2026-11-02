# 2.4.7-beta.1 — publication des nouveaux dons

Publication expressément demandée par le propriétaire le 2 novembre 2026.

- Android : https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/v2.4.7-beta.1
- Canal de mise à jour : https://github.com/PerfectDNSManager/PerfectDNSManager/releases/tag/latest-beta
- Commit version : `96c63c8ba418e945ed2bcfa2ebfc3dce03a73cb4`.
- CI Android : https://github.com/PerfectDNSManager/PerfectDNSManager/actions/runs/41476126704 — succès.
- APK : package `app.perfectdnsmanager`, version `2.4.7-beta.1`, versionCode `20407`.
- Dons : https://beta.perfectdnsmanager.app/support.html
- Worker bêta final : `bf1e6190-2150-4a2e-97a4-9cfc26d1dc1d`.
- La stable Android demeure **2.4.0** ; le déploiement du Worker stable est strictement inchangé, comparé via l'API avant/après.

## Contrôles

33 tests JVM et lint sans erreur. DonationScreenInstrumentedTest réussi sur
Android TV API 31 : chargement réel du sélecteur de crypto/réseau dans le WebView,
QR présent, fichiers/contenu/cookies tiers désactivés, contenu mixte interdit.

Serveur : tests partage, catalogue, pages et dons, typecheck, signature Ed25519
native workerd vérifiée indépendamment, et CI site réussis. Navigateur réel :
sélecteur et paire LTC chargés, montant invalide refusé sans ordre, cookie sécurisé,
lecture anonyme refusée (401), origine étrangère refusée (403), vue mobile sans
débordement. Confidentialité actualisée FR/EN et vérifiée sur le serveur bêta.

Le compte SideShift de gestion client est utilisé sur autorisation du propriétaire.
Ses identifiants sont des secrets Cloudflare du Worker bêta, jamais dans le code
client ni dans Git. Réception fixée côté serveur : USDT0 / Polygon,
`0x71528115FA8aee4D9420F6caDEBfD0C043a316f7`.

Aucun ordre d'échange, don ou remboursement réel exécuté pendant les tests. Les
créations concurrentes, réponses incohérentes et délais utilisent des simulations.
Pas de validation sur la Thomson physique dans cette session.

## APK publié

APK téléchargés depuis la release numérotée et latest-beta : même SHA-256,
`ecc0c39ccaeb044485dc5a9530d185d005e9a188e10e91378dc2a8594ba5cdf6`.

Signature vérifiée : certificat de production habituel
`c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`.

Installation `adb install -r` réussie sur l'AVD possédant déjà la 2.4.6-beta.1
signée ; version installée 20407 confirmée, sans désinstallation ni perte imposée
de données. Notes des deux releases et CHANGELOG mis à jour.
