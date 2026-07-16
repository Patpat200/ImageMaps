# ImageMaps v2 — pour Paper 26.2

Réécriture complète du plugin ImageMaps original, qui plantait le serveur
(freeze de 30s+, watchdog, NPE) parce qu'il téléchargeait les images
**en HTTP synchrone sur le thread principal du serveur**.

## Ce qui a changé

- **Tout le réseau et le disque se font en arrière-plan** (thread pool
  dédié), jamais sur le thread principal. `render()` ne fait plus qu'une
  lecture mémoire quasi gratuite.
- **Cache disque** (`plugins/ImageMaps/cache/<id>.png`) : une image déjà
  téléchargée avec succès n'est plus jamais re-téléchargée, même après un
  redémarrage du serveur.
- **Timeouts stricts** (connexion 4s / lecture 6s) et **limite de taille**
  (8 Mo max) sur chaque requête, pour ne jamais rester bloqué.
- **Résolution automatique des liens indirects** : Dropbox (force `dl=1`),
  Google Drive (`/file/d/ID/view` → lien de téléchargement direct), et
  pages génériques (Pinterest, ImgBB, Imgur...) via la balise
  `<meta property="og:image">` de la page.
- **Backoff** : en cas d'échec, le plugin réessaie au plus une fois par
  minute pour cet id au lieu de spammer réseau + logs à chaque tick.
- Plus jamais de `canvas.drawImage(x, y, null)` → plus de
  `NullPointerException`.
- Commandes admin : `/imagemap create <url>`, `reload`, `remove <id>`,
  `clearcache [id]`, `list`.
- `data.yml` garde exactement le même format (`ids: '<id>': '<url>'`),
  donc ton fichier existant (corrigé, fourni à côté) fonctionne tel quel.

## Build

### Option A — GitHub Actions (rien à installer)

Un workflow est déjà prêt dans `.github/workflows/build.yml`. Il te suffit de :

1. Créer un repo GitHub (public ou privé) et y pousser ce dossier :
   ```bash
   cd ImageMaps
   git init
   git add .
   git commit -m "ImageMaps v2"
   git branch -M main
   git remote add origin https://github.com/<toi>/<ton-repo>.git
   git push -u origin main
   ```
2. Va dans l'onglet **Actions** de ton repo sur GitHub : le build se lance
   automatiquement à chaque push (et tu peux aussi le relancer à la main
   avec le bouton **"Run workflow"**).
3. Une fois le run terminé (icône verte ✅), ouvre-le et descends jusqu'à
   **Artifacts** en bas de page : télécharge `ImageMaps-jar`, qui contient
   `ImageMaps.jar` prêt à poser dans `plugins/`.
4. Si le build échoue (❌), ouvre le log de l'étape qui a planté et
   colle-moi l'erreur ici, je corrige le code en conséquence.

Le workflow installe automatiquement **JDK 25** (Temurin) sur la machine
GitHub, donc tu n'as rien à installer toi-même.

### Option B — En local

Il te faut **JDK 25** (requis par Paper 26.x) et Maven.

```bash
mvn clean package
```

Le jar buildé se trouve dans `target/ImageMaps.jar`.

> Je n'ai pas pu compiler ce projet moi-même dans mon propre environnement
> (mon bac à sable bloque même Maven Central, sans parler de
> `repo.papermc.io`), d'où l'option GitHub Actions ci-dessus qui, elle,
> a un accès réseau complet. Si la version `[26.2.build,)` du `paper-api`
> ne se résout pas correctement, remplace-la dans `pom.xml` par une
> version fixe (ex: `26.2.build.60-beta`), trouvable sur
> https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/

## Installation

1. `mvn clean package`
2. Copie `target/ImageMaps.jar` dans `plugins/` de ton serveur.
3. Supprime l'ancien `ImageMaps-1.0.jar` s'il est encore présent.
4. Copie le `data.yml` corrigé (fourni à côté de ce README) dans
   `plugins/ImageMaps/data.yml`.
5. Démarre le serveur.
