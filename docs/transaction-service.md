# `transaction-service` — Guide complet

> **Microservice 4/8 de FinRisk360** · port **8083** · propriétaire exclusif des tables **`transactions`** et **`merchants`** · jalon **M2** ✅
>
> Ce document explique le service de bout en bout : rôle, couches, chaque endpoint, les règles métier, la **façade SOAP/XML**, les 30 tests, et le détail des problèmes rencontrés.

---

## Sommaire

1. [À quoi sert ce service](#1-à-quoi-sert-ce-service)
2. [Démarrage rapide](#2-démarrage-rapide)
3. [Arborescence commentée](#3-arborescence-commentée)
4. [Les couches et les deux façades](#4-les-couches-et-les-deux-façades)
5. [Les entités et leur correspondance SQL](#5-les-entités-et-leur-correspondance-sql)
6. [Les 7 endpoints REST + 1 opération SOAP](#6-les-7-endpoints-rest--1-opération-soap)
7. [Les règles métier](#7-les-règles-métier)
8. [Le format d'erreur REST et le SOAP Fault](#8-le-format-derreur-rest-et-le-soap-fault)
9. [La façade SOAP/XML en détail](#9-la-façade-soapxml-en-détail)
10. [Swagger](#10-swagger)
11. [Les 30 tests](#11-les-30-tests)
12. [Propriété des tables](#12-propriété-des-tables)
13. [Dépannage](#13-dépannage)
14. [Historique M2](#14-historique-m2)
15. [Ce qui reste (M3+)](#15-ce-qui-reste-m3)
16. [Points de soutenance](#16-points-de-soutenance)

---

## 1. À quoi sert ce service

Il est **le seul responsable des tables `transactions` et `merchants`** (règle M0 §2) :

| Il fait | Il ne fait pas |
|---|---|
| Enregistrer une transaction (REST **ou** SOAP) | ❌ lire la table `accounts` (elle appartient à `account-service`) |
| Consulter une transaction (liste paginée, filtres) | ❌ modifier un montant (un montant est un fait historique) |
| Changer le **statut** (`PENDING → COMPLETED/FAILED/HELD`) | ❌ évaluer le risque (c'est le Risk Service, M3) |
| Gérer les **marchands** (table `merchants`) | ❌ supprimer une transaction (pas de `DELETE`) |
| Exposer le tout en **SOAP/XML** pour les partenaires legacy | |

**Ports** : 8083 · **URLs** : `/api/transactions`, `/api/merchants` (REST) et `/ws` (SOAP) · **Dépendances** : uniquement PostgreSQL.

---

## 2. Démarrage rapide

```powershell
docker compose up -d                     # postgres + adminer (infra)

cd backend\transaction-service
mvn spring-boot:run                      # ou : mvn -q -DskipTests package
                                         #      java -jar target\transaction-service-0.0.1-SNAPSHOT.jar

# vérifications
curl.exe -s http://127.0.0.1:8083/actuator/health            # {"status":"UP"}
curl.exe -s http://127.0.0.1:8083/api/transactions           # page JSON
curl.exe -s http://127.0.0.1:8083/ws/transactionWsdlDefinition.wsdl   # WSDL

# Swagger     : http://localhost:8083/swagger-ui.html
# Adminer     : http://localhost:5050
# Tests       : mvn test        (30 tests)
```

> `customer-service` (8081), `account-service` (8082) et `transaction-service` (8083) **tournent ensemble** : trois ports, une seule base.

---

## 3. Arborescence commentée

```
backend/transaction-service/
├── pom.xml                        ← Boot 4.1.1 : webmvc (REST), webservices (SOAP),
│                                     wsdl4j, data-jpa, validation, actuator,
│                                     postgresql, springdoc 3.1.1
├── src/main/resources/
│   ├── application.yml            ← port 8083
│   └── xsd/transaction.xsd        ← LE CONTRAT SOAP (validé à l'entrée et à la sortie)
├── src/main/java/tn/finrisk/transactionservice/
│   ├── TransactionServiceApplication.java
│   ├── config/
│   │   └── WebServiceConfig.java  ← @EnableWs, servlet /ws/*, WSDL auto, validateur XSD
│   ├── domain/
│   │   ├── Transaction.java       ← table transactions (15 colonnes)
│   │   ├── Merchant.java          ← table merchants (7 colonnes)
│   │   └── enums/                 ← TransactionType | TransactionStatus |
│   │                                 TransactionChannel | MerchantStatus
│   ├── repository/
│   │   ├── TransactionRepository.java  ← requêtes dérivées (filtres accountId/status)
│   │   └── MerchantRepository.java     ← existsByMerchantNameIgnoreCase
│   ├── service/
│   │   ├── TransactionService.java     ← 5 règles, @Transactional
│   │   └── MerchantService.java        ← unicité du nom
│   └── web/
│       ├── TransactionController.java  ← 4 endpoints REST, 0 règle
│       ├── MerchantController.java     ← 3 endpoints REST, 0 règle
│       ├── dto/          ← 4 records + Bean Validation
│       ├── mapper/       ← entité ⇄ DTO (trim, majuscules)
│       ├── error/        ← 4 exceptions métier + ErrorResponse + GlobalExceptionHandler
│       └── soap/
│           ├── TransactionSoapEndpoint.java     ← @Endpoint, délègue au même service
│           ├── CreateTransactionRequest.java    ← miroir XML du DTO (JAXB)
│           ├── CreateTransactionResponse.java
│           └── package-info.java                ← namespace XML partagé
└── src/test/                               ← 30 tests
    ├── resources/application-test.yml      ← base finrisk_test
    └── java/tn/finrisk/transactionservice/
        ├── TransactionServiceApplicationTests.java   (1, contexte)
        ├── service/TransactionServiceTest.java       (7, unitaires)
        ├── service/MerchantServiceTest.java          (3, unitaires)
        ├── service/TransactionServiceIntegrationTest.java (4, PostgreSQL)
        ├── web/TransactionControllerTest.java        (7, MockMvc)
        ├── web/MerchantControllerTest.java           (5, MockMvc)
        └── web/soap/TransactionSoapEndpointTest.java (3, SOAP réel)
```

---

## 4. Les couches et les deux façades

```
  Angular (JSON)                      Partenaire bancaire (XML)
        │                                       │
        ▼                                       ▼
 ┌─────────────────────┐              ┌─────────────────────┐
 │ TransactionController│  REST        │ TransactionSoapEndpoint│ SOAP
 │ + MerchantController │  /api/...    │ + XSD + WSDL           │ /ws
 └─────────────────────┘              └─────────────────────┘
        │ TransactionCreateRequest          │ TransactionCreateRequest
        │ (record + @Valid)                 │ (record JAXB validé par le XSD)
        └───────────────┬───────────────────┘
                        ▼
        ┌───────────────────────────────┐
        │ service/TransactionService    │  ★ UNE SEULE logique, 5 règles
        │ @Transactional                │
        └───────────────────────────────┘
                        ▼
        ┌───────────────────────────────┐
        │ repository/ (Spring Data JPA) │
        └───────────────────────────────┘
                        ▼
              PostgreSQL · tables transactions, merchants
```

**Point clé** : le contrôleur REST et l'endpoint SOAP sont deux **adaptateurs d'entrée**. Ils ne contiennent **aucune règle** — ils traduisent (JSON→record, XML→record) puis appellent la même méthode `TransactionService.create()`.

---

## 5. Les entités et leur correspondance SQL

### `Transaction` ⇄ `transactions`

| Colonne SQL | Attribut Java | Annotation clé |
|---|---|---|
| `transaction_id BIGSERIAL PK` | `Long transactionId` | `@Id @GeneratedValue(IDENTITY)` |
| **`account_id BIGINT NOT NULL`** | **`Long accountId`** | ⭐ **`Long` brut** (table d'un autre service) |
| `merchant_id BIGINT` (FK `merchants`) | `@ManyToOne Merchant merchant` | relation objet : **c'est notre table** |
| `amount DECIMAL(15,2) NOT NULL` | `BigDecimal amount` | `precision = 15, scale = 2` + `CHECK (amount > 0)` |
| `currency CHAR(3) DEFAULT 'TND'` | `String currency = "TND"` | `@PrePersist` |
| `transaction_type VARCHAR(20)` | `TransactionType` | `@Enumerated(STRING)` |
| `country CHAR(2)` | `String country` | — |
| `transaction_date TIMESTAMPTZ` | `OffsetDateTime` | `@PrePersist` = maintenant (UTC) |
| `channel`, `ip_address`, `device_id`, `beneficiary_name` | 4 champs tracés | **matière première du Risk Service (M3)** |
| `status VARCHAR(20) DEFAULT 'PENDING'` | `TransactionStatus` | init `PENDING` |

### `Merchant` ⇄ `merchants`
`merchantName` (unique en pratique via la règle R6), `merchantCategory`, `country`, `city`, `status` (`ACTIVE|INACTIVE|BLOCKED`), `createdAt`.

**`ddl-auto: validate`** refuse le démarrage si le Java diffère du SQL (testé : le service démarre ✅).

---

## 6. Les 7 endpoints REST + 1 opération SOAP

| # | Méthode | URL | Corps / params | Succès | Erreurs |
|---|---|---|---|---|---|
| 1 | **GET** | `/api/transactions?page&size&sort&accountId&status` | — | **200** `Page` | 400 |
| 2 | **GET** | `/api/transactions/{id}` | — | **200** | 400, **404** |
| 3 | **POST** | `/api/transactions` | `TransactionCreateRequest` | **201** + `Location` | **400**, **404**, **409** |
| 4 | **PATCH** | `/api/transactions/{id}/status?status=X` | — | **200** | 400, 404, **409** |
| 5 | **GET** | `/api/merchants` | — | **200** liste | — |
| 6 | **GET** | `/api/merchants/{id}` | — | **200** | **404** |
| 7 | **POST** | `/api/merchants` | `MerchantCreateRequest` | **201** + `Location` | **400**, **409** |
| **SOAP** | **POST** | `/ws` (enveloppe XML) | `createTransactionRequest` | **200** XML | **500** `Fault` |

### 6.1 `POST /api/transactions` — création

```json
{ "accountId": 1, "merchantId": 1, "amount": 88.75, "currency": "tnd",
  "transactionType": "TRANSFER", "country": "fr", "channel": "API",
  "ipAddress": "41.104.10.7", "deviceId": "curl-01", "beneficiaryName": "Tunis Market" }
```
```json
HTTP/1.1 201 Created
Location: /api/transactions/5
{ "transactionId": 5, "accountId": 1, "merchantId": 1, "amount": 88.75,
  "currency": "TND", "transactionType": "TRANSFER", "country": "FR",
  "transactionDate": "2026-10-05T11:12:29.643806Z", "channel": "API",
  "status": "PENDING", "createdAt": "2026-10-05T11:12:29.643806Z" }
```
- `currency: "tnd"` → **`TND`**, `country: "fr"` → **`FR`** (normalisés dans le mapper)
- `merchantId: 999` (inconnu) → **404** « Marchand introuvable »
- `accountId: 999` (inexistant) → l'INSERT viole la FK → **409**
- `amount: -10` → **400** + `errors[]`

### 6.2 `PATCH /api/transactions/{id}/status`
`PENDING/HELD → COMPLETED | FAILED | HELD` ; sur une transaction déjà `COMPLETED` ou `FAILED` → **409** « est définitive ».

### 6.3 `GET /api/transactions?status=PENDING`
Pagination `Page<TransactionResponse>` (20/page par défaut, `size` borné 1..100), **deux filtres combinables** (`accountId` + `status`).

---

## 7. Les règles métier

| # | Règle | Implémentation | Violation → |
|---|---|---|---|
| **R1** | Le **montant est strictement positif** | `@Positive` (DTO) **+** `CHECK (amount > 0)` (SQL) | 400 / contrainte |
| **R2** | Le **compte doit exister** | FK `fk_tx_account` (vérifiée par PostgreSQL) | 409 intégrité |
| **R3** | Un **marchand indiqué doit exister chez nous** | `merchantRepository.findById(...).orElseThrow` | 404 `MerchantNotFoundException` |
| **R4** | Toute transaction **naît `PENDING`** (en attente d'évaluation M3) | `Transaction.status = PENDING` + `@PrePersist` | — |
| **R5** | `COMPLETED` / `FAILED` sont **définitifs** | contrôle dans `changeStatus` | 409 `TransactionNotModifiableException` |
| **R6** | Le **nom de marchand est unique** (insensible à la casse) | `existsByMerchantNameIgnoreCase` | 409 `MerchantAlreadyExistsException` |
| **R7** | Pas de `DELETE` : une transaction est un fait historique | aucun `delete()` exposé | — |

**Double barrière** (comme en M1/M2-account) : contrôle applicatif → message lisible **+** contrainte SQL → filet `DataIntegrityViolationException` → aussi 409.

---

## 8. Le format d'erreur REST et le SOAP Fault

### REST — identique à M1/M2-account : `{timestamp, status, message, path, errors[]}`

| Exception | HTTP |
|---|---|
| `TransactionNotFoundException`, `MerchantNotFoundException` | 404 |
| `TransactionNotModifiableException`, `MerchantAlreadyExistsException` | 409 |
| `DataIntegrityViolationException` (FK compte inconnu) | 409 |
| `MethodArgumentNotValidException` (Bean Validation) | 400 + `errors[]` |
| `MethodArgumentTypeMismatchException` (`/abc`, `?status=FOO`) | 400 |
| `HttpMessageNotReadableException` (JSON/enum invalide) | 400 |
| `Exception` (le reste) | **500**, stacktrace serveur seule |

### SOAP — enveloppe `Fault` (le JSON n'existe pas ici)

```xml
<!-- montant négatif : rejeté par le XSD AVANT le code Java -->
<SOAP-ENV:Fault>
  <faultcode>SOAP-ENV:Client</faultcode>
  <faultstring xml:lang="en">Validation error</faultstring>
  <detail><spring-ws:ValidationError xmlns:spring-ws="http://springframework.org/spring-ws">
    cvc-minExclusive-valid: Value '-5' is not facet-valid with respect to minExclusive '0.0' ...
  </spring-ws:ValidationError></detail>
</SOAP-ENV:Fault>

<!-- marchand inconnu : levé par TransactionService, traduit par le endpoint -->
<SOAP-ENV:Fault>
  <faultcode>SOAP-ENV:Server</faultcode>
  <faultstring xml:lang="en">Transaction refusee : Marchand introuvable : id = 999</faultstring>
</SOAP-ENV:Fault>
```

`SOAP-ENV:Client` = l'erreur vient de **la requête** (ou du business) · `SOAP-ENV:Server` = erreur **serveur/métier**.

---

## 9. La façade SOAP/XML en détail

**Une logique, deux façades** — c'est l'argument central de la soutenance.

### 9.1 Le contrat : `src/main/resources/xsd/transaction.xsd`

```xml
<xs:schema targetNamespace="http://finrisk.tn/transactions" elementFormDefault="qualified">
  <xs:element name="createTransactionRequest" type="tns:createTransactionRequestType"/>
  <xs:complexType name="createTransactionRequestType">
    <xs:sequence>
      <xs:element name="accountId" type="xs:long"/>
      <xs:element name="merchantId" type="xs:long" minOccurs="0"/>
      <xs:element name="amount" type="tns:amountType"/>          <!-- minExclusive 0 -->
      <xs:element name="transactionType" type="tns:transactionTypeEnum"/>  <!-- 4 valeurs -->
      <xs:element name="country" type="tns:countryType"/>         <!-- pattern [A-Za-z]{2} -->
      ...
    </xs:sequence>
  </xs:complexType>
</xs:schema>
```

Le XSD fait **trois fois** le travail : il **documente**, il **valide** (avant Java) et il **génère le WSDL**.

### 9.2 Le flux d'une requête SOAP

```
POST /ws  (text/xml)
   │
   ▼
MessageDispatcherServlet            ← déclaré dans WebServiceConfig (/ws/*)
   │
   ▼
PayloadValidatingInterceptor        ← validation du XML contre le XSD
   │   invalide ? ──► SOAP-ENV:Client + détail spring-ws:ValidationError
   ▼
TransactionSoapEndpoint             ← @PayloadRoot(namespace, "createTransactionRequest")
   │   XML ──► CreateTransactionRequest (JAXB) ──► TransactionCreateRequest (DTO)
   ▼
TransactionService.create()         ★ EXACTEMENT la même méthode que le REST
   │
   ▼
PostgreSQL · table transactions
   │
   ▼
CreateTransactionResponse (JAXB)    ← re-validatee contre le XSD aussi (validateResponse)
```

### 9.3 Appel réel (test effectué)

```powershell
curl.exe -s -X POST -H "Content-Type: text/xml; charset=utf-8" `
  --data-binary "@soap1.xml" http://127.0.0.1:8083/ws
```
```xml
<SOAP-ENV:Envelope ...>
  <SOAP-ENV:Body>
    <createTransactionResponse xmlns="http://finrisk.tn/transactions">
      <transactionId>3</transactionId>
      <status>PENDING</status>
    </createTransactionResponse>
  </SOAP-ENV:Body>
</SOAP-ENV:Envelope>
```
Puis `GET /api/transactions/3` → la **même** ligne, **la même base** : critère M2 (« transaction SOAP persistée en base ») ✅.

### 9.4 Les 4 pièces de `WebServiceConfig`

| Pièce | Rôle |
|---|---|
| `@EnableWs` | détecte les `@Endpoint`, monte les mappings `@PayloadRoot` |
| `ServletRegistrationBean<MessageDispatcherServlet>` sur `/ws/*` | **le servlet SOAP** (déclaré à la main, voir §13) + `transformWsdlLocations` (le WSDL affiche l'URL réelle) |
| `DefaultWsdl11Definition` | génère **`/ws/transactionWsdlDefinition.wsdl`** depuis le XSD |
| `Jaxb2Marshaller` (`setPackagesToScan`) | sérialise XML ⇄ objets JAXB |
| `PayloadValidatingInterceptor` | valide requête **et** réponse contre le XSD |

### 9.5 REST vs SOAP — pourquoi les deux

| | REST (façade 1) | SOAP (façade 2) |
|---|---|---|
| Public | Angular, microservices internes | partenaires bancaires / legacy B2B |
| Format | JSON léger | XML + enveloppe SOAP |
| Contrat | Swagger (documentation) | **WSDL + XSD** (contractuel, auto-généré) |
| Validation | Bean Validation dans le code | **XSD avant le code Java** |
| Erreurs | codes HTTP (400/404/409) | **SOAP Fault** typée |
| Usage projet | le quotidien de l'app | le M2 l'exige ; en banque, les cores banking parlent encore SOAP |

**Alternatives refusées** : deux services (logique dupliquée) ❌ · SOAP seul (Angular perdu, Swagger perdu) ❌.

---

## 10. Swagger

- UI : `http://localhost:8083/swagger-ui.html` — groupes **`Transactions`** et **`Merchants`**
- JSON : `http://localhost:8083/v3/api-docs` (OpenAPI 3.1)

Annotations : `@Tag`, `@Operation`, `@ApiResponses`, `@Parameter`. **Swagger ne documente que le REST** : le SOAP est documenté par son WSDL (c'est sa force).

---

## 11. Les 30 tests

```powershell
mvn test
# Tests run: 30, Failures: 0, Errors: 0  →  BUILD SUCCESS
```

| Classe | Nb | Vérifie |
|---|---|---|
| `TransactionServiceTest` | **7** | naît `PENDING` + `save()` · marchand inconnu → 404 **sans `save()`** · marchand associé · `COMPLETED` intouchable · `PENDING→COMPLETED` · 404 · délégation des 2 filtres au repository |
| `MerchantServiceTest` | **3** | trim + pays en majuscules · doublon → exception **sans `save()`** · 404 |
| `TransactionControllerTest` | **7** | 200 page · **201 + `Location`** · **400 + `errors[]`** · 404 marchand · **404 formaté** · `/abc` → 400 · **409** statut définitif |
| `MerchantControllerTest` | **5** | 200 liste · **201 + Location** · **409** doublon · **400** nom manquant · **404** |
| `TransactionServiceIntegrationTest` | **4** | INSERT+SELECT réels dans `finrisk_test` (FK `accounts` insérée via `JdbcTemplate`) · 404 marchand · statuts définitifs · doublon marchand · **ROLLBACK** en fin de test |
| `TransactionSoapEndpointTest` | **3** | WSDL = 200 + `createTransaction` · **transaction SOAP persistée en base** (requête HTTP réelle sur port aléatoire) · `amount=-5` → **Fault XSD** |
| `TransactionServiceApplicationTests` | **1** | contexte complet + `ddl-auto: validate` |

**Les tests ne touchent jamais `finrisk`** : `@ActiveProfiles("test")` → base `finrisk_test`. Le test SOAP crée ses fixtures (client + compte + marchand) et **les nettoie** en `@AfterEach`.

---

## 12. Propriété des tables

| Service | Écrit | Lit |
|---|---|---|
| `transaction-service` | **`transactions`**, **`merchants`** | ces deux tables (et rien d'autre) |
| `account-service` | `accounts` | `accounts` |
| `customer-service` | `customers`, `risk_profiles` | `customers`, lecture seule `accounts` |

- `account_id` est stocké en **`Long`** (et non `@ManyToOne Account`) : **interdit de `JOIN` sur la table d'un autre service** ; la cohérence est garantie par `fk_tx_account` en PostgreSQL.
- `merchant_id`, lui, **est** notre table → `@ManyToOne Merchant` est autorisé (et évite une table parasite de code).
- `transactions` ne sera **lue** par `risk-service` (M3) et `alert-case-service` (M4) **qu'à travers nos API REST**, jamais en SQL direct.

---

## 13. Dépannage

| Symptôme | Cause | Correction |
|---|---|---|
| Compilation `XsdSchema cannot be converted to Resource` | Spring WS 5 a changé l'API | utiliser **`setXsdSchema(...)`** (et non `setSchema`) |
| `NoClassDefFoundError: javax/wsdl/extensions/ExtensibilityElement` au démarrage | `wsdl4j` est **optionnel** dans `spring-boot-starter-webservices` | ajouter la dépendance `wsdl4j:wsdl4j` (version gérée par Boot) |
| **`500 No static resource ws` sur `/ws`** | `@EnableWs` fournit `WsConfigurationSupport` → `@ConditionalOnMissingBean` → l'auto-config Boot **« back off »** et **n'enregistre plus** le `MessageDispatcherServlet` | déclarer soi-même `ServletRegistrationBean<MessageDispatcherServlet>` sur `/ws/*` |
| `500 No static resource transactions/3` | les endpoints sont sous **`/api/...`** (préfixe M0) | appeler `/api/transactions/3` ; vérifier via `/actuator/mappings` |
| `psql` introuvable (Windows) | non installé dans le PATH | vérifier via l'API elle-même ou `docker exec finrisk-postgres psql -U finrisk -d finrisk` |
| Fault « Marchand introuvable » sur un appel SOAP de test | fixtures absentes (compte/marchand) | insérer les fixtures (`INSERT ... RETURNING`) avant l'appel |
| Port 8083 occupé | ancien `java -jar` | tuer le PID de `Temp\opencode\m3\pid.txt` |
| Le WSDL affiche `localhost:8080` | `transformWsdlLocations` désactivé | déjà activé dans `WebServiceConfig` |

---

## 14. Historique M2

| Étape | Contenu | Commit |
|---|---|---|
| 1-2 | Squelette Initializr (**`web-services`** → starter SOAP), `pom`, `application.yml` (8083) | `5f8c7d0` |
| 3 | Entités `Transaction` + `Merchant` + 4 enums (`validate` OK) | `7aae85d` |
| 4-7 | Repositories, 4 DTO, 2 mappers, 2 services (**5 règles**), 2 contrôleurs REST, `GlobalExceptionHandler` | `cf3a19b` |
| 8 | **Façade SOAP** : XSD, classes JAXB, `WebServiceConfig`, `TransactionSoapEndpoint` + `wsdl4j` | `00b41ec` |
| 9 | **30 tests** (unitaires, MockMvc, PostgreSQL, SOAP réel) | `f58da8e` |
| 10 | Cette documentation | (ce commit) |

**Erreurs rencontrées** (matière pour le rapport) :
1. **`setSchema` / `setXsdSchema`** : API Spring WS 5 renommée → erreur de compilation (30 s de perdues).
2. **`wsdl4j` manquant** : dépendance optionnelle non transportée par le starter → `NoClassDefFoundError` au démarrage.
3. **Le casse-tête n°1** : `/ws` → « No static resource ». L'auto-config Boot n'enregistre le servlet SOAP **que si** aucun `WsConfigurationSupport` n'existe — or `@EnableWs` en crée un → **conflit de configuration implicite**, service « vert » mais façade morte. Diagnostic : lecture des annotations de la classe au `javap` (décompilation).
4. **Fausse alerte** : 500 sur `/transactions` → en réalité les chemins sont sous `/api/...` ; diagnostic via `/actuator/mappings` temporairement exposé.
5. Pépins d'environnement : `psql` absent du PATH, contenu `byte[]` de l'actuator en PowerShell 5.1, `ChildProcess.kill` du shell (revérifier le PID).

---

## 15. Ce qui reste (M3+)

| Jalon | Sur ce service |
|---|---|
| **M3** | `risk-service` appellera `POST /api/transactions` (ou écoutera la création) puis évaluera les traces `ip/device/beneficiary` ; il changera le statut via `PATCH /{id}/status` |
| **M4** | alert-case-service lira les transactions concernées **via notre API** |
| **M5** | JWT : `POST /api/transactions` réservé aux rôles `ANALYST`/`ADMIN` ; le SOAP devra intégrer **WS-Security** |
| **M8** | `Dockerfile` + port 8083 exposé dans `docker-compose.yml` |

À venir **sur ce service** si besoin : `GET /api/transactions/merchant/{id}`, filtre par montant/date, `PUT` de correction (rétrocession), export.

---

## 16. Points de soutenance

1. **Pourquoi SOAP *et* REST en même temps ?** → deux publics, un seul cerveau : Angular parle JSON, les partenaires bancaires legacy parlent XML/WSDL. Le XSD est un contrat opposable, Swagger ne l'est pas.
2. **Combien de logique est dupliquée ?** → **zéro**. `TransactionController` et `TransactionSoapEndpoint` ne font que traduire, puis appellent `TransactionService.create()` (pattern ports & adaptateurs).
3. **D'où vient le WSDL ?** → **auto-généré** depuis le XSD par `DefaultWsdl11Definition` : le contrat et l'implémentation ne peuvent pas diverger.
4. **Qui valide une requête SOAP ?** → `PayloadValidatingInterceptor` **avant** d'atteindre le code Java (testé : `amount=-5` → Fault `cvc-minExclusive-valid`, aucune ligne en base).
5. **Pourquoi `accountId` en `Long` ?** → règle M0 : pas de `JOIN` sur la table d'un autre service ; la FK PostgreSQL garantit l'intégrité.
6. **Pourquoi `PENDING` au départ ?** → la transaction est créée par le domaine « comptes » mais évaluée par le **Risk Service (M3)** : on naît en attente d'analyse.
7. **`COMPLETED` est-il réversible ?** → non : c'est un fait historique (409), règle R5.
8. **30 tests, quel mix ?** → 10 unitaires (logique seule) + 12 HTTP simulés (contrat REST) + 4 PostgreSQL réel + 3 **SOAP réel** (dont la persistance) + 1 contexte.
9. **Et le critère M2 exact ?** → *« transaction SOAP persistée en base »* : test `createTransaction_parSoap_persisteEnBase` qui POSTe du XML et relit la ligne **en SQL**.
