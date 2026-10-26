# Changelog

## 2.4.1-beta.design.1 — 26 octobre 2026

- Refonte graphique et filtres DoH / DoT / DoQ / UDP ; profils Adblock sur activation explicite.
- Cloudflare, Control D, dns.sb en tête ; retrait des presets Quad9, Surfshark et Mullvad.
- FDN : DNS classique réservé aux réseaux FDN/FFDN, DoH et DoT publics conservés.
- Correction des requêtes DoQ simultanées au démarrage ; erreurs DNS explicites en cas de panne amont.
- IPv6 autorisé par défaut, interrupteur vérifié en connexion active.
- Paramètres : détection du DNS privé DoT posé via ADB ou Android, même sans profil PDM enregistré. Explication de sa conservation par Android après redémarrage ; un ancien profil VPN ne remplace plus ce DoT automatiquement.
- Durcissement des liens de partage, des lectures réseau et des journaux ; mise à jour Bouncy Castle.

Validation : tests JVM et Android TV, matrice de 89 profils + contrôle négatif, site en 12 langues. Les tests physiques Firestick/Formuler restent à confirmer.

## 2.4.0 — 14 octobre 2026

### Fournisseurs et profils
- Ajout de **xdp.es Standard**, avec les protocoles DoH, DoT, DoQ et DNS classique.
- Ajout de **profils personnels Control D**, à partir du Resolver ID ou de l’adresse du résolveur, avec un nom d’appareil facultatif.
- Nouveau réglage **« Autoriser les profils Adblock »** : affiche les variantes antipublicité disponibles chez les fournisseurs du catalogue. Désactivé sur une nouvelle installation ; les utilisateurs qui affichaient déjà ces variantes conservent leur accès.
- Correction et migration des anciennes adresses DoQ NextDNS et Control D.

### Connexion et IPv6
- Correction de la perte de résolution DNS en DoH introduite dans la bêta 6 et signalée sur Fire Stick : le socket est initialisé avant sa protection par le VPN.
- **IPv6 est autorisé par défaut.** L’option « Désactiver IPv6 / Forcer IPv4 » contrôle réellement le blocage du trafic IPv6.
- À la première mise à jour, l’ancien blocage IPv6 est désactivé une seule fois, même s’il avait été activé manuellement : les anciennes préférences ne permettaient pas de distinguer ce choix de l’activation automatique. Il peut être réactivé ensuite et ce choix sera conservé.
- L’écran distingue désormais IPv6 indisponible et blocage activé dans PDM. L’autorisation IPv6 ne fournit pas de connectivité IPv6 à un réseau qui en est dépourvu.
- L’adresse IP reste affichée pendant les rafraîchissements, y compris sur une connexion lente.
- Meilleure gestion des changements de réseau, des sessions VPN et des connexions DoT.

### Sauvegardes, partage et mises à jour
- Imports plus tolérants : les profils invalides sont signalés sans rejeter toute la sauvegarde ; les profils personnels existants ne sont plus supprimés par la nouvelle validation.
- Modifier un profil prédéfini crée une copie personnelle. Les exports et la conservation des réglages sont corrigés.
- Copie des liens de partage uniquement sur demande, sans lecture automatique du presse-papiers ; compatibilité des codes de partage entre stable et bêta.
- Téléchargements de mises à jour et tests de débit plus fiables sur les connexions lentes ; nettoyage des anciens APK en cache.
- Correction de l’affichage de certains résultats des tests DNS et de blocage, et de l’ouverture de l’application depuis les notifications.

### Sécurité et compatibilité
- Validation renforcée des réponses DNS, des profils, des imports et des règles de réécriture.
- Vérification des redirections, du paquet et de la signature des mises à jour ; limites sur les données réseau reçues.
- Renforcement des contrôles TLS, des échanges ADB et du cycle de vie du VPN ; mise à jour de BouncyCastle.
- Corrections de compatibilité Android 5 et de traductions.

### Validation
- 22 tests JVM et lint Android réussis sur la version candidate.
- Tests sur émulateurs Android 13 et Android TV 12 : DNS/HTTPS, migrations et passage IPv6 avec blocage OFF → ON → OFF.
- La validation sur Fire Stick physique et sur les modèles Formuler de l’issue #2 reste à confirmer.

Installation stable : [APK](https://github.com/PerfectDNSManager/PerfectDNSManager/releases/latest/download/PerfectDNSManager-latest.apk) ou code Downloader **9909387** (ancien code **2759555** toujours disponible).

Les notes détaillées des étapes de validation sont conservées dans les fichiers `BETA-2.4.0*.md` et `docs/audit/`.
