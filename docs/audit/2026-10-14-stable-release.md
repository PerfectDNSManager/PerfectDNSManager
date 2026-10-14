# Publication stable 2.4.0 — 14 octobre 2026

- Sources : tag `v2.4.0`, commit `0bc3bdc510b8a8a95d36fc4ed3000dd03819995b`.
- GitHub Actions : run `38550407885`, succès (tests JVM, lint, compilation release, signature).
- Release publique non préliminaire, marquée Latest ; APK versionné et alias `PerfectDNSManager-latest.apk` / `latest.apk` présents.
- APK téléchargé depuis la release : `app.perfectdnsmanager`, version `2.4.0`, code `20400`, min SDK 21, cible 34.
- Certificat SHA-256 : `c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d`.
- APK SHA-256 : `98c2d0c9235cdc6668ad9b61715ccf4530eba858e4e14905ee6b4d8c1165535a`.

## Mise à jour sur Android TV 12 / API 31

Installation signée par-dessus la bêta 9 avec `adb install -r` : succès, sans effacement des données. Le profil Control D Unfiltered est conservé, le VPN DoH actif et l'écran affiche la version 2.4.0. Le sélecteur « Disable IPv6 / Force IPv4 » reste décoché et les routes du VPN ne contiennent pas `::/0`.

Pendant le démarrage à froid de l'AVD, avant la mise à jour (12:40:54, installation stable à 12:41:59), la bêta 9 a enregistré un `ForegroundServiceDidNotStartInTimeException`. L'émulateur était très lent et optimisait les applications ; cette cause n'est pas prouvée. Le lancement après mise à jour et la connexion ont réussi. Ce constat ne constitue pas une validation du démarrage à froid sur Fire Stick physique. Les tests DNS/HTTPS et IPv6 OFF → ON → OFF détaillés sont consignés dans `2026-10-14-ipv6.md`.

## Changelog et site

- Changelog cumulatif ajouté au dépôt et aux notes de release GitHub.
- Site : changelog 2.4.0 dans les 12 langues et résumé d'accueil actualisé ; vérification HTTP des 13 pages réussie.
- Worker : commit `f9c0b49`, déploiement `7c12a10a-0cb9-4bc0-961f-489068b4863e`.
- Le bundle du backend de production a été comparé à celui récupéré avant déploiement : identique, à l'exception de la référence au module HTML d'accueil. Les modifications locales non publiées du backend n'ont pas été incluses.
- `POST /api/challenge` répond HTTP 200 après publication ; GitHub `/releases/latest` retourne `v2.4.0`, `prerelease=false`.
