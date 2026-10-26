# Retour au design original — 26 octobre 2026

À la demande de l’utilisateur, la palette claire/sombre et les cartes de profils sont reprises à l’identique depuis la stable 2.4.0 (6b6a12b). La taille du titre et son espacement retrouvent leurs valeurs initiales ; la correction empêchant le chevauchement avec Retour est conservée. Les évaluations statiques non mesurées ne sont pas réintroduites.

Les filtres protocoles demandés séparément, la navigation télécommande, le catalogue et toutes les corrections réseau/sécurité sont conservés. Aucun chemin réseau DNS ni aucune dépendance ne sont modifiés par ce retour visuel.

Version suivante : 2.4.2-beta.1, code 20402 pour permettre la mise à jour depuis la bêta design 20401. Publication exclusivement sur le canal bêta. Le site local reprend sa présentation initiale en conservant les corrections et les filtres ; aucun déploiement web.

## Sortie du canal bêta

Le démarrage ne force plus la préférence beta_updates_enabled. Une préférence absente est initialisée selon la build ; un choix explicite est conservé. Le drapeau hérité beta_channel_forced est migré. Le contrôle des paramètres est déverrouillé.

Le retour explicite récupère uniquement releases/latest, avec les mêmes limites HTTPS/hôtes/taille, vérification du paquet et du certificat que les mises à jour. Une APK annoncée stable mais portant un suffixe est refusée. Comparaison des versionCode réels : installation normale si possible, sinon enregistrement dans Downloads/PerfectDNSManager, via MediaStore sur Android 10+ ou stockage public avec permission limitée aux API 28 et antérieures, avant toute désinstallation manuelle. Aucun effacement automatique de données. L’utilisateur est invité à exporter sa configuration et conserver lien ET mot de passe à part. Cette action désactive les mises à jour bêta, y compris avant un export, pour éviter leur réactivation lors de la restauration.

Un refus de permission ou un échec de copie ne déclenche aucune désinstallation. Aucun sélecteur de documents nécessaire sur Android TV/Fire OS. Les instructions sont traduites dans les 12 langues.

## Validation avant publication

- Compilation debug et instrumentée réussie ; 30 tests JVM sans échec. Lint : {'Warning': 324}.
- Android TV API 31 : 4 tests de canal, filtres et DoT système réussis ; après ajout du stockage public, 3 tests de stockage/canal réussis (dont les 2 tests canal répétés).
- L’enregistrement MediaStore est relu après suppression de la source en cache ; emplacement public Download/PerfectDNSManager et état non-pending vérifiés. La branche Android 9 et antérieur reste à confirmer sur appareil physique.
- Palette claire/sombre et cartes de profils comparées octet par octet avec la stable ; capture du catalogue inspectée.
- Site : tests worker/site/catalogue et TypeScript réussis ; 32 combinaisons de filtres. Présentation du catalogue contrôlée dans le navigateur local.


## Retour aux protocoles pilotés par les paramètres — beta.2

La publication beta.1 a été annulée avant livraison. Les cases de sélection des protocoles sont supprimées. DoH est toujours visible ; DoT, DoQ, DNS classique et DNS opérateurs suivent leurs paramètres existants (désactivés par défaut). Le même filtrage est appliqué aux détails et aux profils personnels, sans modifier la liste source. Le test instrumenté couvre les 16 combinaisons de réglages et l’absence de cases après recréation. Le site local conserve la recherche et l’option Adblock mais retire les cases de protocoles.

Validation beta.2 : assembleDebug + assembleDebugAndroidTest + testDebugUnitTest + lintDebug réussis (30 tests JVM, zéro échec). Android TV API31 : cinq tests instrumentés réussis (protocoles, persistance du choix bêta, copie stable en Downloads, DoT système). Tests web et typecheck réussis. Le moteur DNS n’a pas été modifié par ce retour d’interface.

Publication vérifiée : workflow 40310618013 réussi, tag v2.4.2-beta.2 et pointeur latest-beta concordants ; dernière stable toujours v2.4.0. SHA-256 des deux APK téléchargés identique : 2ae4e12a101f8443062b75be9c42bd0550e03b28c15d778036866dfc6d8c1608. Signature de production vérifiée : c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d. Mise à jour réelle sur l’AVD TV API31 depuis 2.4.1-beta.design.1 vers 2.4.2-beta.2 réussie (versionCode 20402). Tentative séparée de réinstallation directe 2.4.1-beta.design.1 → 2.4.0 refusée par Android avec INSTALL_FAILED_VERSION_DOWNGRADE ; aucun contournement forcé. Site conservé localement, sans déploiement.
