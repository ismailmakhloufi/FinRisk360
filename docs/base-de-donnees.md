# FinRisk360 — Base de données

Documentation de la base **`finrisk`** (PostgreSQL 17, conteneur Docker) : pourquoi chaque table existe, comment elle se relie aux autres, et les commandes du quotidien.

---

## 1. Vue d'ensemble

| Élément | Valeur |
|---|---|
| SGBD | PostgreSQL **17.11** (image officielle, conteneur Docker) |
| Base | `finrisk` |
| Rôle applicatif | `finrisk` / `<POSTGRES_PASSWORD>` (droits complets) |
| Superuser | `postgres` / `POSTGRES_ADMIN_PASSWORD` |
| Hôte / port | `localhost:5432` |
| Conteneur | `finrisk-postgres` |
| Volume | `projetservicesweb_finrisk-pgdata` (données persistées) |
| Nombre de tables | **13** |
| Source de vérité | `postgres/02-schema.sql` (versionnée dans le repo) |

Le schéma en étoile du Data Warehouse (`FactTransaction`, `DimCustomer`…) **n'est pas ici** : il est alimenté par l'ETL dans une autre base.

---

## 2. Pourquoi 13 tables ?

Le découpage suit la chaîne de valeur du projet :
**transaction → validation → scoring → décision → alerte → investigation → assistance IA → décision humaine → audit → DW**.

### Bloc A — Identité et comptes (3)

#### `customers` — *qui est concerné*
Le client bancaire, sujet de toutes les mesures de risque.
**Pourquoi** : le Risk Engine compare toujours la transaction courante à **l'historique du client**. Sans entité client, pas de profil comportemental ni de score.
**Lien** : 1:N vers `accounts`, 1:1 vers `risk_profiles`.

#### `accounts` — *sur quel compte*
Comptes détenus par le client (IBAN/numéro, devise, solde, statut).
**Pourquoi** : les transactions portent sur un **compte**, pas directement sur un client — cela permet des règles par compte (solde, type) et l'intégrité référentielle.
**Lien** : N:1 vers `customers`, 1:N vers `transactions`.

#### `users` — *qui utilise la plateforme*
Analystes et administrateurs (`ADMIN`, `ANALYST`, `MANAGER`), hash de mot de passe, rôle, actif/inactif.
**Pourquoi** : c'est la base du **RBAC** (§18 du cahier des charges) et de la traçabilité — toute action doit être attribuable à quelqu'un.
**Lien** : 1:N vers `alerts`, `cases`, `case_comments`, `audit_logs`.

---

### Bloc B — Transactionnel (3)

#### `merchants` — *contre qui*
Référence des marchands (nom, catégorie, pays, ville, **date de création**).
**Pourquoi** : la **RULE-005 « nouveau marchand »** exige de connaître l'ancienneté du marchand ; sans table, la règle est impossible à évaluer et à expliquer.
**Lien** : 1:N vers `transactions` (`merchant_id` NULL pour un virement).

#### `transactions` — *la donnée brute* ⭐ table centrale
Tous les champs reçus de la banque simulée ou du système legacy : montant, devise, type, pays, date, canal, IP, appareil, bénéficiaire (libellé texte), statut.
**Pourquoi** : c'est l'entrée du pipeline **et** la source de vérité pour tous les calculs du Feature Engine (moyennes, percentiles, vélocité). Tout le reste en dérive.
**Lien** : N:1 vers `accounts` et `merchants`, 1:N vers `risk_assessments`.

---

### Bloc C — Risque (4)

#### `risk_profiles` — *mémoire comportementale du client*
Moyenne, médiane, maximum, **P95/P99**, transactions/jour, pays et heure habituels, nombre de marchands, fenêtre de calcul, snapshot JSONB des features.
**Pourquoi** : recalculé à chaque transaction (Feature Engine), c'est le **cache** qui rend le scoring rapide — sinon il faudrait agréger tout l'historique à chaque appel.
**Cardinalité 1:1** avec `customers` (garantie par `UNIQUE(customer_id)`) : un client = un profil, réécrit à chaque opération.
**Lien** : 1:1 vers `customers`.

#### `risk_rules` — *catalogue configurable des règles*
`RULE-001…011`, catégorie, **opérateur de comparaison**, seuil, **poids**, sévérité, activé/désactivé.
**Pourquoi** : les règles doivent être **modifiables sans redéploiement** et **explicables** (§8 : « règles indépendantes et identifiables »). Le schéma de référence initialise les règles avant le premier démarrage.
**Lien** : 1:N vers `risk_factors`.

#### `risk_assessments` — *résultat du scoring, versionné*
Score 0–100, niveau (LOW→CRITICAL), **décision** (APPROVED/MONITORING/PENDING_REVIEW/HOLD), horodatage, version du moteur.
**Pourquoi** : une transaction peut être **recalculée** (nouveau poids, nouveau modèle) → on garde chaque évaluation. C'est aussi la séparation imposée par le doc : le Risk Engine dit *« quel est le niveau de risque »*, le Decision Engine dit *« quelle action appliquer »* — la colonne `decision` porte la réponse métier.
**Lien** : N:1 vers `transactions`, 1:N vers `risk_factors` et `alerts`.

#### `risk_factors` — *pourquoi ce score* 🔑
Le détail chiffré par dimension : AMOUNT, VELOCITY, GEOGRAPHIC, TIME, BEHAVIOR, HISTORY — score, poids appliqué, **raison textuelle**.
**Pourquoi** : c'est ce qui rend l'alerte **explicable** et qui **nourrit l'AI Assistant** (§11.1 « Explain Alert ») et Power BI. Sans cette table, on aurait un nombre sans justification.
**Lien** : N:1 vers `risk_assessments` et `risk_rules` (FK **NULL** : un facteur purement statistique comme l'écart-Z n'est déclenché par aucune règle).

---

### Bloc D — Investigation et gouvernance (4)

#### `alerts` — *signalement*
Code unique (`ALERT-2026-0001`), type, sévérité, statut, **analyste assigné**, création/résolution.
**Pourquoi** : produit par le Decision Engine quand le niveau dépasse le seuil. Le code unique sert de référence citable en réunion et dans les rapports ; `assigned_to` répond à *« qui traite cette alerte ? »* (§10).
**Lien** : N:1 vers `risk_assessments`, 0..1 vers `cases`, N:1 vers `users`.

#### `cases` — *dossier d'investigation*
Statut (OPEN → UNDER_REVIEW → CONFIRMED/FALSE_POSITIVE → CLOSED), priorité, résultat d'enquête, ouverture/clôture.
**Pourquoi** : c'est l'objet de travail de l'analyste et la base du tableau de bord *Investigation* de Power BI (temps de résolution, décisions).
**Cardinalité 0..1** avec `alerts` : une alerte devient au plus un dossier (`UNIQUE(alert_id)`).
**Lien** : N:1 vers `alerts` et `users`, 1:N vers `case_comments`.

#### `case_comments` — *raisonnement de l'enquête*
Fil de commentaires : auteur, contenu, **type** (NOTE / EVIDENCE / DECISION), date.
**Pourquoi** : une investigation se lit comme une conversation. `cases` seul ne garde qu'un statut — on perd le *pourquoi*. C'est aussi la matière première du **rapport généré par l'IA** (§11.5).
**Écart avec `audit_logs`** : ici c'est l'**humain** qui écrit son analyse ; là, le **système** enregistre les actions. Complémentaires, jamais substituables.
**Lien** : N:1 vers `cases` et `users`.

#### `audit_logs` — *preuve d'activité* (append-only)
Qui, quoi, quelle entité, valeurs avant/après, date, IP.
**Pourquoi** : exigence §20 — toute action importante (clôture de dossier, changement de décision) doit être historisée. Jamais modifié ni supprimé.
**Note** : `entity_type` + `entity_id` sont **polymorphes** → pas de clé étrangère possible (parti assumé), indexés sur `(entity_type, entity_id)`.
**Lien** : N:1 vers `users`.

---

## 3. Les relations (15)

| # | Relation | Card. | Raison |
|---|---|---|---|
| 1 | customers → accounts | 1:N | un client a plusieurs comptes |
| 2 | customers → risk_profiles | **1:1** | un seul profil, réécrit à chaque transaction |
| 3 | accounts → transactions | 1:N | historique des opérations |
| 4 | merchants → transactions | 1:N | un marchand encaisse plusieurs ops |
| 5 | transactions → risk_assessments | 1:N | recalculs / versioning du score |
| 6 | risk_assessments → risk_factors | 1:N | 6 dimensions par évaluation |
| 7 | risk_rules → risk_factors | 1:N | une règle peut se déclencher sur N facteurs |
| 8 | risk_assessments → alerts | 1:N | une évaluation peut générer une alerte |
| 9 | alerts → cases | **0..1** | une alerte devient au plus un dossier |
| 10 | cases → case_comments | 1:N | fil de discussion |
| 11 | users → alerts | 1:N | analyste assigné |
| 12 | users → cases | 1:N | analyste assigné |
| 13 | users → case_comments | 1:N | auteur des notes |
| 14 | users → audit_logs | 1:N | auteur des actions |
| 15 | risk_rules → risk_factors | N:1 | facteur rattaché à une règle (nullable) |

---

## 4. Décisions de conception

| Décision | Justification |
|---|---|
| `NUMERIC/DECIMAL` pour montants et scores | jamais de `float` : arrondis monétaires exacts |
| `TIMESTAMPTZ` partout, UTC | fenêtres glissantes (5 min / 1 h / 24 h) et join avec le DW |
| `CHAR(2)` pays, `CHAR(3)` devise | codes ISO → jointures propres avec `DimCountry` |
| Statuts et rôles en `VARCHAR + CHECK` | lisible, vérifié par la base, visible dans un client SQL |
| `created_at` et non `timestamp` | `timestamp` est un mot-clé SQL réservé |
| `audit_logs.entity_id` sans FK | schéma polymorphe : on audite toutes les entités |
| `beneficiary_name` et non `beneficiary_id` | c'est un libellé, pas une clé — ne pas faire mentir le nom |
| `risk_factors.rule_id` NULL | facteur statistique pur (écart-Z) sans règle associée |
| Hibernate en `ddl-auto: validate` | le SQL est la source de vérité, Hibernate contrôle seulement |
| Seed des 6 règles | la démo ne démarre jamais avec un catalogue vide |

---

## 5. Fichiers

```
projet services web/
├── docker-compose.yml              définit le service postgres
├── postgres/
│   ├── 01-init.sql                 rôle finrisk + droits
│   └── 02-schema.sql               13 tables + index + grants + seed
└── docs/
    ├── base-de-donnees.md          ce document
    ├── schema-relationnel.mmd      schéma source (Mermaid)
    ├── schema-relationnel.png      schéma en image
    └── schema-relationnel.svg      schéma vectoriel
```

**Cycle de vie des scripts**. Sur un volume vierge, Docker appelle le script d’initialisation qui construit le schéma de référence puis prépare les bases de développement, de démonstration et de test. Les migrations historiques 04 à 09 restent disponibles pour les anciennes bases.

---

## 6. Commandes

### Mise en route

```powershell
# 1. démarrer Docker Desktop (si fermé)
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
docker info                       # attendre "Server Version"

# 2. lancer PostgreSQL
cd "C:\Users\ismai\OneDrive\Bureau\projet services web"
docker compose up -d

# 3. vérifier
docker compose ps                 # → Up (healthy)
```

### Au quotidien

```powershell
docker compose logs -f            # suivre les logs
docker compose restart            # redémarrer
docker compose stop               # arrêter (données gardées)
docker compose down               # arrêter + supprimer le conteneur (données gardées)
docker compose down -v            # ⚠️ TOUT SUPPRIMER (volume inclus)
```

### Console SQL

```powershell
# superuser
docker exec -it finrisk-postgres psql -U postgres -d finrisk

# rôle applicatif
docker exec -it finrisk-postgres psql -U finrisk -d finrisk
```
Commandes internes : `\dt` (tables) · `\du` (rôles) · `\d transactions` (structure) · `\q` (quitter).

### Requêtes en une ligne

```powershell
docker exec finrisk-postgres psql -U postgres -d finrisk -c "\dt"
docker exec finrisk-postgres psql -U postgres -d finrisk -c "SELECT * FROM risk_rules ORDER BY rule_code;"
docker exec finrisk-postgres psql -U postgres -d finrisk -c "SELECT count(*) FROM transactions;"
```

### Tester la connexion depuis l'extérieur (comme le fera Spring Boot)

```powershell
$env:PGPASSWORD = $env:POSTGRES_PASSWORD
try {
    docker exec -e PGPASSWORD finrisk-postgres psql -h 127.0.0.1 -U finrisk -d finrisk -c "SELECT current_user;"
} finally {
    Remove-Item Env:\PGPASSWORD
}
```

### Réappliquer le schéma après modification de `02-schema.sql`

```powershell
docker compose down -v            # volume neuf → les scripts rejouent
docker compose up -d              # 01-init puis 02-schema
```
⚠️ `down -v` **détruit les données**. Jamais à faire une fois le projet en cours de production de démo.

### Interface web (Adminer)

Un service **Adminer** est défini dans `docker-compose.yml` : c'est la seule façon de voir la base **depuis un navigateur** (PostgreSQL lui-même ne parle pas HTTP, `localhost:5432` ne s'ouvre jamais dans un navigateur).

```powershell
docker compose up -d          # démarre postgres + adminer
start http://localhost:5050   # ouvrir l'interface
```

| Champ Adminer | Valeur |
|---|---|
| System | PostgreSQL |
| Server | `postgres` *(nom du service dans le réseau Docker)* |
| Username | `finrisk` |
| Password | `<POSTGRES_PASSWORD>` |
| Database | `finrisk` |

Le port **5050** est volontaire : la plage **8080+** est réservée aux microservices Spring Boot.

### Config Spring Boot (`application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/finrisk
    username: finrisk
    password: ${POSTGRES_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          time_zone: UTC
```
