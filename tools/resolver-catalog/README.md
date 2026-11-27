# Import explicite des catalogues YogaDNS / DNSCrypt

L’issue #4 demande davantage de serveurs pour les tests. Le benchmark de PDM
inclut désormais les profils personnels et tous les endpoints distincts.
Ce convertisseur Python 3.9+ prépare un import JSON depuis les listes demandées,
sans ajouter automatiquement des fournisseurs ni changer le DNS actif.

1. Exporter ses profils dans PDM vers `pdm-backup.json`.
2. Examiner le catalogue :

   ```sh
   python3 tools/resolver-catalog/convert.py --source dnscrypt
   python3 tools/resolver-catalog/convert.py --source yoga
   ```

3. Choisir explicitement un ou plusieurs noms exacts affichés, puis générer :

   ```sh
   python3 tools/resolver-catalog/convert.py --source yoga --name 'Google DoH' --base pdm-backup.json --output pdm-import.json
   ```

4. Relire le JSON et l’importer dans PDM par le parcours de configuration existant.
   Les profils du fichier de sauvegarde sont conservés. Ce fichier n’inclut ni
   paramètres ni DNS sélectionné. Le benchmark peut alors tester les profils.

`--input fichier.md` permet de travailler avec une copie locale du catalogue.
Sans cette option, seul le catalogue choisi est téléchargé en HTTPS (1 Mio maximum).
L’affichage liste son SHA-256 pour tracer la copie ; aucune vérification Minisign
n’est revendiquée. Les données sont parsées, jamais exécutées. Les profils ne sont
pas recommandés ou certifiés sûrs du seul fait de leur présence dans une liste.

Limites explicites :
- Conversion DoH uniquement, avec nom d’hôte authentifié et port HTTPS conservé.
- Pins de certificats non représentables dans les profils PDM : entrée rejetée,
  jamais convertie en supprimant cette contrainte de sécurité.
- DNSCrypt, relais anonymisés, ODoH, DoT/DoQ de ces catalogues : non convertis.
  Les profils DoT/DoQ natifs de PDM restent utilisables séparément.
- L’adresse d’amorçage de la stamp est remplacée par la résolution du nom via
  le réseau utilisé par PDM. Les listes de bootstrap conseillées sont ignorées.
- Sans drapeau « ne filtre pas », le profil est marqué filtrant par précaution ;
  la politique exacte est à vérifier auprès de l’opérateur. Le benchmark respecte
  le réglage autorisant les profils Adblock.
- 512 profils / 1 Mio maximum, sortie nouvelle uniquement, noms choisis explicitement.
- Aucun catalogue distant permanent ni mise à jour automatique dans l’interface.

Relevé du 27 novembre 2026 : YogaDNS 2 entrées DoH convertibles / 4 exclues ;
DNSCrypt public-resolvers 18 / 875 ; relays 0 / 315. Les exclusions concernent
surtout des protocoles ou pins non pris en charge, pas nécessairement des serveurs
en panne. Test : `python3 -m unittest discover -s tools/resolver-catalog`.

Spécification : https://dnscrypt.info/stamps-specifications/
