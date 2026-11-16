# Calendrier congés 2026–2035

Calendrier de congés (cycle matin / après-midi), en page web et en appli Android.
Charte couleur reprise de GOATout (noir chaud, or, cuivre ; thème clair crème).

- `www/index.html` : l'appli (fonctionne aussi seule dans un navigateur).
- `android/` : appli Android (WebView) qui embarque `www/`.
- `.github/workflows/apk.yml` : compile l'APK à chaque modification et le publie dans **Releases**.
- `reference/` : la version 1 d'origine (HTML).
- `tests/regles.test.cjs` : tests des règles (`node tests/regles.test.cjs`, Playwright).

## Installer l'APK
1. Sur le téléphone, ouvrir la page **Releases** du dépôt et télécharger `calendrier-conges.apk`.
2. L'ouvrir et autoriser « installer des applis inconnues » pour le navigateur si Android le demande.

## Reprendre les données de la version 1
1. Dans l'ancienne page HTML : bouton **Exporter** → fichier `.json`.
2. Dans l'appli : bouton **Importer** → choisir ce fichier.

## Règles
- Cycle : semaine du 29/12/2025 = matin (lun-jeu), semaine suivante = après-midi (lun-ven) ; 1er janvier et 31 décembre en repos.
- Soldes de départ : CPP 19, CP5 4, CSUP 4,5, CFE 0,5, CFR 1, CAN 3, CETC 5,03 (2026).
- CET : en fin d'année, les restes de CSUP, CFE, CFR, CAN et CETC sont versés au CETC de l'année suivante (CPP, CP5 et RECUP n'alimentent pas le CET). Recalculé à chaque modification.
- Fractionnement : 1 CFR dès janvier ; un 2e au 1er novembre si au moins 4,5 CPP sont posés entre le 1er janvier et le 30 avril. Il ne peut pas être posé avant novembre.
- Fériés : 2 chômés maximum ; « + RECUP » sur un férié travaillé donne 1 RECUP, à poser un jour seul, après le férié, dans la même année.
- Un seul congé par an sur un jour férié, obligatoirement un CPP (pas sur un férié marqué « + RECUP » ou « Chômé »).

La clé de signature (`android/app/calendrier.keystore`) est dans le dépôt pour que chaque mise à jour s'installe par-dessus la précédente.
