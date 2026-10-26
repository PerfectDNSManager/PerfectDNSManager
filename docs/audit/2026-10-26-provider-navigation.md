# Navigation télécommande dans le catalogue DNS

La recherche géométrique du focus pouvait passer du bouton fournisseur au bouton profils d’une autre ligne, en fonction de leur largeur. ProviderLayoutManager impose désormais le même identifiant de colonne pour haut/bas. Si la ligne suivante n’est pas attachée, elle est affichée puis reçoit le focus après le layout. Haut depuis la première ligne rejoint la barre d’actions ; bas depuis la dernière ligne conserve le focus. Gauche/droite reste géré par les liens horizontaux existants.

Validation : test instrumenté Android TV API31 avec tous les protocoles et profils Adblock affichés ; parcours complet en descendant côté fournisseur, passage droite/gauche sur chaque ligne, remontée côté profils, vérification des positions, recyclage hors écran et sortie vers la barre d’actions. Test réussi en 11,351 s. 30 tests JVM, assembleDebug, assembleDebugAndroidTest et lintDebug réussis. Aucun changement du moteur DNS ou du design.
