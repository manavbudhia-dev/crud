import hmac
import os
import re
import secrets
import sqlite3
import time
from urllib.parse import quote_plus

from flask import Flask, abort, render_template, request, redirect, url_for, flash, g, session

DB_PATH = os.environ.get("DB_PATH", "/data/app.db")


def _database_url():
    """Postgres URL from DATABASE_URL, or built from DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME."""
    url = os.environ.get("DATABASE_URL", "").strip()
    if url:
        return url
    host = os.environ.get("DB_HOST", "").strip()
    if not host:
        return None
    user = quote_plus(os.environ.get("DB_USER", "postgres"))
    password = quote_plus(os.environ.get("DB_PASSWORD", ""))
    port = os.environ.get("DB_PORT", "5432")
    name = os.environ.get("DB_NAME", "postgres")
    auth = f"{user}:{password}" if password else user
    return f"postgresql://{auth}@{host}:{port}/{name}"


DATABASE_URL = _database_url()
USE_POSTGRES = DATABASE_URL is not None

app = Flask(__name__)
_secret = os.environ.get("SECRET_KEY", "").strip()
if not _secret or _secret in ("dev-secret-change-me", "change-this-in-production"):
    # Never sign sessions with a known key: a guessable key lets anyone forge
    # the session (and with it the CSRF token). Fall back to a random per-process key.
    # Persist it next to the DB so every gunicorn worker shares one key. O_EXCL
    # makes creation atomic when several workers start at once.
    print("WARNING: SECRET_KEY not set; using a generated key stored in the data dir.", flush=True)
    key_path = os.path.join(os.path.dirname(DB_PATH) or ".", ".secret_key")
    try:
        os.makedirs(os.path.dirname(key_path) or ".", exist_ok=True)
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_hex(32))
    except FileExistsError:
        pass
    for _ in range(50):  # another worker may still be writing it
        with open(key_path) as f:
            _secret = f.read().strip()
        if _secret:
            break
        time.sleep(0.05)
    if not _secret:
        raise RuntimeError("Could not read generated secret key")
app.secret_key = _secret
app.config.update(
    MAX_CONTENT_LENGTH=64 * 1024,
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=os.environ.get("SESSION_COOKIE_SECURE", "").lower() in ("1", "true", "yes"),
)

MAX_NAME = 120
MAX_EMAIL = 254
MAX_PHONE = 32
MAX_QUERY = 100
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PHONE_RE = re.compile(r"^[0-9+()\-.\s]*$")


def csrf_token():
    token = session.get("_csrf")
    if not token:
        token = secrets.token_urlsafe(32)
        session["_csrf"] = token
    return token


app.jinja_env.globals["csrf_token"] = csrf_token


@app.before_request
def csrf_protect():
    if request.method in ("POST", "PUT", "PATCH", "DELETE"):
        sent = request.form.get("_csrf", "")
        expected = session.get("_csrf", "")
        if not expected or not hmac.compare_digest(sent, expected):
            abort(400, description="Invalid or missing CSRF token")


@app.after_request
def security_headers(resp):
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("X-Frame-Options", "DENY")
    resp.headers.setdefault("Referrer-Policy", "same-origin")
    resp.headers.setdefault(
        "Content-Security-Policy",
        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
        "frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    )
    return resp


class DB:
    """Tiny wrapper so the same SQL (with ? placeholders) works on SQLite and Postgres."""

    def __init__(self):
        if USE_POSTGRES:
            import psycopg
            from psycopg.rows import dict_row

            self.conn = psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=10)
        else:
            os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
            self.conn = sqlite3.connect(DB_PATH)
            self.conn.row_factory = sqlite3.Row

    def execute(self, sql, params=()):
        if USE_POSTGRES:
            sql = sql.replace("?", "%s")
        return self.conn.execute(sql, params)

    def commit(self):
        self.conn.commit()

    def close(self):
        self.conn.close()


def get_db():
    if "db" not in g:
        g.db = DB()
    return g.db


@app.teardown_appcontext
def close_db(_exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db(retries=10, delay=3):
    id_col = "SERIAL PRIMARY KEY" if USE_POSTGRES else "INTEGER PRIMARY KEY AUTOINCREMENT"
    for attempt in range(1, retries + 1):
        db = None
        try:
            db = DB()
            db.execute(
                f"""CREATE TABLE IF NOT EXISTS items (
                    id {id_col},
                    name TEXT NOT NULL,
                    email TEXT NOT NULL,
                    phone TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )"""
            )
            db.commit()
            print(f"Database ready ({'postgres' if USE_POSTGRES else 'sqlite'})", flush=True)
            return
        except Exception as exc:  # DB may not be up yet
            print(f"DB init attempt {attempt}/{retries} failed: {exc}", flush=True)
            if attempt == retries:
                raise
            time.sleep(delay)
        finally:
            if db is not None:
                db.close()


@app.route("/")
def index():
    q = request.args.get("q", "").strip()[:MAX_QUERY]
    db = get_db()
    if q:
        like = f"%{q}%"
        rows = db.execute(
            "SELECT * FROM items WHERE name LIKE ? OR email LIKE ? OR phone LIKE ? ORDER BY id DESC",
            (like, like, like),
        ).fetchall()
    else:
        rows = db.execute("SELECT * FROM items ORDER BY id DESC").fetchall()
    return render_template("index.html", items=rows, q=q)


def _form_data():
    name = request.form.get("name", "").strip()
    email = request.form.get("email", "").strip()
    phone = request.form.get("phone", "").strip()
    return name, email, phone


def _validate(name, email, phone):
    if not name or not email:
        return "Name aur Email required hain."
    if len(name) > MAX_NAME or len(email) > MAX_EMAIL or len(phone) > MAX_PHONE:
        return "Input bahut lamba hai."
    if not EMAIL_RE.match(email):
        return "Email sahi nahi hai."
    if not PHONE_RE.match(phone):
        return "Phone mein sirf digits, +, -, ( ) allowed hain."
    return None


@app.route("/create", methods=["GET", "POST"])
def create():
    if request.method == "POST":
        name, email, phone = _form_data()
        error = _validate(name, email, phone)
        if error:
            flash(error, "error")
            return render_template("form.html", item=request.form, action="Create")
        db = get_db()
        db.execute("INSERT INTO items (name, email, phone) VALUES (?, ?, ?)", (name, email, phone))
        db.commit()
        flash("Contact add ho gaya!", "success")
        return redirect(url_for("index"))
    return render_template("form.html", item={}, action="Create")


@app.route("/edit/<int:item_id>", methods=["GET", "POST"])
def edit(item_id):
    db = get_db()
    item = db.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()
    if item is None:
        flash("Contact nahi mila.", "error")
        return redirect(url_for("index"))
    if request.method == "POST":
        name, email, phone = _form_data()
        error = _validate(name, email, phone)
        if error:
            flash(error, "error")
            return render_template("form.html", item=request.form, action="Update")
        db.execute(
            "UPDATE items SET name = ?, email = ?, phone = ? WHERE id = ?",
            (name, email, phone, item_id),
        )
        db.commit()
        flash("Contact update ho gaya!", "success")
        return redirect(url_for("index"))
    return render_template("form.html", item=item, action="Update")


@app.route("/delete/<int:item_id>", methods=["POST"])
def delete(item_id):
    db = get_db()
    db.execute("DELETE FROM items WHERE id = ?", (item_id,))
    db.commit()
    flash("Contact delete ho gaya.", "success")
    return redirect(url_for("index"))


@app.route("/health")
def health():
    try:
        get_db().execute("SELECT 1")
        return {"status": "ok", "db": "postgres" if USE_POSTGRES else "sqlite"}
    except Exception as exc:
        # Don't leak connection strings / hostnames to unauthenticated callers.
        print(f"Health check failed: {exc}", flush=True)
        return {"status": "error"}, 503


init_db()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
