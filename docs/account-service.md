# `account-service` — Guide complet

> **Microservice 3/8 de FinRisk360** · port **8082** · propriétaire exclusif de la table **`accounts`** · jalon **M2** ✅
>
> Ce document explique le service de bout en bout : rôle, couches, chaque endpoint, chaque règle métier, les 18 tests, et comment le dépanner.

---

## Sommaire

1. [À quoi sert ce service](#1-à-quoi-sert-ce-service)
2. [Démarrage rapide](#2-démarrage-rapide)
3. [Arborescence commentée](#3-arborescence-commentée)
4. [Les couches](#4-les-couches)
5. [L'entité `Account` et sa correspondance SQL](#5-lentité-account-et-sa-correspondance-sql)
6. [Les 6 endpoints](#6-les-6-endpoints)
7. [Les règles métier](#7-les-règles-métier)
8. [Le format d'erreur](#8-le-format-derreur)
9. [Concurrence et verrouillage des soldes](#9-concurrence-et-verrouillage-des-soldes)
10. [Swagger](#10-swagger)
11. [Les 18 tests](#11-les-18-tests)
12. [Propriété des tables et divergence assumée](#12-propriété-des-tables-et-divergence-assumée)
13. [Dépannage](#13-dépannage)
14. [Historique M2](#14-historique-m2)
15. [Ce qui reste (M2 suite / M3+)](#15-ce-qui-reste-m2-suite--m3)
16. [Points de soutenance](#16-points-de-soutenance)

---

## 1. À quoi sert ce service

Il est **le seul responsable de la table `accounts`** (règle M0 §2) :

| Il fait | Il ne fait pas |
|---|---|
| Créer un compte (numéro **généré par le serveur**) | ❌ lire la table `customers` (elle appartient à `customer-service`) |
| Consulter par **id** ou par **numéro** | ❌ modifier un solde **sans** passer par son endpoint dédié |
| Lister / filtrer les comptes par client | ❌ supprimer un compte (pas de `DELETE`) |
| Geler / clôturer (`FROZEN`, `CLOSED`) | ❌ évaluer le risque (Risk Service) |
| **Créditer / débiter** (verrou `FOR UPDATE`) | |

**Ports** : 8082 · **URL de base** : `/api/accounts` · **Dépendances** : uniquement PostgreSQL.

---

## 2. Démarrage rapide

```powershell
docker compose up -d                     # postgres + adminer (infra)

cd backend\account-service
mvn spring-boot:run                      # ou : mvn -q -DskipTests package
                                         #      java -jar target\account-service-0.0.1-SNAPSHOT.jar

# vérifications
curl.exe -s --noproxy "*" http://127.0.0.1:8082/actuator/health     # {"status":"UP"}
# Swagger     : http://localhost:8082/swagger-ui.html
# Adminer     : http://localhost:5050
# Tests       : mvn test        (18 tests)
```

> `customer-service` (8081) et `account-service` (8082) **peuvent tourner en même temps** : deux ports, une seule base.

---

## 3. Arborescence commentée

```
backend/account-service/
├── pom.xml                        ← Boot 4.1.1 : webmvc, data-jpa, validation,
│                                     actuator, postgresql, springdoc 3.1.1
├── src/main/java/tn/finrisk/accountservice/
│   ├── AccountServiceApplication.java
│   ├── domain/
│   │   ├── Account.java           ← table accounts (8 colonnes)
│   │   └── enums/
│   │       ├── AccountType.java   ← CHECKING | SAVINGS | CARD
│   │       ├── AccountStatus.java ← ACTIVE | FROZEN | CLOSED
│   │       └── BalanceDirection.java ← CREDIT | DEBIT
│   ├── repository/
│   │   └── AccountRepository.java ← requêtes dérivées + verrou pessimiste
│   ├── service/
│   │   └── AccountService.java    ← 6 méthodes, 6 règles, @Transactional
│   └── web/
│       ├── AccountController.java ← 6 endpoints, Swagger, 0 règle
│       ├── dto/
│       │   ├── AccountCreateRequest.java   ← ENTRÉE (5 champs validés)
│       │   ├── AccountResponse.java        ← SORTIE (8 champs)
│       │   └── BalanceOperationRequest.java ← ENTRÉE solde (2 champs)
│       ├── mapper/
│       │   └── AccountMapper.java          ← entité ⇄ DTO
│       └── error/
│           ├── AccountNotFoundException.java          → 404
│           ├── AccountNumberAlreadyExistsException.java → 409
│           ├── AccountNotOperableException.java        → 409
│           ├── InsufficientBalanceException.java       → 409
│           ├── ErrorResponse.java                      ← format unique
│           └── GlobalExceptionHandler.java             ← 9 @ExceptionHandler
├── src/main/resources/application.yml      ← port 8082
└── src/test/                               ← 18 tests
    ├── resources/application-test.yml      ← base finrisk_test
    └── java/tn/finrisk/accountservice/
        ├── AccountServiceApplicationTests.java   (1, contexte)
        ├── service/AccountServiceTest.java       (7, unitaires)
        ├── service/AccountServiceIntegrationTest.java (2, PostgreSQL)
        └── web/AccountControllerTest.java        (8, MockMvc)
```

---

## 4. Les couches

```
   Client (Swagger / curl / futur Angular)
                │
                ▼
   ┌────────────────────────────────┐
   │ web/AccountController          │  routage + validation @Valid + codes HTTP
   └────────────────────────────────┘
                │ AccountCreateRequest / BalanceOperationRequest
                ▼
   ┌────────────────────────────────┐
   │ web/mapper + web/dto           │  filtre d'entrée/sortie (contrat API)
   └────────────────────────────────┘
                ▼
   ┌────────────────────────────────┐
   │ service/AccountService         │  RÈGLES : numéro unique, compte actif,
   │ @Transactional(readOnly=true)  │  solde négatif interdit, verrou FOR UPDATE
   └────────────────────────────────┘
                ▼
   ┌────────────────────────────────┐
   │ repository/AccountRepository   │  SQL généré + @Lock(PESSIMISTIC_WRITE)
   └────────────────────────────────┘
                ▼
          PostgreSQL · table accounts
```

---

## 5. L'entité `Account` et sa correspondance SQL

| Colonne SQL | Attribut Java | Annotation clé |
|---|---|---|
| `account_id BIGSERIAL PK` | `Long accountId` | `@Id @GeneratedValue(IDENTITY)` |
| **`customer_id BIGINT NOT NULL`** | **`Long customerId`** | ⭐ **`Long` brut, pas une entité** (voir §12) |
| `account_number VARCHAR(20) UNIQUE` | `String accountNumber` | `unique = true` |
| `account_type VARCHAR(20) DEFAULT 'CHECKING'` | `AccountType` | `@Enumerated(STRING)` + init `CHECKING` |
| `currency CHAR(3) DEFAULT 'TND'` | `String currency = "TND"` | **`@JdbcTypeCode(SqlTypes.CHAR)`** |
| `balance DECIMAL(15,2) DEFAULT 0` | `BigDecimal balance` | `precision = 15, scale = 2` |
| `opening_date DATE DEFAULT CURRENT_DATE` | `LocalDate` | `@PrePersist` |
| `status VARCHAR(20) DEFAULT 'ACTIVE'` | `AccountStatus` | `@Enumerated(STRING)` + init `ACTIVE` |

**`@PrePersist`** reproduit les `DEFAULT` SQL côté Java : date du jour, solde 0, devise `TND`.
**`ddl-auto: validate`** refuse le démarrage si l'une de ces colonnes diffère (testé : le service démarre ✅).

---

## 6. Les 6 endpoints

| # | Méthode | URL | Corps / params | Succès | Erreurs |
|---|---|---|---|---|---|
| 1 | **GET** | `/api/accounts?page&size&sort&customerId` | — | **200** `Page` | 400 |
| 2 | **GET** | `/api/accounts/{id}` | — | **200** | 400, **404** |
| 3 | **GET** | `/api/accounts/number/{number}` | — | **200** | **404** |
| 4 | **POST** | `/api/accounts` | `AccountCreateRequest` | **201** + `Location` | **400**, **409** |
| 5 | **PATCH** | `/api/accounts/{id}/status?status=X` | — | **200** | 400, 404, **409** |
| 6 | **PATCH** | `/api/accounts/{id}/balance` | `BalanceOperationRequest` | **200** nouveau solde | 400, 404, **409** |

### 6.1 `POST /api/accounts` — création

```json
{ "customerId": 1, "accountType": "CHECKING", "currency": "tnd", "openingBalance": 1500.50 }
```
```json
HTTP/1.1 201 Created
Location: /api/accounts/1
{ "accountId": 1, "customerId": 1, "accountNumber": "957398324794",
  "accountType": "CHECKING", "currency": "TND", "balance": 1500.50,
  "openingDate": "2026-10-05", "status": "ACTIVE" }
```
- `accountNumber` **omis** → généré : 12 chiffres, vérifié en base
- `currency: "tnd"` → normalisé **`TND`** (majuscules)
- `customerId` **inexistant** → l'INSERT viole la FK → **409** « contrainte d'intégrité »

### 6.2 `GET /api/accounts/number/{number}`
Recherche unique sur `account_number` → 404 si inconnu.

### 6.3 `PATCH /api/accounts/{id}/status`
`ACTIVE` → `FROZEN` (gelé, plus aucune opération de solde) → `CLOSED` (**définitif**).

### 6.4 `PATCH /api/accounts/{id}/balance` ⭐

```json
{ "amount": 200.25, "direction": "CREDIT" }
→ 200 { ..., "balance": 200.25 }

{ "amount": 999999, "direction": "DEBIT" }
→ 409 { "message": "Solde insuffisant sur le compte TN999999999999" }

{ "amount": -1, "direction": "IN" }
→ 400 { "message": "Corps JSON invalide ou illisible" }
```

---

## 7. Les règles métier

| # | Règle | Implémentation | Violation → |
|---|---|---|---|
| **R1** | Le numéro de compte est **unique** et **généré par le serveur** (12 chiffres) | `generateAccountNumber()` + `assertNumberIsFree()` | 409 `AccountNumberAlreadyExistsException` |
| **R2** | Un compte doit exister | `getAccountOrThrow()` | 404 `AccountNotFoundException` |
| **R3** | **Aucune opération de solde** si `FROZEN` ou `CLOSED` | contrôle `status != ACTIVE` | 409 `AccountNotOperableException` |
| **R4** | **CLOSED est définitif** : ni réouverture, ni autre changement | contrôle `status == CLOSED` dans `changeStatus` | 409 |
| **R5** | Un **DEBIT** ne peut jamais rendre le solde négatif | `balance.compareTo(amount) < 0` | 409 `InsufficientBalanceException` |
| **R6** | Pas de `DELETE` dur — on passe en `CLOSED` | aucun `delete()` exposé | — |
| **R7** | La devise est normalisée `TND` (majuscules) | `AccountMapper.toEntity` | — |
| **R8** | Le **client doit exister** (FK) | PostgreSQL `fk_accounts_customer` | 409 (integrité) |

**Double barrière** (comme l'email en M1) : contrôle applicatif → message lisible 409 **+** contrainte `UNIQUE`/`FK` en base → filet `DataIntegrityViolationException` → aussi 409.

---

## 8. Le format d'erreur

Identique à `customer-service` (contrainte M0) : `{timestamp, status, message, path, errors[]}`.

| Exception | HTTP |
|---|---|
| `AccountNotFoundException` | 404 |
| `AccountNumberAlreadyExistsException` | 409 |
| `AccountNotOperableException` | 409 |
| `InsufficientBalanceException` | 409 |
| `DataIntegrityViolationException` (FK/UNIQUE) | 409 |
| `MethodArgumentNotValidException` (Bean Validation) | 400 + `errors[]` |
| `MethodArgumentTypeMismatchException` (`/abc`, `?status=FOO`) | 400 |
| `HttpMessageNotReadableException` (JSON/enum invalide) | 400 |
| `MissingServletRequestParameterException` | 400 |
| `Exception` (le reste) | **500** + stacktrace **serveur seulement** |

---

## 9. Concurrence et verrouillage des soldes

C'est le point technique le plus important du service (et la réponse au débat Kafka/REST).

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Account> findWithLockByAccountId(Long accountId);
```

```
Thread A : DEBIT 800                  Thread B : DEBIT 800
  SELECT ... FOR UPDATE  ← verrou       attend le verrou ⏳
  lit balance = 1000
  écrit 200
  COMMIT → verrou libéré                reprend : relit balance = 200
                                          → DEBIT refusé (solde insuffisant) ✅
```

- **Sans verrou** : les deux threads lisent 1000 → soldes corrompus
- **Avec `FOR UPDATE`** : PostgreSQL **sérialise** les écritures → l'intégrité est garantie **par la base**, pas par du code applicatif
- Le tout est dans `@Transactional` : soit tout est commité, soit rien

> Ce mécanisme est **exactement** ce qu'on répond quand on nous demande *« et si on a beaucoup de transactions en même temps ? »* : c'est la **base** (verrou + ACID) qui protège les soldes, pas un bus de messages.

---

## 10. Swagger

- UI : `http://localhost:8082/swagger-ui.html` — groupe **`Accounts`**
- JSON : `http://localhost:8082/v3/api-docs` (OpenAPI 3.1)

Annotations : `@Tag`, `@Operation(summary, description)`, `@ApiResponses`, `@Parameter`.

---

## 11. Les 18 tests

```powershell
mvn test
# Tests run: 18, Failures: 0, Errors: 0  →  BUILD SUCCESS
```

| Classe | Nb | Vérifie |
|---|---|---|
| `AccountServiceTest` | **7** | numéro généré (12 chiffres, devise `TND`, `ACTIVE`) · doublon → exception **sans `save()`** · 404 · DEBIT > solde → exception **et solde inchangé** · `FROZEN` refusé · CREDIT → nouveau solde · `CLOSED` intouchable |
| `AccountControllerTest` | **8** | 200 page · **201 + Location** · **400 + `errors[]`** · **404 formaté** · **409** · `/abc` → 400 · **200 nouveau solde** · **409 solde insuffisant** |
| `AccountServiceIntegrationTest` | **2** | INSERT+SELECT réels dans `finrisk_test` (avec insertion FK du client via `JdbcTemplate`) · doublon → exception · filtre `customerId` · **ROLLBACK** en fin de test |
| `AccountServiceApplicationTests` | **1** | contexte complet + `ddl-auto: validate` sur `finrisk_test` |

**Les tests ne touchent jamais `finrisk`** : `@ActiveProfiles("test")` → base `finrisk_test`.

---

## 12. Propriété des tables et divergence assumée

| Service | Écrit | Lit |
|---|---|---|
| `account-service` | **`accounts`** | `accounts` (et rien d'autre) |
| `customer-service` | `customers`, `risk_profiles` | **`accounts` en lecture seule** (`GET /api/customers/{id}/accounts`) |

**Divergence documentée** : M0 dit « un service ne lit les autres que par API ». En M1, `customer-service` a reçu `Account` + `AccountRepository` **en lecture seule** pour servir la sous-ressource `/api/customers/{id}/accounts`.

- **Conséquence** : `customer-service` ne **modifie** jamais `accounts` — l'écriture est exclusive à `account-service`
- **Alternative non retenue** : `customer-service` appellerait `GET /api/accounts?customerId=` → un appel inter-service de plus pour un gain nul aujourd'hui
- **À dire en soutenance** : *« ownership = écriture exclusive ; la lecture est un compromis assumé pour éviter un couplage réseau non nécessaire »*

`customer_id` en `Long` (et non `@ManyToOne Customer`) respecte l'interdit de `JOIN` sur les tables d'un autre service.

---

## 13. Dépannage

| Symptôme | Cause | Correction |
|---|---|---|
| `No property 'id' found for type 'Account'` | méthode dérivée `...ById` alors que le champ s'appelle `accountId` | renommer `findWithLockByAccountId` |
| `409` sur `POST` avec un client qui existe | customer_id pointe vers un id absent de `customers` (ex. id 999) | vérifier `SELECT customer_id FROM customers` |
| Id « sauté » dans la séquence | un INSERT a échoué (FK) : `BIGSERIAL` a déjà consommé la valeur | normal, aucun impact |
| `400 Corps JSON invalide` | enum inconnue (`"direction":"IN"`) ou guillemets cassés par PowerShell | écrire le JSON **dans un fichier** puis `--data-binary "@file.json"` |
| Port 8082 occupé | ancien `java -jar` | tuer le PID de `Temp\opencode\m2\pid.txt` |
| `Schema validation: missing/different column` | entité ≠ table | aligner l'entité, jamais la base |
| Tests sur la mauvaise base | `@ActiveProfiles("test")` oublié | l'ajouter sur la classe |

---

## 14. Historique M2

| Étape | Contenu | Commit |
|---|---|---|
| 1-2 | Squelette Initializr + `pom` corrigé + `application.yml` (8082) | `c9b2a89` |
| 3 | Entité `Account` + 2 enums (`validate` OK) | `440842f` |
| 4-8 | Repository (verrou inclus), DTO, mapper, `AccountService` (6 règles), 6 endpoints, `GlobalExceptionHandler` | `699d0fa` |
| 9 | Swagger + **18 tests** | `28da54a` |
| 10 | Cette documentation | (ce commit) |

**Erreurs rencontrées** (matière pour le rapport) :
1. `findWithLockById` → Spring Data ne trouvait pas de propriété `id` → renommé `findWithLockByAccountId`
2. Générateur de numéro : modulo `10^13` au lieu de `10^12` → 13 chiffres au lieu de 12
3. JSON inline cassé par les guillemets PowerShell → payloads en fichier
4. Saut de séquence `BIGSERIAL` après un INSERT FK échoué (explication pédagogique)

---

## 15. Ce qui reste (M2 suite / M3+)

| Jalon | Sur ce service |
|---|---|
| **M2** (suite) | **`transaction-service`** (8083) : REST **+ endpoint SOAP/XML**, persistance dans `transactions` |
| **M3** | Risk Service appellera `GET /api/accounts/{id}` pour évaluer |
| **M4** | alert-case-service lit les comptes concernés |
| **M5** | JWT : `POST /api/accounts` réservé aux rôles autorisés |
| **M8** | `Dockerfile` + port 8082 exposé dans le `docker-compose.yml` |

À venir **sur ce service** si besoin : `GET /api/accounts?status=`, plafonds, intérêts (`SAVINGS`), endpoint de transfert.

---

## 16. Points de soutenance

1. **Pourquoi `customerId` en `Long` et non `@ManyToOne Customer` ?** → règle M0 : pas de `JOIN` sur la table d'un autre service ; la FK est garantie par PostgreSQL.
2. **Qui génère le numéro de compte ?** → le **serveur** (12 chiffres, unicité vérifiée) : le client ne peut pas choisir un numéro existant.
3. **Que se passe-t-il si deux débits partent en même temps ?** → `SELECT ... FOR UPDATE` sérialise les écritures : l'intégrité est assurée par la base (et non par du code).
4. **Pourquoi 4 exceptions différentes ?** → chaque cas métier a un code HTTP précis : 404 (inconnu), 409 (conflit / solde / état) — jamais de 500.
5. **CLOSED est-il réversible ?** → non, c'est une règle métier explicite (`AccountNotOperableException`).
6. **Pourquoi pas de `DELETE` ?** → `ON DELETE RESTRICT` + traçabilité : un compte est clôturé, jamais effacé.
7. **18 tests, quel mix ?** → 7 unitaires (logique, 0,6 s) + 8 HTTP simulés (contrat, 1,7 s) + 2 intégration (SQL réel, 0,9 s) + 1 contexte (7,2 s).
