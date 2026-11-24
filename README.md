# FinRisk360

FinRisk360 est une plateforme de surveillance du risque transactionnel. Elle relie le simulateur bancaire **Atlas Bank** à une console d’investigation pour suivre une opération depuis sa création jusqu’à la décision de l’analyste.

## Fonctionnalités

- **Atlas Bank** simule la création de clients, les virements et les retraits.
- **Moteur de risque** calcule un score explicable à partir de règles et produit les facteurs qui ont contribué à l’évaluation.
- **Alertes et enquêtes** regroupent les alertes apparentées et journalisent les décisions.
- **Constellation** présente le trajet de l’opération, les indices et les comptes liés. Les preuves associent les rapprochements à des transactions consultables. Ollama peut proposer des pistes d’analyse sourcées, avec une lecture par règles en cas d’indisponibilité.
- **Agent de nuit** balaie les opérations en attente de 17 h à 8 h, heure de Tunis. Avec la validation automatique activée, il valide uniquement les cas de risque faible ou moyen avec avis IA normal. Le rapport de nuit conserve son action et son motif.
- **Notification SMS** fonctionne en mode pilote avec une file, un relais iPhone via Tailscale et un e-mail de déclenchement. La réception du SMS en mode test a été vérifiée. Le dernier essai déclenché directement par une alerte de fraude reste à réaliser.

Le dossier `etl/` est réservé à une extension ETL et Power BI.

## Architecture

| Composant | Technologie | Adresse locale |
| --- | --- | --- |
| Console d’investigation | Angular et TypeScript | `http://localhost:4200` |
| Simulateur Atlas Bank | Python, Flask et SQLite | `http://127.0.0.1:5000` |
| Passerelle | Java 21, Spring Boot, REST et authentification JWT | `http://localhost:8080` |
| Services métier | Sept microservices Spring Boot. Les transactions disposent aussi d’un endpoint SOAP. | `127.0.0.1:8081–8087` |
| Stockage applicatif | PostgreSQL 17 via Docker Compose | `127.0.0.1:5432` |
| Administration PostgreSQL | Adminer | `http://localhost:5050` |

## Démarrage local sous Windows

Préparer Java 21, Maven, Node.js 22, Docker Desktop et Python 3.10 ou ultérieur pour le simulateur. Ollama est facultatif pour enrichir le texte de la Constellation.

1. Copier [`.env.example`](.env.example) vers `.env` et renseigner chaque valeur. Choisir une clé JWT aléatoire d’au moins 32 octets et un mot de passe d’amorçage administrateur de 16 caractères minimum et 72 octets maximum. Conserver les valeurs locales. [Configuration des secrets](docs/deploiement-secrets-jwt.md)
2. Depuis la racine du dépôt, lancer les commandes ci-dessous. Le chargement du fichier `.env` injecte aussi les valeurs dans les processus Java démarrés depuis cette session PowerShell. Utiliser le format `NOM=valeur` sans guillemets.

   ```powershell
   foreach ($line in Get-Content .env) {
       if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
           [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
       }
   }
   docker compose up -d --wait

   foreach ($service in Get-ChildItem backend -Directory) {
       $pom = Join-Path $service.FullName 'pom.xml'
       if (Test-Path $pom) {
           mvn -q -f $pom '-DskipTests' package
           if ($LASTEXITCODE -ne 0) { throw "Compilation interrompue pour $($service.Name)." }
       }
   }

   npm.cmd --prefix frontend ci
   .\Start-Dev.ps1
   ```

3. Ouvrir la console et utiliser `admin` avec le mot de passe d’amorçage configuré lors de la première installation. Depuis **Alertes**, ouvrir une alerte puis **Constellation réseau**. Le rapport de nuit est accessible depuis le menu.

Le premier démarrage avec un volume vierge prépare `finrisk`, `finrisk_test` et `finrisk_demo`. Pour lancer le jeu de démonstration, utiliser `.\Start-Demo.ps1` depuis la même session. Les deux scripts redémarrent les services Java sur les ports 8080 à 8087. Pour un volume existant, suivre la procédure de migration adaptée à sa version. [Base de données et mises à niveau](docs/deploiement-secrets-jwt.md)

### Simulateur Atlas Bank

Créer un utilisateur avec le rôle `BANK_SIMULATOR` dans la base utilisée par FinRisk360. Fournir ses identifiants via `FINRISK_USER` et `FINRISK_PASSWORD`. Configurer également `ATLAS_SECRET_KEY` avec une clé distincte d’au moins 32 octets et `ATLAS_ADMIN_PASSWORD` avec au moins 12 caractères pour le premier démarrage local.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r bank-simulator/requirements.txt
.\.venv\Scripts\python.exe bank-simulator/app.py
```

L’identifiant administrateur local d’Atlas Bank est `admin`. Son mot de passe
d’amorçage relève de `ATLAS_ADMIN_PASSWORD`. Les clients et marchands peuvent
utiliser leur identifiant Atlas Bank, leur e-mail ou leur numéro. Si deux
comptes partagent un même numéro ou e-mail et le même mot de passe, utiliser
l’identifiant explicite `client.<id>` ou `merchant.<id>`.

### Vérifications

```powershell
# Dans la session qui a chargé POSTGRES_PASSWORD
mvn -f backend/transaction-service/pom.xml test
mvn -f backend/alert-case-service/pom.xml test
mvn -f backend/ai-assistant-service/pom.xml test
npm.cmd --prefix frontend run build
```

Les tests d’intégration métier utilisent `finrisk_test`. Les autres modules Maven se testent avec la même commande en ciblant leur `pom.xml`.

## Documentation

- [Architecture des services](docs/architecture-services.md)
- [Modèle relationnel](docs/schema-relationnel.png)
- [Secrets, comptes et migration PostgreSQL](docs/deploiement-secrets-jwt.md)
- [Reprise et réconciliation des mouvements financiers](docs/reconciliation-financiere.md)
- [Configuration et essai du relais SMS sur iPhone](docs/sms-relay-iphone.md)
