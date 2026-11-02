# DoT externe et dons communs — candidat local, non publié

## DoT

Le lancement et la reprise de l'activité reconnaissent le vrai hostname Android
en mode strict. Recherche dans les profils connus, aliases Cloudflare exacts et
suffixes délimités pour les profils ControlD/NextDNS. Les inconnus restent nommés
par leur hostname et deviennent des profils personnels. Le choix est conservé
lorsque le DoT est désactivé, sans réactivation automatique au démarrage.

La synchronisation est suspendue lors d'une sélection/activation en cours, pour
ne pas réappliquer accidentellement l'ancien profil. Les droits Android/ADB
existants restent nécessaires pour modifier le DNS système.

Validation : 33 tests JVM réussis ; compilation debug et APK de tests ; lint sans
erreur. Android TV API 31 : fournisseur externe remplaçant une sélection DoH
périmée, mise à jour au retour depuis les paramètres, profil inconnu, persistance
après désactivation. Le bouton réel a été testé : arrêt puis réactivation de
one.one.one.one avec WRITE_SECURE_SETTINGS accordé dans l'émulateur de test.
Le test existant des paramètres DoT reste passant. Ce n'est pas une validation
sur la Thomson physique ni sur toutes les méthodes de permission ADB.

## Dons

SupportActivity charge la page HTTPS /support.html de PDM_BASE_URL ; la page
serveur possède le formulaire et toute la logique de conversion. Le QR statique
ouvre la même page sur un téléphone, sans identifiant de paiement. WebView sans
accès fichier/contenu, sans pont JS natif, sans contenu mixte ni contournement TLS ;
liens externes ouverts dans le navigateur. QR disponible si WebView est absent.

Réception confirmée par le propriétaire : USDT0 / Polygon,
0x71528115FA8aee4D9420F6caDEBfD0C043a316f7.

Le serveur et ses tests sont dans le dépôt pdm-worker, branche locale
codex/pdm-site-design-beta, documentation docs/donations.md. Le compte SideShift de gestion client a été autorisé et validé en local. Le
serveur de dons reste à déployer. Aucun ordre réel, transfert, déploiement du site ou APK bêta
publié pour ce candidat. Déployer/valider le serveur bêta avant de distribuer cet
écran ; ne pas pointer les utilisateurs vers une intégration non activée.
