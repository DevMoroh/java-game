#!/usr/bin/env python3
"""
Генератор статей золотих яєць: за кожен запуск (cron: зранку й увечері) пише одну нову статтю-розбір і додає її в гру через закритий
адмін-API (AdminArticles, /api/admin/articles на 127.0.0.1).

  1. Бере з API вже наявні статті — щоб обрати найменш покриту тему й не повторити кейс.
  2. Автор (claude -p з prompts/writer.md) пише статтю у форматі «питання зі співбесіди → розбір → варіанти → висновок».
  3. Рецензент (окремий claude -p з prompts/reviewer.md) перевіряє формат і стиль за чеклістом.
     Не пройшла — автор переписує з зауваженнями (до MAX_ATTEMPTS спроб).
  4. Стаття проходить перевірку — POST в API; гра підхоплює її без перезапуску.

Claude тут без жодних інструментів (--tools ""): лише повертає JSON за схемою. До API ходить сам скрипт.

Запуск:  ./generate.py [--dry-run] [--topic Databases] [--env-file /opt/devworld/.env]
Змінні:  GAME_ADMIN_TOKEN (або з --env-file), EGGS_API (http://127.0.0.1:8090), CLAUDE_BIN (claude),
         EGGS_MODEL (opus), EGGS_REVIEW_MODEL (як EGGS_MODEL).
Claude викликається від залогіненого акаунта CLI (claude.ai); ANTHROPIC_API_KEY з оточення навмисно прибирається.
"""

import argparse
import json
import os
import random
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOPICS = ["Databases", "Architecture", "Security", "DevOps", "Java", "Frontend", "Testing", "Git"]
MAX_ATTEMPTS = 3
SECTIONS = ["## Розбір", "## Варіанти рішення", "## Висновок"]

ARTICLE_SCHEMA = {
    "type": "object",
    "properties": {
        "id": {"type": "string"},
        "topic": {"type": "string"},
        "subject": {"type": "string"},
        "title": {"type": "string"},
        "body": {"type": "string"},
    },
    "required": ["id", "topic", "subject", "title", "body"],
}
REVIEW_SCHEMA = {
    "type": "object",
    "properties": {
        "ok": {"type": "boolean"},
        "problems": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["ok", "problems"],
}


def log(msg):
    print(f"{datetime.now():%Y-%m-%d %H:%M:%S} {msg}", flush=True)


def read_env_file(path):
    values = {}
    try:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    except OSError as e:
        sys.exit(f"Cannot read {path}: {e}")
    return values


class Api:
    def __init__(self, base, token):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.status, json.loads(resp.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"null")


def claude(system_file, prompt, schema, model):
    cmd = [
        os.environ.get("CLAUDE_BIN", "claude"), "-p",
        "--model", model,
        "--tools", "",                 # без інструментів: лише текст за схемою
        "--strict-mcp-config", "--setting-sources", "",
        "--no-session-persistence",
        "--output-format", "json",
        "--json-schema", json.dumps(schema),
        "--system-prompt", (HERE / "prompts" / system_file).read_text(encoding="utf-8"),
    ]
    # Лише залогінений акаунт CLI (підписка): ключ API в оточенні мав би пріоритет і оплачувався б окремо.
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    run = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=900, env=env)
    try:
        out = json.loads(run.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"claude failed (exit {run.returncode}): {run.stderr.strip() or run.stdout.strip()[:500]}")
    if out.get("is_error") or out.get("structured_output") is None:
        raise RuntimeError(f"claude error: {out.get('subtype')} {str(out.get('result'))[:500]}")
    # total_cost_usd — оцінка CLI за цінами API; на підписці не списується, лише йде в ліміти плану.
    log(f"  claude {system_file}: done (CLI estimate ${out.get('total_cost_usd', 0):.2f} at API prices)")
    return out["structured_output"]


def normalize(text):
    return re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).strip()


def local_problems(article, topic, existing):
    """Механічні перевірки, які не варто довіряти моделі: формат id, рубрика, розділи, довжини, дублікати."""
    problems = []
    body = article["body"].strip()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,59}", article["id"]):
        problems.append("id: 3-60 символів, лише a-z, 0-9 і дефіси")
    if article["topic"] != topic:
        problems.append(f"topic має бути саме «{topic}»")
    header = f"**{topic}**"
    if not body.startswith(header):
        problems.append(f"body має починатися рядком {header}")
    positions = [body.find(s) for s in SECTIONS]
    if -1 in positions or positions != sorted(positions):
        problems.append("потрібні розділи " + ", ".join(SECTIONS) + " саме в такому порядку")
    else:
        situation = body[len(header):positions[0]].strip()
        if not 120 <= len(situation) <= 450:
            problems.append(f"ситуація (питання) має 150–400 символів, зараз {len(situation)}")
        if "?" not in situation[-120:]:
            problems.append("ситуація має закінчуватися коротким відкритим питанням")
    if not 2000 <= len(body) <= 8000:
        problems.append(f"body має ~2 500–6 000 символів, зараз {len(body)}")
    if len(article["title"]) > 90:
        problems.append("title до 90 символів")
    if re.search(r"<[a-z/][^>]*>|!\[|\]\(|^\s*\|.*\|\s*$", body, re.M):
        problems.append("без HTML, картинок, посилань і таблиць")
    taken = {normalize(a.get("subject")) for a in existing} | {a["id"] for a in existing}
    if normalize(article["subject"]) in taken or article["id"] in taken:
        problems.append("такий subject або id уже є — обери інший кейс")
    return problems


def pick_topic(existing, forced):
    if forced:
        return forced
    counts = {t: 0 for t in TOPICS}
    for a in existing:
        if a.get("topic") in counts:
            counts[a["topic"]] += 1
    least = min(counts.values())
    return random.choice([t for t, n in counts.items() if n == least])


def writer_prompt(topic, used, previous=None, problems=None):
    lines = [
        f"Тема: {topic}",
        "",
        "Уже використані кейси (не повторюй їх за суттю, навіть іншими словами):",
        *([f"- {u}" for u in used] or ["- (поки жодного)"]),
        "",
        "Напиши одну нову статтю-яйце за інструкцією. Обери реальний, поширений у командах інцидент саме з цієї теми.",
    ]
    if previous:
        lines += [
            "",
            "Попередня версія не пройшла перевірку. Виправ усі зауваження й поверни статтю повністю:",
            *[f"- {p}" for p in problems],
            "",
            "Попередня версія:",
            json.dumps(previous, ensure_ascii=False),
        ]
    return "\n".join(lines)


def review_prompt(article, used):
    return "\n".join([
        "Уже використані кейси:",
        *([f"- {u}" for u in used] or ["- (поки жодного)"]),
        "",
        "Стаття на перевірку:",
        json.dumps(article, ensure_ascii=False, indent=2),
    ])


def main():
    parser = argparse.ArgumentParser(description="Generate one golden-egg article and publish it to the game")
    parser.add_argument("--dry-run", action="store_true", help="print the article instead of publishing it")
    parser.add_argument("--topic", choices=TOPICS)
    parser.add_argument("--env-file", help="read GAME_ADMIN_TOKEN from this .env (e.g. /opt/devworld/.env)")
    args = parser.parse_args()

    env = read_env_file(args.env_file) if args.env_file else {}
    token = os.environ.get("GAME_ADMIN_TOKEN") or env.get("GAME_ADMIN_TOKEN")
    if not token:
        sys.exit("GAME_ADMIN_TOKEN is not set (env or --env-file)")
    api = Api(os.environ.get("EGGS_API", "http://127.0.0.1:8090"), token)
    model = os.environ.get("EGGS_MODEL", "opus")
    review_model = os.environ.get("EGGS_REVIEW_MODEL", model)

    status, existing = api.call("GET", "/api/admin/articles")
    if status != 200:
        sys.exit(f"Admin API: HTTP {status} {existing}")
    topic = pick_topic(existing, args.topic)
    used = [f"{a.get('subject') or a['id']} — {a['title']}" for a in existing]
    log(f"{len(existing)} articles so far; topic: {topic}")

    article, problems = None, []
    for attempt in range(1, MAX_ATTEMPTS + 1):
        article = claude("writer.md", writer_prompt(topic, used, article, problems), ARTICLE_SCHEMA, model)
        article = {k: str(v).strip() for k, v in article.items()}
        problems = local_problems(article, topic, existing)
        review = claude("reviewer.md", review_prompt(article, used), REVIEW_SCHEMA, review_model)
        problems += [p for p in review["problems"] if p.strip()] if not review["ok"] else []
        log(f"attempt {attempt}: «{article['title']}» ({article['subject']}) — "
            + ("OK" if not problems else f"{len(problems)} problems"))
        for p in problems:
            log(f"  - {p}")
        if not problems:
            break
    else:
        sys.exit("Article did not pass the review — nothing published")

    if args.dry_run:
        print(json.dumps(article, ensure_ascii=False, indent=2))
        return
    status, body = api.call("POST", "/api/admin/articles", article)
    if status != 201:
        sys.exit(f"Publish failed: HTTP {status} {body}")
    log(f"published: {article['id']}")


if __name__ == "__main__":
    main()
