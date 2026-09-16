# 2.4.0-beta.7 — correctif « plus d'internet » en DoH + corrections de revue

## Correctif principal
La bêta 6 coupait la résolution DNS dès l'activation d'un profil DoH (signalé sur Fire Stick) : la protection `VpnService.protect()` était appliquée à un socket dont le descripteur natif n'existait pas encore. Chaque requête échouait (`Cannot protect DNS socket`). Le socket est maintenant lié avant d'être protégé.

## Corrections issues de la revue de code
- **Migration 2.3.2** : les profils ControlD DoQ enregistrés au format `quic://freedns.controld.com/p0` sont convertis. Avant, un utilisateur 2.3.2 avec ce profil sélectionné ne pouvait plus démarrer le VPN.
- **Import de sauvegarde** : un profil invalide n'entraîne plus le rejet de tout le fichier (tout export 2.3.2 était refusé). Les profils écartés sont signalés.
- **Profils personnels** : ceux qui ne passent pas la nouvelle validation ne sont plus supprimés définitivement au premier lancement.
- **Édition d'un preset** : crée une copie personnelle au lieu de dupliquer l'identifiant du preset.
- **Réglage Adblock** : hérité pour les utilisateurs qui affichaient les variantes ou utilisaient un profil adblock.
- **ControlD** : prise en charge du nom d'appareil (`/id/appareil` en DoH, `id-appareil` en DoT/DoQ).
- **Speedtest** : les connexions parallèles ne sont plus multiplexées dans une seule (débit sous-estimé), plus de coupure à 60 s sur connexion lente.
- **Mises à jour** : plus de coupure du téléchargement sur connexion lente, purge des anciens APK du cache, vérification de l'hôte à chaque redirection, plus de crash possible si le cache est plein.
- **Testeur de blocage** : un blocage FAI par NXDOMAIN est de nouveau affiché « bloqué ».
- **Notifications** : un tap n'empile plus un second écran principal.
- **Partage** : un code court créé en bêta est lisible depuis la stable et inversement.
- DNS privé : plus de désactivation erronée quand le nom DoT contient « off » ; état DoT remis à jour ; canal bêta non conservé après passage en stable.
- Validation des règles de réécriture ; `https://1.1.1` n'est plus accepté comme nom d'hôte ; réponses DNS rejetées désormais journalisées.
- Traduction des 12 textes ajoutés restés en anglais.
- R8 (minification) retiré : l'app est open source.

## Validation (AVD Android 13)
- Test instrumenté depuis l'app (requêtes DNS vers `192.0.2.2` + HTTPS) : Cloudflare DoH, AdGuard DoQ, NextDNS DoQ, ControlD DoH, ancien ControlD DoQ 2.3.2, xdp.es DoH et DoQ — tous OK.
- APK release : VPN DoH actif, le test de fuite DNS voit le résolveur Cloudflare, aucune erreur.
- Mise à jour simulée depuis des préférences 2.3.2 : profil ControlD DoQ hérité fonctionnel, profils perso conservés, réglage Adblock hérité.
- 22 tests unitaires OK.
- Reste à confirmer sur le Fire Stick physique.
