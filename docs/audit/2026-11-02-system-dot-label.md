# Libellé du DNS privé DoT dans les paramètres

Lorsque le DNS privé Android est en mode hostname avec un hôte non vide, la ligne de démarrage automatique (libellé et interrupteur) est masquée. Un texte non interactif la remplace : « DoT géré automatiquement par Android ». L’adresse du DNS reste affichée avec une explication de sa conservation après redémarrage. Les 12 traductions sont mises à jour.

Sans DoT configuré, la ligne de démarrage revient avec la préférence VPN précédente inchangée. Les liens haut/bas entre le thème et l’option IPv6 contournent la ligne masquée. Le comportement de BootReceiver et du moteur DNS n’est pas modifié.

Validation : assembleDebug, assembleDebugAndroidTest, 30 tests JVM et lintDebug réussis. Test instrumenté Android TV API31 réussi en 26,363 s : DoT externe sans profil PDM sauvegardé, absence de l’interrupteur, texte affiché, liens de focus adaptés, puis retour de l’interrupteur et préservation du choix après désactivation du DNS privé et reprise de l’écran.
