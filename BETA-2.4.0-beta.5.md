# 2.4.0-beta.5 — xdp.es et profils personnels Control D

- Ajout de xdp.es à la liste des fournisseurs : Standard et Adblock (sur activation du réglage) en DoH, DoQ, DoT et DNS classique. Les adresses IPv6 publiées sont incluses dans les profils classiques.
- Nouveau réglage « Autoriser les profils Adblock », désactivé par défaut et indépendant des options avancées. Il affiche toutes les variantes antipublicité du catalogue, même si « Afficher les variantes » est désactivé. Le DNS actif et les profils personnels restent inchangés. Le réglage est sauvegardé et exporté/importé.
- Catalogue Adblock complété : xdp.es, AdGuard Family, Mullvad Adblock/Base/Extended/Family/All et Control D (Ads & Tracking, Family Friendly, OISD, StevenBlack, 1Hosts, HaGeZi, AdGuard Filter). Protocoles soumis à leurs réglages habituels.
- Adresses DoQ publiques Control D corrigées selon la documentation officielle. Mullvad annonce la fin de son service public le 2 novembre 2026 ; rappel affiché dans les descriptions.
- Ajout du bouton « Ajouter un profil Control D » dans la fiche du fournisseur (appui long sur ControlD dans la liste). Saisir le Resolver ID de l’appareil Control D ou coller son adresse DoH / DoT / DoQ ; nom personnalisé facultatif.
- DoH proposé par défaut. DoQ et DoT sont proposés lorsque leurs options respectives sont activées dans les réglages de PDM.
- Les profils personnels sont sauvegardés, exportés/importés avec la configuration et dédupliqués par adresse. La fiche relit les profils sauvegardés après modification ou suppression.
- Les identifiants malformés, mauvais domaines, paramètres inattendus et adresses non sécurisées sont refusés avant enregistrement.

Adresses xdp.es confirmées dans [la documentation officielle du fournisseur](https://github.com/Oihalitz/xdp-dns-evadeproxy#usar-el-dns-de-xdpes). Ces résolveurs peuvent réécrire certaines IP bloquées, y compris en variante Standard. Aucun classement de vitesse ou de confidentialité n’a été inventé.

Formats personnels Control D issus de [sa documentation](https://docs.controld.com/docs/device-clients). Aucun identifiant de compte réel n’a été utilisé pour les tests.

Validation : tests JVM des formats Control D, conservation lors de la validation d’import/export, unicité des identifiants des presets et filtrage des variantes Adblock. Requêtes DNS réelles vers le résolveur DoH Standard xdp.es réussies. Tests, lint et compilation exécutés avant publication ; validation sur TV/Fire TV réelle encore nécessaire.

Installation : canal bêta de PDM ou Downloader **2447296**.

Sources du catalogue : [Control D](https://docs.controld.com/docs/free-dns), [AdGuard](https://adguard-dns.io/en/public-dns.html), [Mullvad](https://mullvad.net/en/help/dns-over-https-and-dns-over-tls).
