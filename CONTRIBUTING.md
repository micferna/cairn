# Contribuer à Cairn

Merci de l'intérêt. Quelques règles, courtes mais fermes.

## La règle qui prime sur toutes les autres

**Aucune contribution ne peut ajouter la permission `INTERNET`,
`ACCESS_NETWORK_STATE`, `ACCESS_BACKGROUND_LOCATION`, ni faire atteindre le
disque à une coordonnée géographique.**

Ce n'est pas négociable au cas par cas : c'est le produit. Une fonctionnalité
qui l'exige n'a pas sa place ici, quelle que soit son utilité. La CI refuse
d'ailleurs toute build dont l'APK contiendrait une permission réseau.

Si vous pensez tenir une exception qui mérite discussion, ouvrez une issue
avant d'écrire du code — pas une pull request.

## Avant d'ouvrir une pull request

```bash
./gradlew detekt lintDebug lintRelease assembleDebug assembleRelease
```

Tout doit passer. Deux précisions :

- **Aucune règle d'analyse n'est désactivée** dans ce dépôt, et le seuil
  d'échec de detekt est à zéro problème. Si l'analyse signale votre code,
  corrigez le code. Une PR qui ajoute une exclusion de règle, un
  `@Suppress` ou un `//noinspection` sera refusée sauf justification écrite
  dans la description.
- **Android Lint tourne en `warningsAsErrors`**, sur debug *et* release.

## Style

- Kotlin officiel, 120 colonnes.
- Les commentaires expliquent **pourquoi**, pas quoi. Sur ce projet, la raison
  d'être d'un choix de conception est souvent une garantie de confidentialité :
  écrivez-la, elle empêchera quelqu'un de défaire la protection par
  inadvertance dans six mois.
- Les fonctions `@Composable` en PascalCase, découpées : un panneau, une
  fonction.
- Pas de nombre nu dans le code : une constante nommée, ou un argument nommé.

## Dépendances

La liste actuelle tient en six lignes et se limite à AndroidX et à la
bibliothèque standard Kotlin. Toute nouvelle dépendance doit être justifiée
dans la PR, et sera examinée d'abord sous l'angle « que peut-elle faire à
l'exécution ? ». Une bibliothèque disposant d'un client réseau ne rentrera
pas, même inutilisée.

## Traductions

Les chaînes sont en français dans `app/src/main/res/values/strings.xml`, et
l'interface contient encore beaucoup de texte en dur. Une PR qui extrait ces
chaînes puis ajoute une locale est très bienvenue.

## Licence

En contribuant, vous acceptez que votre code soit distribué sous GPL-3.0.
