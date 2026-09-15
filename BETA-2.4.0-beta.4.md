# 2.4.0-beta.4 — xdp.es et profils personnels Control D

- Ajout de xdp.es à la liste des fournisseurs : variante Standard uniquement en DoH, DoQ, DoT et DNS classique. Les adresses IPv6 publiées sont incluses dans les profils classiques.
- Ajout du bouton « Ajouter un profil Control D » dans la fiche du fournisseur (appui long sur ControlD dans la liste). Saisir le Resolver ID de l’appareil Control D ou coller son adresse DoH / DoT / DoQ ; nom personnalisé facultatif.
- DoH proposé par défaut. DoQ et DoT sont proposés lorsque leurs options respectives sont activées dans les réglages de PDM.
- Les profils personnels sont sauvegardés, exportés/importés avec la configuration et dédupliqués par adresse. La fiche relit les profils sauvegardés après modification ou suppression.
- Les identifiants malformés, mauvais domaines, paramètres inattendus et adresses non sécurisées sont refusés avant enregistrement.

Adresses xdp.es confirmées dans [la documentation officielle du fournisseur](https://github.com/Oihalitz/xdp-dns-evadeproxy#usar-el-dns-de-xdpes). Ces résolveurs peuvent réécrire certaines IP bloquées, y compris en variante Standard. Aucun classement de vitesse ou de confidentialité n’a été inventé.

Formats personnels Control D issus de [sa documentation](https://docs.controld.com/docs/device-clients). Aucun identifiant de compte réel n’a été utilisé pour les tests.

Validation : tests JVM des formats Control D, conservation lors de la validation d’import/export, unicité des identifiants des presets et absence de variante Adblock. Requêtes DNS réelles vers le résolveur DoH Standard xdp.es réussies. Tests, lint et compilation exécutés avant publication ; validation sur TV/Fire TV réelle encore nécessaire.

Installation : canal bêta de PDM ou Downloader **2447296**.
