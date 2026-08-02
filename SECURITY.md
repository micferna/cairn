# Politique de sécurité

## Signaler une vulnérabilité

Utilisez le **signalement privé de vulnérabilité** de GitHub :
onglet *Security* → *Report a vulnerability*. Le rapport reste confidentiel
jusqu'au correctif.

N'ouvrez pas d'issue publique pour une faille exploitable.

## Ce qui compte comme vulnérabilité sur ce projet

Cairn a une surface d'attaque volontairement minuscule : pas de réseau, pas de
compte, pas de dépendance tierce hors AndroidX. Les rapports les plus utiles
portent donc sur les garanties du produit plutôt que sur des CVE classiques.

**Sévérité maximale — une garantie est cassée :**

- une coordonnée géographique, sous quelque forme que ce soit, atteint le
  disque, un journal, une notification ou un export ;
- une permission réseau apparaît dans l'APK publié (la CI l'interdit, mais un
  contournement du contrôle serait grave) ;
- une forme anonymisée redevient localisable — par exemple si la rotation
  aléatoire, la perte d'échelle ou le bruit se révélaient insuffisants pour
  empêcher un recalage sur un réseau routier ;
- des données survivent à « Tout effacer » ou à la désinstallation ;
- une sauvegarde système emporte des données malgré `allowBackup="false"`.

**Sévérité élevée :**

- fuite via un canal auxiliaire : contenu de notification, `logcat`,
  interface exportée, `content://` accessible à une autre application ;
- CVE dans une dépendance embarquée.

## Versions suivies

Seule la dernière version publiée reçoit des correctifs. Le projet vise en
permanence les dernières versions stables de sa chaîne de compilation.

## Vérifier vous-même

La garantie centrale se contrôle en cinq secondes, sans nous faire confiance :

```bash
aapt dump permissions cairn.apk | grep -iE 'INTERNET|NETWORK'
```

Aucune sortie attendue. Si cette commande renvoie quelque chose, considérez
qu'il s'agit d'une vulnérabilité de sévérité maximale et signalez-la.
