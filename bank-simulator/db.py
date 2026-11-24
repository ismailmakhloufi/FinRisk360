"""
Base locale du simulateur ATLAS BANK (SQLite).

Elle ne contient QUE les comptes de connexion de la console :
  - admin      : identite de gestion (mot de passe configure au premier demarrage)
  - client     : un compte par client FinRisk360 cree, mdp genere a la creation
  - merchant   : idem pour un marchand

Les donnees bancaires (clients, comptes, transactions) ne vivent PAS ici :
elles restent chez FinRisk360 et sont lues/proxifiees via l'API (:8080).
"""

from __future__ import annotations

import os
import secrets
import sqlite3
from contextlib import closing
from pathlib import Path

from werkzeug.security import check_password_hash, generate_password_hash

DB_PATH = Path(__file__).with_name("finrisk_sim.db")
DUMMY_PASSWORD_HASH = generate_password_hash(secrets.token_urlsafe(24))

SCHEMA = """
CREATE TABLE IF NOT EXISTS sim_users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    username     TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('ADMIN', 'CLIENT', 'MERCHANT')),
    customer_id  INTEGER,
    merchant_id  INTEGER,
    email        TEXT,
    created_at   TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_sim_users_username_ci ON sim_users (lower(username));
CREATE INDEX IF NOT EXISTS idx_sim_users_email_ci ON sim_users (lower(trim(email)));
CREATE INDEX IF NOT EXISTS idx_sim_users_customer ON sim_users (customer_id);
CREATE INDEX IF NOT EXISTS idx_sim_users_merchant ON sim_users (merchant_id);
"""


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    """Cree la base au 1er demarrage et s'assure qu'un admin existe."""
    with closing(connect()) as conn, conn:
        conn.executescript(SCHEMA)
        admin = conn.execute(
            "SELECT id, password_hash FROM sim_users WHERE username = 'admin' AND role = 'ADMIN'"
        ).fetchone()
        if admin and not check_password_hash(admin["password_hash"], "admin"):
            return
        if admin is None and conn.execute(
            "SELECT 1 FROM sim_users WHERE role = 'ADMIN'"
        ).fetchone():
            return
        password = os.environ.get("ATLAS_ADMIN_PASSWORD", "")
        if len(password.strip()) < 12:
            raise RuntimeError("Configurez ATLAS_ADMIN_PASSWORD avec au moins 12 caracteres.")
        password_hash = generate_password_hash(password)
        if admin:
            conn.execute("UPDATE sim_users SET password_hash = ? WHERE id = ?", (password_hash, admin["id"]))
        else:
            conn.execute(
                "INSERT INTO sim_users (username, password_hash, role) VALUES (?, ?, 'ADMIN')",
                ("admin", password_hash),
            )


def verify(username: str, password: str) -> sqlite3.Row | None:
    """Retourne l'utilisateur si les identifiants sont valides, sinon None.

    On accepte les trois entrees de la page de connexion :
      - l'identifiant   : admin, client.44, merchant.7
      - l'email         : client@example.test  (client/marchand)
      - le numero brut  : 44 (id client) ou 7 (id marchand)
    """
    ident = username.strip()
    with closing(connect()) as conn, conn:
        user = conn.execute(
            "SELECT * FROM sim_users WHERE username = ?", (ident,)
        ).fetchone()
        if user is not None:
            candidates = [user]
        elif "@" in ident:
            candidates = conn.execute(
                "SELECT * FROM sim_users WHERE lower(trim(email)) = lower(?)",
                (ident,),
            ).fetchall()
        elif ident.isascii() and ident.isdecimal():
            digits = ident.lstrip("0") or "0"
            number = int(digits) if len(digits) <= 19 else 0
            candidates = conn.execute(
                "SELECT * FROM sim_users WHERE customer_id = ? OR merchant_id = ?",
                (number, number),
            ).fetchall() if 0 < number <= 2**63 - 1 else []
        else:
            candidates = conn.execute(
                "SELECT * FROM sim_users WHERE lower(username) = lower(?)",
                (ident,),
            ).fetchall()
    if not candidates:
        check_password_hash(DUMMY_PASSWORD_HASH, password)
        return None
    matches = [account for account in candidates if check_password_hash(account["password_hash"], password)]
    return matches[0] if len(matches) == 1 else None


def find_by_id(user_id: int) -> sqlite3.Row | None:
    with closing(connect()) as conn, conn:
        return conn.execute("SELECT * FROM sim_users WHERE id = ?", (user_id,)).fetchone()


def find_by_customer(customer_id: int) -> sqlite3.Row | None:
    with closing(connect()) as conn, conn:
        return conn.execute(
            "SELECT * FROM sim_users WHERE customer_id = ?", (customer_id,)
        ).fetchone()


def find_by_merchant(merchant_id: int) -> sqlite3.Row | None:
    with closing(connect()) as conn, conn:
        return conn.execute(
            "SELECT * FROM sim_users WHERE merchant_id = ?", (merchant_id,)
        ).fetchone()


def create_user(
    username: str,
    password: str,
    role: str,
    *,
    customer_id: int | None = None,
    merchant_id: int | None = None,
    email: str | None = None,
) -> int:
    with closing(connect()) as conn, conn:
        cur = conn.execute(
            "INSERT INTO sim_users (username, password_hash, role, customer_id, merchant_id, email)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (username, generate_password_hash(password), role, customer_id, merchant_id, email),
        )
        return cur.lastrowid


def set_password(user_id: int, password: str) -> None:
    with closing(connect()) as conn, conn:
        conn.execute(
            "UPDATE sim_users SET password_hash = ? WHERE id = ?",
            (generate_password_hash(password), user_id),
        )


def delete_user(user_id: int) -> None:
    with closing(connect()) as conn, conn:
        conn.execute("DELETE FROM sim_users WHERE id = ?", (user_id,))


def count_users(role: str) -> int:
    with closing(connect()) as conn, conn:
        return conn.execute("SELECT COUNT(*) FROM sim_users WHERE role = ?", (role,)).fetchone()[0]
