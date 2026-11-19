import os
import sqlite3
from flask import Flask, render_template, request, redirect, url_for, flash, g

DB_PATH = os.environ.get("DB_PATH", "/data/app.db")

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "dev-secret-change-me")


def get_db():
    if "db" not in g:
        os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(_exc):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    with app.app_context():
        get_db().execute(
            """CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT NOT NULL,
                phone TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )"""
        )
        get_db().commit()


@app.route("/")
def index():
    q = request.args.get("q", "").strip()
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


@app.route("/create", methods=["GET", "POST"])
def create():
    if request.method == "POST":
        name, email, phone = _form_data()
        if not name or not email:
            flash("Name aur Email required hain.", "error")
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
        if not name or not email:
            flash("Name aur Email required hain.", "error")
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
    return {"status": "ok"}


init_db()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
