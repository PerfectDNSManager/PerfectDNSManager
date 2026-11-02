# Libellé du DNS privé DoT dans les paramètres

Lorsque le DNS privé Android est en mode hostname avec un hôte non vide, la ligne de démarrage automatique (libellé et interrupteur) est masquée. Un texte non interactif la remplace : « DoT géré automatiquement par Android ». L’adresse du DNS reste affichée avec une explication de sa conservation après redémarrage. Les 12 traductions sont mises à jour.

Sans DoT configuré, la ligne de démarrage revient avec la préférence VPN précédente inchangée. Les liens haut/bas entre le thème et l’option IPv6 contournent la ligne masquée. Le comportement de BootReceiver et du moteur DNS n’est pas modifié.

Validation : assembleDebug, assembleDebugAndroidTest, 30 tests JVM et lintDebug réussis. Test instrumenté Android TV API31 réussi en 26,363 s : DoT externe sans profil PDM sauvegardé, absence de l’interrupteur, texte affiché, liens de focus adaptés, puis retour de l’interrupteur et préservation du choix après désactivation du DNS privé et reprise de l’écran.

Publication : workflow 41430785845 réussi, tag v2.4.5-beta.1 et latest-beta concordants. SHA-256 des deux APK identique : 39d480a51c16513f08abde4bd5309ef32fc62ec5338270df81ae38328edda3ac. Signature de production inchangée et vérifiée : c7fd0c977b2c8911c32268e3fa15e9fe50b14dccfae18f7232b28a9faa858f6d. Installation de l’APK publié réussie sur Android TV API31, versionCode 20405 / versionName 2.4.5-beta.1. La stable reste v2.4.0.
