"""Writes the Avalon deck JSON.

Kept as a script rather than hand-written because the deck is fifty entries long and every one of them
repeats the same three keys; a typo in a card's ``texture`` is otherwise only visible as Charta
substituting its own ``missing_card`` art at runtime.

    python tools/make_deck.py
"""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "data", "trustnoone", "decks")

SUITS = {
    "good": ("trustnoone:good", "suit.trustnoone.good"),
    "evil": ("trustnoone:evil", "suit.trustnoone.evil"),
    "vote": ("trustnoone:vote", "suit.trustnoone.vote"),
    "quest": ("trustnoone:quest", "suit.trustnoone.quest"),
}

# Role card: (role, suit, how many copies the deck holds). The counts are the worst case over a full
# ten-player table, so one deck always seats a whole party.
ROLES = [
    ("merlin", "good", 1),
    ("percival", "good", 1),
    ("loyalist", "good", 4),
    ("assassin", "evil", 1),
    ("morgana", "evil", 1),
    ("mordred", "evil", 1),
    ("oberon", "evil", 1),
    ("minion", "evil", 1),
]

# Vote and quest card: (rank, suit, texture stem, copies). One of each per seat is dealt, so ten copies
# covers the largest table.
FACES = [
    ("yes", "vote", "vote_yes", 10),
    ("no", "vote", "vote_no", 10),
    ("success", "quest", "quest_success", 10),
    ("fail", "quest", "quest_fail", 10),
]

RANK_OFFSET = {"role": 100, "vote": 200, "quest": 300}


def card_entry(suit, rank, texture, translation):
    return {
        "card": {
            "components": {
                "charta:suit": suit,
                "charta:rank": rank,
                "charta:flipped": False,
            }
        },
        "texture": texture,
        "translation": translation,
    }


def build(name):
    cards = []
    for role, suit, copies in ROLES:
        for _ in range(copies):
            cards.append(card_entry(
                SUITS[suit][0],
                f"trustnoone:{role}",
                f"trustnoone:role/{role}",
                f"card.trustnoone.{role}",
            ))
    for rank, suit, texture, copies in FACES:
        for _ in range(copies):
            cards.append(card_entry(
                SUITS[suit][0],
                f"trustnoone:{rank}",
                f"trustnoone:{texture}",
                f"card.trustnoone.{texture}",
            ))

    deck = {
        "rarity": "common",
        "tradeable": True,
        "texture": "trustnoone:avalon",
        "translation": f"deck.trustnoone.{name}",
        "suits": [
            {"suit": sid, "texture": f"trustnoone:{key}", "translation": key_}
            for key, (sid, key_) in SUITS.items()
        ],
        "cards": cards,
    }
    return deck


def main():
    os.makedirs(OUT, exist_ok=True)
    for name in ("avalon",):
        path = os.path.join(OUT, f"{name}.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(build(name), handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
