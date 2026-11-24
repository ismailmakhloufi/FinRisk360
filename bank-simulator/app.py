"""
ATLAS BANK - Banque partenaire (service INDEPENDANT, port 5000).

Console a 3 roles, authentifiee sur une base SQLite LOCALE (db.py) :
  - ADMIN    : cree / consulte / supprime clients et marchands ;
               toute operation est PROXIFIEE vers FinRisk360 (:8080) ;
               les routes /admin/flux/start|stop|flux.json (1 tx aleatoire / 3 s)
               restent disponibles POUR LES TESTS EN CODE (pas d'interface)
  - CLIENT   : identifi generee a la creation (mdp affiche une fois),
               voit son solde reel + id de compte + RIB, change son mdp,
               et opere en 2 modes : ENVOI (vers un destinataire, ajoute
               dans la table marchands, compte credite s'il existe) / RETRAIT ;
               chaque operation ouvre un RECU imprimable ;
               la route POST /client/scenario (structuring, boom&bust,
               smurfing, geo-travel) reste disponible POUR LES TESTS POUR CODE
               (pas d'interface : les flux se testent manuellement ou en code)
  - MERCHANT : idem : consulte sa fiche et ses transactions, change son mdp

ALERTE : le simulateur ne cree une alerte que si le score >= 30 (bande
MONITORING) - c'est l'appelant qui decide, comme le documente FinRisk360.

La suppression d'un client est une vraie cascade SQL chez FinRisk360,
orchestree ici dans l'ordre impose par les FK :
  alerts -> evaluations -> transactions -> accounts -> customers

Demarrage :  py bank-simulator/app.py
Arret      :  Ctrl+C
"""

from __future__ import annotations

import os
import random
import re
import secrets
import sys
import threading
import time
from functools import wraps

import requests
from flask import (
    Flask,
    abort,
    flash,
    g,
    has_app_context,
    redirect,
    render_template,
    request,
    session,
    url_for,
)
from werkzeug.exceptions import HTTPException

import db as simdb

GATEWAY = os.environ.get("FINRISK_GATEWAY", "http://127.0.0.1:8080").rstrip("/")
# Compte de service : la banque partenaire consomme l'API FinRisk360.
def required_secret(name: str) -> str:
    value = os.environ.get(name, "")
    if not value.strip():
        raise RuntimeError(f"{name} doit être configuré.")
    return value


SERVICE_USER = required_secret("FINRISK_USER")
SERVICE_PASSWORD = required_secret("FINRISK_PASSWORD")

app = Flask(__name__)
# Developpement : les templates HTML sont relus a CHAQUE requete et le
# navigateur ne met pas CSS/JS en cache -> une modification dans templates/
# ou static/ est visible immediatement, sans redemarrer le simulateur.
# (Seul un changement dans app.py / db.py demande un redemarrage.)
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.config["SEND_FILE_MAX_AGE_DEFAULT"] = 0
app.secret_key = required_secret("ATLAS_SECRET_KEY")
if len(app.secret_key.encode()) < 32:
    raise RuntimeError("ATLAS_SECRET_KEY doit contenir au moins 32 octets.")
app.config.update(
    SESSION_COOKIE_NAME="atlas_session",
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=os.environ.get("ATLAS_COOKIE_SECURE") == "1",
)


@app.template_global()
def csrf_token() -> str:
    token = session.get("_csrf")
    if not token:
        token = secrets.token_urlsafe(32)
        session["_csrf"] = token
    return token


@app.before_request
def check_csrf():
    if request.method in {"POST", "PUT", "PATCH", "DELETE"}:
        expected = session.get("_csrf", "")
        supplied = request.headers.get("X-CSRF-Token") or request.form.get("_csrf", "")
        if not expected or not secrets.compare_digest(expected.encode(), supplied.encode()):
            abort(403)

simdb.init_db()

_token: str | None = None
# Le verrou protège le renouvellement partagé entre les requêtes et le flux.
_api_lock = threading.Lock()
ROLE_PAGES = {"ADMIN": "admin", "CLIENT": "client", "MERCHANT": "merchant"}


# ── Proxy API FinRisk360 ───────────────────────────────────────────
def api_transport():
    if not has_app_context():
        return requests
    if "finrisk_http" not in g:
        g.finrisk_http = requests.Session()
    return g.finrisk_http


@app.teardown_appcontext
def close_api_transport(_error):
    transport = g.pop("finrisk_http", None)
    if transport is not None:
        transport.close()


def _login() -> str:
    r = api_transport().post(
        f"{GATEWAY}/api/auth/login",
        json={"username": SERVICE_USER, "password": SERVICE_PASSWORD},
        timeout=10,
        allow_redirects=False,
    )
    if r.status_code in (401, 403):
        raise ApiException("FinRisk360 a refusé le compte de service Atlas Bank.")
    r.raise_for_status()
    credentials = r.json()
    if credentials.get("user", {}).get("role") != "BANK_SIMULATOR":
        raise ApiException("Le compte de service Atlas Bank doit avoir le rôle BANK_SIMULATOR.")
    return credentials["accessToken"]


def api(method: str, path: str, *, json=None, params=None) -> requests.Response:
    """Appelle FinRisk360 et renouvelle une fois le jeton refusé."""
    global _token
    with _api_lock:
        if _token is None:
            _token = _login()
        token = _token

    def send(access_token):
        return api_transport().request(
            method,
            f"{GATEWAY}{path}",
            json=json,
            params=params,
            headers={"Authorization": f"Bearer {access_token}"},
            timeout=15,
        )
    response = send(token)
    if response.status_code == 401:
        with _api_lock:
            if _token == token:
                _token = _login()
            token = _token
        return send(token)
    return response


def api_json(method: str, path: str, **kwargs):
    """Comme api() mais leve une ApiException lisible sur toute erreur HTTP."""
    r = api(method, path, **kwargs)
    if r.status_code >= 400:
        try:
            msg = r.json().get("message", "")
        except ValueError:
            msg = r.text[:200]
        raise ApiException(f"{r.status_code} - {msg or 'erreur API'}")
    return r.json() if r.content else None


class ApiException(Exception):
    pass


# ── Authentification locale (SQLite sim_users) ────────────────────
def current_user():
    if "atlas_user" not in g:
        uid = session.get("uid")
        g.atlas_user = simdb.find_by_id(uid) if uid else None
    return g.atlas_user


@app.context_processor
def inject_current_user():
    """Rend current_user() et rib_for() utilisables dans TOUS les templates."""
    return {"current_user": current_user, "rib_for": rib_for}


def _role_refusal(user: dict, roles) -> str:
    """Refus d'acces en clair : il explique POURQUOI, puis quoi FAIRE.

    « Acces reserve au role CLIENT (vous : ADMIN) » laissait l'administrateur
    se demander ce qui ne va pas. Ici le message dit d'ou vient le refus et
    comment proceder (se deconnecter, utiliser les identifiants du client).
    """
    if roles == ("CLIENT",) and user["role"] == "ADMIN":
        return ("Espace client reserve aux clients : votre session est ADMIN, "
                "elle ne peut pas ouvrir l'espace d'un client. Pour consulter "
                "l'espace d'un client, deconnectez-vous puis connectez-vous "
                "avec ses identifiants (identifiant visible dans son dossier).")
    if roles == ("MERCHANT",) and user["role"] == "ADMIN":
        return ("Espace marchand reserve aux marchands : votre session est "
                "ADMIN. Deconnectez-vous puis connectez-vous avec les "
                "identifiants du marchand (visibles dans sa fiche).")
    return f"Acces reserve au role {' / '.join(roles)} (vous : {user['role']})."


def login_required(*roles):
    """Exige une session ; 'roles' restreint a une liste (toute vide = aucun)."""

    def deco(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            user = current_user()
            if user is None:
                return redirect(url_for("login", next=request.path))
            if roles and user["role"] not in roles:
                flash(_role_refusal(user, roles), "ko")
                return redirect(url_for("home"))
            return fn(*args, **kwargs)

        return wrapper

    return deco


@app.route("/")
def home():
    user = current_user()
    if user is None:
        return redirect(url_for("login"))
    return redirect(url_for(ROLE_PAGES[user["role"]]))


# ── Lecture du « dossier complet » chez FinRisk360 ─────────────────
def _all_pages(path: str, **params):
    """Parcourt toutes les pages d'une liste paginee ({content, last})."""
    items, page = [], 0
    while True:
        data = api_json("GET", path, params={**params, "page": page, "size": 100})
        items.extend(data["content"])
        if data.get("last") or not data["content"]:
            return items
        page += 1


def fetch_customer_dossier(customer_id: int) -> dict:
    """Toute la base d'un client : fiche, comptes, tx, evaluations, alertes."""
    customer = api_json("GET", f"/api/customers/{customer_id}")
    accounts = api_json("GET", f"/api/customers/{customer_id}/accounts")

    transactions = []
    for acc in accounts:
        transactions.extend(_all_pages("/api/transactions", accountId=acc["accountId"],
                                       sort="transactionId"))

    evaluations = []
    for tx in transactions:
        try:
            evaluations.append(api_json("GET", f"/api/evaluations/transaction/{tx['transactionId']}"))
        except ApiException:
            pass  # transaction jamais evaluee : pas d'evaluation a afficher

    assessment_ids = {e["assessmentId"] for e in evaluations}
    alerts = [a for a in _all_pages("/api/alerts", sort="alertId")
              if a["assessmentId"] in assessment_ids]

    return {
        "customer": customer,
        "accounts": accounts,
        "transactions": transactions,
        "evaluations": evaluations,
        "alerts": alerts,
    }


def cascade_delete_customer(customer_id: int) -> dict:
    """Suppression DURE chez FinRisk360, dans l'ordre impose par les FK."""
    counts = {}
    counts["alertes"] = api_json("DELETE", "/api/alerts",
                                 params={"customerId": customer_id})["deleted"]
    counts["evaluations"] = api_json("DELETE", "/api/evaluations",
                                     params={"customerId": customer_id})["deleted"]
    counts["transactions"] = api_json("DELETE", "/api/transactions",
                                      params={"customerId": customer_id})["deleted"]
    counts["comptes"] = api_json("DELETE", "/api/accounts",
                                 params={"customerId": customer_id})["deleted"]
    counts["client"] = api_json("DELETE", f"/api/customers/{customer_id}")["deleted"]
    return counts


# ── Authentification ───────────────────────────────────────────────
@app.route("/login", methods=["GET", "POST"])
def login():
    if current_user():
        return redirect(url_for("home"))
    username = ""
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        user = simdb.verify(username, request.form.get("password", ""))
        if user:
            session.clear()
            session["uid"] = user["id"]
            destination = request.args.get("next", "")
            if (destination.startswith("/") and not destination.startswith("//")
                    and not re.search(r"[\\\x00-\x1f\x7f]", destination)):
                return redirect(destination)
            return redirect(url_for(ROLE_PAGES[user["role"]]))
        flash("Identifiants invalides. Vérifiez les identifiants de votre compte Atlas Bank.", "ko")
        return render_template("login.html", page="login", username=username), 401
    return render_template("login.html", page="login", username=username)


@app.post("/logout")
def logout():
    session.clear()
    flash("Deconnecte.", "ok")
    return redirect(url_for("login"))


# ── Espace ADMIN ───────────────────────────────────────────────────
PAGE_SIZE = 5  # elements par page des listes clients / marchands


@app.route("/admin")
@login_required("ADMIN")
def admin():
    """Listes clients et marchands : recherche (ID, nom, email) + pagination."""
    cq = (request.args.get("cq") or "").strip()
    mq = (request.args.get("mq") or "").strip()

    def page_no(arg):
        try:
            return max(1, int(arg))
        except (TypeError, ValueError):
            return 1

    def match(q, id_value, ribs, *text_fields):
        """ID exact si chiffre ; nom/email en texte ; un RIB retrouve aussi le
        client. Un RIB fait 20 chiffres : en dessous, un chiffrage continu reste
        une recherche par ID (sinon « 7 » matcherait tous les RIB contenant 7)."""
        if not q:
            return True
        if q.isdigit() and id_value == int(q):
            return True
        digits = q.replace(" ", "")
        if len(digits) >= 11 and digits.isdigit() and any(
                digits in r.replace(" ", "") for r in ribs):
            return True
        if q.isdigit():
            return False
        ql = q.lower()
        return any(ql in str(f or "").lower() for f in text_fields)

    def cut(items, wanted):
        """Decoupe en pages de PAGE_SIZE : (page courante, pages, totaux)."""
        pages = max(1, -(-len(items) // PAGE_SIZE))
        current = min(max(1, wanted), pages)
        return items[(current - 1) * PAGE_SIZE:current * PAGE_SIZE], current, pages

    ctx = {"page": "admin", "customers": [], "merchants": [],
           "cq": cq, "mq": mq, "cp": 1, "mp": 1,
           "c_pages": 1, "m_pages": 1, "c_total": 0, "m_total": 0,
           "ribs": {}}
    try:
        # RIB de tous les comptes, groupe par client : l'admin voit (et
        # recherche) les RIB de TOUT le monde sans ouvrir chaque dossier.
        try:
            ribs_by_customer: dict = {}
            for a in _all_pages("/api/accounts"):
                ribs_by_customer.setdefault(a.get("customerId"), []).append(
                    rib_for(a["accountNumber"]))
        except (ApiException, requests.RequestException):
            ribs_by_customer = {}  # RIB indisponible : la liste reste affichee
        ctx["ribs"] = ribs_by_customer

        customers = [c for c in _all_pages("/api/customers", sort="customerId")
                     if match(cq, c["customerId"], ribs_by_customer.get(c["customerId"], []),
                              f"{c.get('firstName', '')} {c.get('lastName', '')}",
                              c.get("email"))]
        merchants = [m for m in api_json("GET", "/api/merchants")
                     if match(mq, m["merchantId"], (), m.get("merchantName"),
                              m.get("merchantCategory"), m.get("city"))]
        ctx["c_total"], ctx["m_total"] = len(customers), len(merchants)
        ctx["customers"], ctx["cp"], ctx["c_pages"] = cut(customers, page_no(request.args.get("cp")))
        ctx["merchants"], ctx["mp"], ctx["m_pages"] = cut(merchants, page_no(request.args.get("mp")))
    except ApiException as exc:
        ctx["error"] = f"API FinRisk360 injoignable ({exc})"
    except requests.RequestException:
        ctx["error"] = "API FinRisk360 injoignable - services demarres ?"
    return render_template("admin.html", **ctx)


def admin_link(**over):
    """Lien /admin qui conserve la recherche et les pages courantes.

    Les parametres vides ou egales a la page 1 sont omis (URL propres).
    Exemples : admin_link(cp=2)  -> page suivante, recherche conservee
               admin_link(cq='', cp=1) -> efface la recherche clients
    """
    args = {k: v for k, v in request.args.items() if k in ("cq", "cp", "mq", "mp")}
    args.update({k: str(v) for k, v in over.items()})
    args = {k: v for k, v in args.items() if v not in ("", "1")}
    return url_for("admin", **args)


def admin_page(which: str, p: int) -> str:
    """Lien vers la page p de la liste 'cp' (clients) ou 'mq'/'mp' (marchands)."""
    return admin_link(**{which: p})


app.jinja_env.globals["admin_link"] = admin_link
app.jinja_env.globals["admin_page"] = admin_page


# Flux temps reel en arriere-plan (demo) : 1 operation aleatoire / 3 s.
_flux = {"running": False, "generated": 0, "alerted": 0, "errors": 0, "since": ""}
_flux_stop = threading.Event()
_flux_thread: threading.Thread | None = None


def _flux_once(accounts: list[dict], merchant_names: list[str]) -> None:
    """Une operation aleatoire : depot, retrait ou virement vers un marchand."""
    account = random.choice(accounts)
    roll = random.random()
    country = random.choices(["TN", "FR", "NG"], weights=[88, 8, 4])[0]
    if roll < 0.40 or float(account["balance"]) < 500:
        action, amount = "FUND", random.uniform(100, 5000)
    elif roll < 0.75:
        action, amount = "RETRAIT", random.uniform(50, 4500)
    else:
        action, amount = "ENVOI", random.uniform(50, 4500)
    if action != "FUND" and amount > float(account["balance"]):
        action, amount = "FUND", random.uniform(100, 5000)
    amount = round(amount, 2)

    run_operation(
        account=account, amount=amount, action=action,
        beneficiary=(random.choice(merchant_names)
                     if action == "ENVOI" and merchant_names else None),
        country=country if action != "FUND" else "TN",
        channel=random.choice(["MOBILE", "POS", "ATM", "WEB"]),
    )
    _flux["generated"] += 1
    # risk-service ouvre une alerte pour TOUTE evaluation (cf. EvaluationController)
    # : chaque transaction generee en produit donc une.
    _flux["alerted"] += 1


def _flux_worker() -> None:
    """Boucle du flux : s'arrete seul apres 5 erreurs consecutives."""
    fails = 0
    while not _flux_stop.is_set():
        try:
            accounts = [a for a in _all_pages("/api/accounts")
                        if a["status"] == "ACTIVE"]
            names = [m["merchantName"] for m in api_json("GET", "/api/merchants")]
            if not accounts:
                raise ApiException("500 - aucun compte actif")
            if not names:
                names = [find_or_create_merchant("COMMERCE PROXIMITE")[0]["merchantName"]]
            _flux_once(accounts, names)
            fails = 0
        except (ApiException, requests.RequestException):
            fails += 1
            _flux["errors"] += 1
            if fails >= 5:
                break
        _flux_stop.wait(3.0)
    _flux["running"] = False


@app.route("/admin/flux/start", methods=["POST"])
@login_required("ADMIN")
def flux_start():
    global _flux_thread
    if _flux_thread is not None and _flux_thread.is_alive():
        _flux_thread.join(timeout=4)  # l'arret precedent se termine
    if _flux_thread is not None and _flux_thread.is_alive():
        flash("Le flux tourne deja.", "ko")
        return redirect(url_for("admin"))
    _flux_stop.clear()
    _flux.update(running=True, generated=0, alerted=0, errors=0,
                 since=time.strftime("%H:%M:%S"))
    _flux_thread = threading.Thread(target=_flux_worker, daemon=True, name="flux")
    _flux_thread.start()
    flash("Flux temps reel LANCE : 1 transaction aleatoire toutes les 3 s "
          "(les alertes de score >= 30 tombent dans la console analyste).", "ok")
    return redirect(url_for("admin"))


@app.route("/admin/flux/stop", methods=["POST"])
@login_required("ADMIN")
def flux_stop():
    _flux_stop.set()
    _flux["running"] = False
    flash(f"Flux ARRETE : {_flux['generated']} transactions generatees "
          f"({_flux['alerted']} alertes).", "ok")
    return redirect(url_for("admin"))


@app.route("/admin/flux.json")
@login_required("ADMIN")
def flux_status():
    """Etat du flux (poll JS de la page admin toutes les 3 s)."""
    return dict(_flux)


@app.route("/admin/clients/new", methods=["GET", "POST"])
@login_required("ADMIN")
def new_client():
    if request.method == "POST":
        f = request.form
        payload = {
            "firstName": f["firstName"].strip(),
            "lastName": f["lastName"].strip(),
            "email": f.get("email", "").strip() or None,
            "phone": f.get("phone", "").strip() or None,
            "dateOfBirth": f.get("dateOfBirth", "") or None,
            "country": (f.get("country") or "TN").strip().upper()[:2],
            "city": f.get("city", "").strip() or None,
            "customerType": f.get("customerType") or "RETAIL",
        }
        password = secrets.token_urlsafe(9)
        try:
            customer = api_json("POST", "/api/customers", json=payload)
            account = api_json("POST", "/api/accounts", json={
                "customerId": customer["customerId"],
                "accountType": f.get("accountType") or "CHECKING",
                "currency": "TND",
                "openingBalance": float(f.get("openingBalance") or 0),
            })
            cid = customer["customerId"]
            username = f"client.{cid}"
            simdb.create_user(username, password, "CLIENT",
                              customer_id=cid, email=payload["email"])
            flash(
                {
                    "title": "NOUVEAU CLIENT — ACCES A L'ESPACE",
                    "msg": f"Client #{cid} cree avec le compte {account['accountNumber']} "
                    f"({account['balance']:.2f} TND).",
                    "username": username,
                    "password": password,
                    "hint": "Transmis une seule fois au client — notez-les avant de fermer cette page.",
                },
                "creds",
            )
            return redirect(url_for("customer_detail", customer_id=cid))
        except ApiException as exc:
            flash(f"Creation refusee : {exc}", "ko")
        except requests.RequestException:
            flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return render_template("customer_form.html", page="admin")


@app.route("/admin/clients/<int:customer_id>")
@login_required("ADMIN")
def customer_detail(customer_id: int):
    try:
        dossier = fetch_customer_dossier(customer_id)
    except ApiException as exc:
        flash(f"Consultation impossible : {exc}", "ko")
        return redirect(url_for("admin"))
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
        return redirect(url_for("admin"))
    return render_template("customer_detail.html", page="admin", sim_user=simdb.find_by_customer(customer_id), **dossier)


@app.route("/admin/clients/<int:customer_id>/delete", methods=["POST"])
@login_required("ADMIN")
def delete_client(customer_id: int):
    try:
        counts = cascade_delete_customer(customer_id)
        local = simdb.find_by_customer(customer_id)
        if local:
            simdb.delete_user(local["id"])
        flash(
            f"Client #{customer_id} supprime de la base : "
            + ", ".join(f"{v} {k}" for k, v in counts.items())
            + ", compte local efface.",
            "ok",
        )
    except ApiException as exc:
        flash(f"Suppression refusee : {exc}", "ko")
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return redirect(url_for("admin"))


@app.route("/admin/merchants/new", methods=["GET", "POST"])
@login_required("ADMIN")
def new_merchant():
    if request.method == "POST":
        f = request.form
        password = secrets.token_urlsafe(9)
        try:
            m = api_json("POST", "/api/merchants", json={
                "merchantName": f["merchantName"].strip(),
                "merchantCategory": f.get("merchantCategory", "").strip() or None,
                "country": (f.get("country") or "TN").strip().upper()[:2],
                "city": f.get("city", "").strip() or None,
            })
            mid = m["merchantId"]
            username = f"merchant.{mid}"
            simdb.create_user(username, password, "MERCHANT",
                              merchant_id=mid, email=f.get("email", "").strip() or None)
            flash(
                {
                    "title": "NOUVEAU MARCHAND — ACCES A L'ESPACE",
                    "msg": f"Marchand #{mid} '{m['merchantName']}' enregistre.",
                    "username": username,
                    "password": password,
                    "hint": "Transmis une seule fois au marchand — notez-les avant de fermer cette page.",
                },
                "creds",
            )
            return redirect(url_for("merchant_detail", merchant_id=mid))
        except ApiException as exc:
            flash(f"Enregistrement refuse : {exc}", "ko")
        except requests.RequestException:
            flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return render_template("merchant_form.html", page="admin")


@app.route("/admin/merchants/<int:merchant_id>")
@login_required("ADMIN")
def merchant_detail(merchant_id: int):
    try:
        merchant = api_json("GET", f"/api/merchants/{merchant_id}")
        txs = [t for t in _all_pages("/api/transactions", sort="transactionId")
               if t.get("merchantId") == merchant_id]
    except ApiException as exc:
        flash(f"Consultation impossible : {exc}", "ko")
        return redirect(url_for("admin"))
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
        return redirect(url_for("admin"))
    return render_template("merchant_detail.html", page="admin", merchant=merchant,
                           transactions=txs, sim_user=simdb.find_by_merchant(merchant_id))


@app.route("/admin/merchants/<int:merchant_id>/delete", methods=["POST"])
@login_required("ADMIN")
def delete_merchant(merchant_id: int):
    try:
        api_json("DELETE", f"/api/merchants/{merchant_id}")
        local = simdb.find_by_merchant(merchant_id)
        if local:
            simdb.delete_user(local["id"])
        flash(f"Marchand #{merchant_id} supprime (transactions conservees sans "
              f"marchand lie), compte local efface.", "ok")
    except ApiException as exc:
        flash(f"Suppression refusee : {exc}", "ko")
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return redirect(url_for("admin"))


# ── Espace CLIENT (le client ne voit que SES donnees) ──────────────
@app.route("/client")
@login_required("CLIENT")
def client():
    user = current_user()
    try:
        dossier = fetch_customer_dossier(user["customer_id"])
    except ApiException as exc:
        flash(f"Consultation impossible : {exc}", "ko")
        dossier = {}
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
        dossier = {}
    return render_template("client.html", page="client", user=user, **dossier)


def find_or_create_merchant(name: str) -> tuple[dict, bool]:
    """Destinataire tape par le client : marchand existant ou cree sur la table."""
    name = name.strip()[:100]
    for m in api_json("GET", "/api/merchants"):
        if m["merchantName"].strip().lower() == name.lower():
            return m, False
    created = api_json("POST", "/api/merchants", json={
        "merchantName": name,
        "merchantCategory": "VIREMENT",
        "country": "TN",
        "city": None,
    })
    return created, True


def format_rib(digits: str) -> str:
    """20 chiffres -> RIB lisible : banque(4) agence(3) compte(11) cle(2)."""
    return f"TN {digits[:4]} {digits[4:7]} {digits[7:18]} {digits[18:]}"


def rib_for(account_number) -> str:
    """Format RIB tunisien : banque(4) + agence(3) + compte(11) + cle(2) = 20 chiffres."""
    digits = re.sub(r"\D", "", str(account_number))[-11:].zfill(11)
    body = f"0109001{digits}"
    key = f"{(int(body) % 97) + 1:02d}"
    return format_rib(body + key)


def normalize_rib(rib: str) -> str:
    """RIB tape par le client -> 20 chiffres canoniques.

    Un RIB malforme ne doit JAMAIS passer pour 'destinataire externe' : le
    client se trompe d'une chiffre et son envoi partirait nulle part. On leve
    donc 400 des la forme ou la cle de controle (modulo 97) est fausse.

    DEUX cles sont acceptees, car deux conventions coexistent :
      - (body % 97) + 1 : celle de NOS comptes (rib_for, banque 0109) ;
      - 98 - (body % 97) : la cle ISO 13616 standard, utilisee par les
        AUTRES banques tunisiennes — sans elle, un RIB valide d'une autre
        banque serait rejete au lieu d'etre traite comme destinataire
        EXTERNE.
    C'est un controle de COMMODITE (retour immediate) : l'API FinRisk360
    refait le meme controle a la creation de la transaction.
    """
    digits = re.sub(r"\D", "", str(rib))
    if len(digits) != 20:
        raise ApiException("400 - RIB invalide : 20 chiffres attendus "
                           "(forme TN 0109 001 xxxxxxxxxx xx)")
    body = int(digits[:18])
    valid_keys = {f"{(body % 97) + 1:02d}", f"{98 - (body % 97):02d}"}
    if digits[18:] not in valid_keys:
        raise ApiException("400 - RIB invalide : cle de controle incorrecte "
                           "(verifiez le RIB saisi)")
    return digits


def run_operation(*, account: dict, amount: float, action: str,
                  beneficiary: str | None = None, rib: str = "",
                  country: str = "TN", channel: str = "API") -> dict:
    """Chaine FinRisk360 complete : TX -> evaluation -> alerte (si risquee).

    action : ENVOI (TRANSFER), RETRAIT (WITHDRAWAL), FUND (DEPOSIT)
    rib : RIB TN canonique du beneficiaire (ENVOI uniquement). C'est
        l'API FinRisk360 qui le VALIDE (20 chiffres + cle modulo 97) et le
        RESOUT : trouve en base -> compte interne credite a l'acceptation ;
        inconnu -> destinataire externe (autre banque), seul le debit du
        compte source est applique. Un RIB malforme = 400, rien n'est cree.

    AUCUN solde ne bouge ici : l'argent ne bouge qu'a la regulation, cote
    transaction-service, quand le statut passe a COMPLETED. Tant que la
    transaction est PENDING elle est purement comptable — d'ou rien a
    reverser si l'analyste decide d'annuler.
    """
    merchant = None
    merchant_created = False
    if action == "ENVOI":
        merchant, merchant_created = find_or_create_merchant(beneficiary)
    tx_type = {"ENVOI": "TRANSFER", "RETRAIT": "WITHDRAWAL", "FUND": "DEPOSIT"}[action]

    tx = api_json("POST", "/api/transactions", json={
        "accountId": account["accountId"],
        "merchantId": merchant["merchantId"] if merchant else None,
        "amount": amount,
        "currency": "TND",
        "transactionType": tx_type,
        "country": country,
        "channel": channel,
        "beneficiaryName": beneficiary,
        "beneficiaryRIB": rib or None,
    })
    assessment = api_json("POST", "/api/evaluations",
                          json={"transactionId": tx["transactionId"]})
    # L'alerte est ouverte par risk-service LUI-MEME, et ce pour TOUTE
    # transaction meme anodine : sans elle, l'operation serait liquidee hors de
    # tout examen et un analyste n'aurait aucune trace a consulter. On ne la
    # cree donc PLUS ici (risque de doublon "une seule alerte par evaluation").
    return {"tx": tx, "assessment": assessment, "alert": None,
            "merchant": merchant, "merchant_created": merchant_created}


def recap_operation(action: str, amount: float, beneficiary: str | None,
                    res: dict, rib: str = "") -> str:
    """Message flash synthetique d'une operation (recu ou scénario).

    Aucun solde n'a bouge : on annonce la mise en attente d'un analyste, jamais
    un "nouveau solde" qui n'existera qu'apres acceptation.
    """
    if action == "ENVOI":
        recap = f"ENVOI de {amount:.2f} TND a {beneficiary}"
        if res["merchant_created"]:
            recap += f" (marchand #{res['merchant']['merchantId']} ajoute)"
        if res["tx"].get("beneficiaryAccountId"):
            recap += " - compte interne identifie par RIB"
        elif rib:
            recap += " - destinataire externe (RIB inconnu chez FinRisk360)"
        else:
            recap += " - envoi simple"
    else:
        recap = f"{action} de {amount:.2f} TND"
    recap += (f" - TX #{res['tx']['transactionId']} - controle antifraude "
              f"en cours, en attente de validation par un analyste "
              f"(le solde ne bouge qu'apres acceptation)")
    return recap


@app.route("/client/operate", methods=["POST"])
@login_required("CLIENT")
def operate():
    """2 MODES : ENVOI (depot vers un destinataire) ou RETRAIT, sur SES comptes."""
    user = current_user()
    form = request.form
    try:
        action = form.get("action", "RETRAIT")
        if action not in ("ENVOI", "RETRAIT"):
            raise ApiException("400 - mode d'operation inconnu")
        # Sans compte actif, le <select name="accountId"> est vide et le champ
        # n'est pas soumis : on echange le KeyError (-> 500) par un message
        # lisible renvoye au client via le bandeau flash.
        if not form.get("accountId") or not form.get("amount"):
            raise ApiException("400 - selectionnez un compte ACTIF et un montant "
                               "(aucun compte actif sur votre dossier ?)")
        account_id = int(form["accountId"])
        amount = float(form["amount"])
        if amount <= 0:
            raise ApiException("400 - le montant doit etre positif")

        # Garde-fou : le compte source doit appartenir au client connecte.
        source = api_json("GET", f"/api/accounts/{account_id}")
        if source["customerId"] != user["customer_id"]:
            raise ApiException("403 - ce compte n'appartient pas a votre client")

        beneficiary = None
        rib = ""
        if action == "ENVOI":
            beneficiary = form.get("beneficiary", "").strip()[:100]
            if not beneficiary:
                raise ApiException("400 - le destinataire est obligatoire")
            # Le RIB est OBLIGATOIRE : il identifie seul le beneficiaire. Le nom
            # reste libre (on ne le verifie pas, il se tape avec des fautes).
            # La forme est controlee ici (retour immediate au client) PUIS
            # re-controlee et resolue par l'API FinRisk360 a la creation :
            # interne = credite a l'acceptation, externe = debit seul.
            digits = normalize_rib(form.get("beneficiaryRib", ""))
            rib = format_rib(digits)

        res = run_operation(account=source, amount=amount, action=action,
                            beneficiary=beneficiary, rib=rib)

        # RIB de la cible pour le recu : conserve meme pour un destinataire
        # externe, le client doit pouvoir relire ce qu'il a saisi.
        if action == "ENVOI":
            ribs = dict(session.get("tx_rib", {}))
            ribs[str(res["tx"]["transactionId"])] = rib
            session["tx_rib"] = ribs

        flash(recap_operation(action, amount, beneficiary, res, rib), "ok")
        return redirect(url_for("receipt", tx_id=res["tx"]["transactionId"]))
    except ValueError:
        flash("Formulaire invalide : compte ou montant manquant.", "ko")
    except ApiException as exc:
        flash(f"Operation refusee : {exc}", "ko")
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return redirect(url_for("client"))


@app.route("/client/receipt/<int:tx_id>")
@login_required("CLIENT")
def receipt(tx_id: int):
    """Recu imprimable d'une transaction du client connecte."""
    user = current_user()
    try:
        tx = api_json("GET", f"/api/transactions/{tx_id}")
        account = api_json("GET", f"/api/accounts/{tx['accountId']}")
        if account["customerId"] != user["customer_id"]:
            raise ApiException("403 - cette transaction n'appartient pas a votre client")
        customer = api_json("GET", f"/api/customers/{user['customer_id']}")
        # NOTE : l'evaluation de risque n'est PAS recuperee ici - le score, le
        # niveau et la decision restent strictement internes a la banque.
        ribs = dict(session.get("tx_rib", {}))
        rib_dest = ribs.get(str(tx_id))  # conserve : le recu reste affichable au refresh
    except ApiException as exc:
        flash(f"Recu indisponible : {exc}", "ko")
        return redirect(url_for("client"))
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
        return redirect(url_for("client"))
    return render_template("receipt.html", page="client", user=user, tx=tx,
                           account=account, customer=customer,
                           rib=rib_for(account["accountNumber"]), rib_dest=rib_dest)


@app.route("/client/balances.json")
@login_required("CLIENT")
def client_balances():
    """Soldes + STATUTS REELS (json) : l'espace client poll toutes les 3 s.

    Au-delà des soldes, renvoie aussi les statuts pour que les pastilles se
    mettent à jour SANS recharger :
      - customerStatus : dossier du client (ACTIVE / SUSPENDED / CLOSED)
      - access         : ce que le client PEUT faire (ACTIVE / BLOCKED)
      - compte         : ACTIVE / FROZEN / CLOSED
      - tx             : PENDING / COMPLETED / CANCELLED / FAILED / HELD
    Une décision d'analyste (acceptation -> regulation du solde, ou annulation
    sans aucun mouvement) est donc visible
    côté client dans les ~3 s qui suivent.
    """
    user = current_user()
    customer = api_json("GET", f"/api/customers/{user['customer_id']}")
    accounts = api_json("GET", f"/api/customers/{user['customer_id']}/accounts")

    transactions = []
    for acc in accounts:
        transactions.extend(_all_pages("/api/transactions", accountId=acc["accountId"],
                                       sort="transactionId"))

    # ACCESS = ce que le client peut VRAIMENT faire, a ne pas confondre avec
    # le statut du DOSSIER (customer.status) : un client ACTIVE dont tous les
    # comptes sont FROZEN ou CLOSES n'a acces a aucune operation.
    access = "ACTIVE" if any(a["status"] == "ACTIVE" for a in accounts) else "BLOCKED"

    return {
        "total": round(sum(float(a["balance"]) for a in accounts), 2),
        "customerStatus": customer["status"],
        "access": access,
        "accounts": [{"accountId": a["accountId"], "balance": float(a["balance"]),
                      "status": a["status"]} for a in accounts],
        "transactions": [{"transactionId": t["transactionId"], "status": t["status"]}
                         for t in transactions],
    }


# ── Scenarios de fraude (outils de demo / soutenance) ──────────────
SCENARIOS = {
    "STRUCTURING": {
        "label": "STRUCTURING",
        "code": "SC-01",
        "badge": "VELOCITE",
        "title": "Virements fractionnes sous seuil",
        "desc": "Des fonds entrent, puis une rafale de virements reste juste sous le "
                "seuil de vigilance de 5 000 TND. Chaque operation isolee parait "
                "normale : c'est la velocite (> 10 tx / 24 h) qui fait monter le score.",
        "ops": 17,
        "flow": "100 000 TND entrent · 78 400 TND sortent",
    },
    "BOOM": {
        "label": "BOOM & BUST",
        "code": "SC-02",
        "badge": "MONTANT",
        "title": "Accumulation puis sortie rapide",
        "desc": "Depot important suivi d'une fuite immediate : virements de 18 000 TND "
                "et retraits de 9 000 TND. Montants hors normes et ecart brutal par "
                "rapport au comportement habituel du compte.",
        "ops": 6,
        "flow": "80 000 TND entrent · 72 000 TND sortent",
    },
    "SMURFING": {
        "label": "SMURFING",
        "code": "SC-03",
        "badge": "BENEFICIAIRES",
        "title": "Eclatement multi-beneficiaires",
        "desc": "Une somme unique est fractionnee en douze virements repartis sur "
                "plusieurs beneficiaires pour diluer la piste d'une operation "
                "individuellement anodine.",
        "ops": 13,
        "flow": "30 000 TND entrent · 12 virements de 2 500 TND",
    },
    "TRAVEL": {
        "label": "GEO-TRAVEL",
        "code": "SC-04",
        "badge": "GEOGRAPHIE",
        "title": "Trajectoire geographique inhabituelle",
        "desc": "Trois virements immediats vers TN, NG puis IR : pays rares pour ce "
                "compte et deplacements impossibles en quelques minutes "
                "(2 pays distincts en moins de 3 heures).",
        "ops": 4,
        "flow": "30 000 TND entrent · 24 000 TND vers l'etranger",
    },
}


def _beneficiaries(count: int) -> list[str]:
    """'count' noms de marchands : la table n'est jamais polluee par un scenario."""
    names = list(dict.fromkeys(m["merchantName"] for m in api_json("GET", "/api/merchants")))
    random.shuffle(names)
    if not names:
        names = [find_or_create_merchant("ATELIER SCENARIO")[0]["merchantName"]]
    return (names * ((count // len(names)) + 1))[:count]


def _scenario_plan(key: str) -> list[dict]:
    """Le plan d'injection : chaque entree = une operation FinRisk360 complete."""
    if key == "STRUCTURING":
        one = _beneficiaries(1)[0]
        return ([{"action": "FUND", "amount": 100000.0}]
                + [{"action": "ENVOI", "amount": 4900.0, "beneficiary": one}] * 16)
    if key == "BOOM":
        one = _beneficiaries(1)[0]
        return ([{"action": "FUND", "amount": 80000.0}]
                + [{"action": "ENVOI", "amount": 18000.0, "beneficiary": one}] * 3
                + [{"action": "RETRAIT", "amount": 9000.0}] * 2)
    if key == "SMURFING":
        names = list(dict.fromkeys(_beneficiaries(6)))  # beneficiaires distincts
        per = max(2, -(-12 // len(names)))  # ceil : au moins 12 sorties
        out = [{"action": "ENVOI", "amount": 2500.0, "beneficiary": n}
               for n in names for _ in range(per)]
        return [{"action": "FUND", "amount": round(2500.0 * len(out), 2)}] + out
    if key == "TRAVEL":
        one = _beneficiaries(1)[0]
        return [{"action": "FUND", "amount": 30000.0},
                {"action": "ENVOI", "amount": 8000.0, "beneficiary": one, "country": "TN"},
                {"action": "ENVOI", "amount": 8000.0, "beneficiary": one, "country": "NG"},
                {"action": "ENVOI", "amount": 8000.0, "beneficiary": one, "country": "IR"}]
    raise KeyError(key)


@app.route("/client/scenario", methods=["POST"])
@login_required("CLIENT")
def scenario():
    """Injecte un flux de fraude reel sur SES comptes (demo, en 1 clic)."""
    user = current_user()
    key = request.form.get("scenario", "")
    try:
        if key not in SCENARIOS:
            raise ApiException("400 - scenario inconnu")
        accounts = [a for a in api_json("GET", f"/api/customers/{user['customer_id']}/accounts")
                    if a["status"] == "ACTIVE"]
        if not accounts:
            raise ApiException("400 - aucun compte actif")
        primary = max(accounts, key=lambda a: float(a["balance"]))

        created = 0
        for step in _scenario_plan(key):
            run_operation(account=primary, amount=step["amount"],
                          action=step["action"],
                          beneficiary=step.get("beneficiary"),
                          country=step.get("country", "TN"),
                          channel="MOBILE")
            created += 1
            time.sleep(0.15)  # rythme humain : les alertes arrivent en direct
        flash(
            f"SCENARIO {SCENARIOS[key]['label']} : {created} transactions creees "
            f"- {created} alertes ouvertes (le moteur en ouvre une par "
            f"transaction) - ouvrez la console analyste pour les voir tomber.",
            "ok",
        )
    except ApiException as exc:
        flash(f"Scenario interrompu : {exc}", "ko")
    except requests.RequestException:
        flash("API FinRisk360 injoignable - services demarres ?", "ko")
    return redirect(url_for("client"))


@app.route("/client/password", methods=["POST"])
@login_required("CLIENT")
def change_client_password():
    user = current_user()
    current = request.form.get("currentPassword", "")
    new = request.form.get("newPassword", "")
    if len(new) < 12:
        flash("Le nouveau mot de passe doit faire au moins 12 caracteres.", "ko")
    elif not simdb.verify(user["username"], current):
        flash("Mot de passe actuel incorrect.", "ko")
    else:
        simdb.set_password(user["id"], new)
        flash("Mot de passe mis a jour.", "ok")
    return redirect(url_for("client"))


# ── Espace MERCHANT ────────────────────────────────────────────────
@app.route("/merchant")
@login_required("MERCHANT")
def merchant():
    user = current_user()
    ctx = {"page": "merchant", "user": user}
    try:
        ctx["merchant"] = api_json("GET", f"/api/merchants/{user['merchant_id']}")
        ctx["transactions"] = [t for t in _all_pages("/api/transactions", sort="transactionId")
                               if t.get("merchantId") == user["merchant_id"]]
    except ApiException as exc:
        ctx["error"] = f"API FinRisk360 injoignable ({exc})"
    except requests.RequestException:
        ctx["error"] = "API FinRisk360 injoignable - services demarres ?"
    return render_template("merchant.html", **ctx)


@app.route("/merchant/password", methods=["POST"])
@login_required("MERCHANT")
def change_merchant_password():
    user = current_user()
    current = request.form.get("currentPassword", "")
    new = request.form.get("newPassword", "")
    if len(new) < 12:
        flash("Le nouveau mot de passe doit faire au moins 12 caracteres.", "ko")
    elif not simdb.verify(user["username"], current):
        flash("Mot de passe actuel incorrect.", "ko")
    else:
        simdb.set_password(user["id"], new)
        flash("Mot de passe mis a jour.", "ok")
    return redirect(url_for("merchant"))


@app.errorhandler(Exception)
def on_error(exc):
    if isinstance(exc, ApiException):
        flash(str(exc), "ko")
        return redirect(url_for("home"))
    # HTTPException Flask (404 page introuvable, 400 champ de formulaire
    # absent...) : on la RENVOIE telle quelle. La re-lever ici la ferait
    # reclasser en 500 « Internal Server Error » illisible par l'utilisateur.
    if isinstance(exc, HTTPException):
        return exc
    raise exc


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    print(f"ATLAS BANK sur http://127.0.0.1:{port}  (gateway FinRisk360: {GATEWAY})")
    print(f"Compte administrateur local : admin ({simdb.DB_PATH.name})")
    sys.stdout.flush()
    app.run(host="127.0.0.1", port=port, debug=False)
