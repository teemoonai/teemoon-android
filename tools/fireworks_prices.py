#!/usr/bin/env python3
"""Check the hand-kept Fireworks price table against what Fireworks publishes.

    python3 tools/fireworks_prices.py            # report; exit 1 on drift
    python3 tools/fireworks_prices.py --offline PAGE.html MODELS.json

Fireworks serves no price over any API (iOS tools/update_providers.py, checked 2026-07-26), so
`FireworksAdapter.fireworksPrices` is read off https://docs.fireworks.ai/serverless/pricing by hand.
iOS's tool verifies that each priced SLUG still exists; it never checks the NUMBERS, and it never asks
which live models have no price. Both drifted unseen: on 2026-10-02 deepseek-v4p1-flash shipped
$0.22/$0.66 against the page's $0.30/$1.20, and ember-1 was live and priced on the page with no row
here. This checks the other two directions:

  * DRIFT    — a row whose "$in/$out" differs from the page's Standard column
  * UNPRICED — a live serverless CHAT model with no row (embeddings and rerankers are not listed)

A model the page omits is reported, never guessed: its own model page is the next place to look,
and an empty price shows nothing rather than a wrong number. Developer-machine only: the app's
network destinations are unchanged. The control-plane list needs ~/.FIREWORKS_API_KEY (never printed).
"""

import argparse
import json
import os
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
TABLE = ROOT / "providers/src/main/kotlin/ai/teemoon/providers/FireworksAdapter.kt"
PAGE = "https://docs.fireworks.ai/serverless/pricing"
MODELS = "https://api.fireworks.ai/v1/accounts/fireworks/models?pageSize=200&filter=supports_serverless=true"
NON_CHAT = re.compile(r"embed|rerank|whisper|flux|stable-diffusion|tts", re.I)


def shipped() -> dict:
    return dict(re.findall(r'"accounts/fireworks/models/([^"]+)" to "([^"]+)"', TABLE.read_text()))


def page_prices(html: str) -> dict:
    """slug -> "$in/$out" from the Standard column ("input / cached input / output")."""
    out = {}
    for slug, cell in re.findall(r'models/fireworks/([a-z0-9p-]+)`,children:`[^`]+`\}\)\}\),_jsx\(_components\.td,'
                                 r'\{\\"data-numeric\\":`true`,children:`([^`]+)`', html):
        parts = [p.strip() for p in cell.split("/")]
        if len(parts) >= 2 and slug not in out:
            out[slug] = f"{parts[0]}/{parts[-1]}"
    return out


def live_chat_models(records: list) -> list:
    return sorted(r["name"].split("/")[-1] for r in records
                  if r.get("state") in (None, "", "READY") and not r.get("deprecationDate") and not NON_CHAT.search(r["name"]))


def fetch_records() -> list:
    key = (pathlib.Path.home() / ".FIREWORKS_API_KEY").read_text().strip()
    records, token = [], ""
    while True:
        req = urllib.request.Request(MODELS + (f"&pageToken={token}" if token else ""), headers={"Authorization": f"Bearer {key}"})
        d = json.load(urllib.request.urlopen(req, timeout=30))
        records += d.get("models", [])
        token = d.get("nextPageToken")
        if not token:
            return records


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--offline", nargs=2, metavar=("PAGE_HTML", "MODELS_JSON"))
    a = ap.parse_args()
    if a.offline:
        html = pathlib.Path(a.offline[0]).read_text()
        records = json.loads(pathlib.Path(a.offline[1]).read_text())["models"]
    else:
        html = urllib.request.urlopen(PAGE, timeout=30).read().decode()
        records = fetch_records()
    table, page, live = shipped(), page_prices(html), live_chat_models(records)
    if not page:
        print("the pricing page parsed to nothing — its markup changed; fix page_prices before trusting a clean run")
        return 2
    problems = 0
    for slug, price in sorted(page.items()):
        if slug in table and table[slug] != price:
            print(f"DRIFT     {slug}: ships {table[slug]}, page says {price}"); problems += 1
    for slug in live:
        if slug not in table:
            if slug in page:
                print(f"UNPRICED  {slug}: page says {page[slug]}"); problems += 1
            else:   # nothing published to copy: the row stays priceless rather than guessed
                print(f"note      {slug}: live, no price on the page — check its model page by hand")
    print(f"{len(table)} priced, {len(page)} on the page, {len(live)} live chat models — "
          + ("clean" if not problems else f"{problems} to fix in FireworksAdapter.fireworksPrices (and iOS KnownModel.fireworksPrices)"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
