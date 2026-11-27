# Unbound optionnel — préparation locale 2.4.12-beta.1

Branche `codex/pdm-unbound-beta`. Aucun push, tag, déploiement Worker ou release.
Les changements précédents du préfiltre restent inclus.

## Implémentation

- libunbound 1.26.2 + OpenSSL 3.5.9 compilés depuis des archives vérifiées, JNI
  interne et cycle de vie géré par le service VPN DNS existant.
- Profils dérivés des endpoints DoT du catalogue, profils personnels Control D
  et NextDNS, profil custom `unbound://hostname[:port]`, masqués par défaut.
- Cache local et DNSSEC, DoT authentifié vers le fournisseur choisi. Ce n'est
  pas de la récursion autonome ni un changement des politiques du fournisseur.
- Aucun démarrage du moteur lors de l'affichage des profils ou des benchmarks
  généraux. Fermeture et annulation lors de l'arrêt/changement de profil.
- Préfiltre avant résolution ; IPv6 autorisé par défaut, aucune nouvelle route
  bloquante. Les réponses AAAA sont conservées.
- CA Mozilla et ancres IANA embarquées, licences et provenance conservées.
  Bootstrap du seul hostname du fournisseur par DNS système avant le VPN.
- Limites de concurrence, taille de réponses et délais ; attente sur descripteur
  pour éviter une boucle de polling à 100 réveils/seconde au repos.
- Paramètre sauvegardé/restauré ; chaînes ajoutées aux douze langues ; IDs
  personnels également masqués pour le nouveau schéma dans les logs ordinaires.

## Vérifications

- 47 tests unitaires passent, incluant validation/import des profils personnels,
  unicité des presets et conservation de leur politique Adblock.
- Traductions : 514 ressources × 12 langues, contrôle réussi.
- Lint : zéro erreur, 325 avertissements. Ce n'est pas un code sans avertissements.
- Construction native API 21 pour armeabi-v7a, arm64-v8a, x86 et x86_64.
- Contrôle ELF/APK : quatre architectures présentes, toutes les bibliothèques
  64 bits alignées sur 16 Ko, y compris libpdm_unbound.so.
- Android 5/API 21 x86 : quatre tests instrumentés passent sur l'APK final :
  DoT réel Cloudflare A/AAAA, DNSSEC signé et domaine invalide, mauvais nom TLS
  refusé (absence de réponse ou SERVFAIL sans réponse), fermeture concurrente
  et répétée, VPN/préfiltre, visibilité des protocoles et DoH par défaut.
- Le test TLS initial exigeait exclusivement `null`. Il a été corrigé pour
  accepter aussi SERVFAIL sans réponse, qui est un échec DNS explicite et sûr.

- Android 16/API 36 x86_64, `getconf PAGESIZE` et assertion instrumentée à
  16384 : quatre tests passent (Unbound/TLS/DNSSEC/fermeture, VPN/préfiltre et
  vecteur Argon2 indépendant). L'image expérimentale API 35 n'a pas été retenue
  car son démarrage était défaillant ; ce n'est pas un test PDM réussi.
- APK debug local, application séparée `.design`, SHA256 :
  `692229a494fc7df73baabd6342b888414a775b60f267249a15c30f07b98902b9`.
  Ce n'est pas un APK de mise à jour signé pour le canal public.

## Limites et maintenance

Ce moteur n'est pas requis pour filtrer localement avec DoH/DoQ. Il ajoute des
bibliothèques natives (environ 24 Mo non compressés pour les quatre ABI) et de
la mémoire pendant son fonctionnement. Il reste optionnel, utilise le slot VPN
Android et ne remplace pas le DoT système sans VPN.

Les tests A/AAAA sur émulateur ne prouvent pas une connectivité IPv6 de bout en
bout sur tous les FAI. ARM/ARM64 sont compilés et inspectés mais pas exécutés
sur une box physique ici. Les réseaux captifs, changements Wi-Fi/Ethernet et
performances sur matériel ancien restent à vérifier avant une promotion stable.
Une horloge fausse ou un upstream qui altère des données signées peut provoquer
un échec DNSSEC/TLS ; aucun repli non chiffré n'est ajouté.

Unbound/OpenSSL/CA/ancres doivent être maintenus lors des prochaines versions.
L'état beta local ne justifie pas une publication stable automatique.

L'étude xdp/ByeByeDPI est dans `../features/xdp-and-anti-dpi-assessment.md`.
Les 24 tests Rust xdp passent ; son moteur n'est pas embarqué dans PDM.
