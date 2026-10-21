**Illustrated tutorial:** the book button in the table's top-left corner opens the screenshot tour; turn pages with the arrow keys, the arrow buttons or by clicking the left/right half of the picture. This page is its "Text rules" version (the screenshots are currently Chinese-only).

### **Objective:**

Avalon is a hidden-identity deduction game for the round table. Good and Evil want opposite things:

- **Good** wins by making **three quests succeed** and then keeping the Allay alive.
- **Evil** wins by sabotaging **three quests**, or - once Good has taken three - by having the **Creeper strike the Allay**.

### **Sides and Roles:**

Everyone is dealt one role card that only they can see.

- **Good:** Allay, Librarian, Loyalist
    - **Allay** knows every evil seat, but must not be spotted by the Creeper.
    - **Librarian** knows who the Allay is - but **Illusioner** appears as the Allay to him, so he has to tell them apart.
    - **Loyalist** has no extra information and wins by deduction and by their vote.
- **Evil:** Creeper, Illusioner, Ender Dragon, Phantom, Zombie
    - **Creeper** gets one shot at the Allay once Good takes three quests.
    - **Illusioner** appears to the Librarian as the Allay.
    - **Ender Dragon** is invisible to the Allay.
    - **Phantom** knows neither the other evil nor is known by them - but the Allay can see him.
    - **Zombie** sabotages alongside their allies.

### **How a game runs:**

1.  **Propose:** the leader (rotating each round) names the team this quest needs. The size is fixed by the round.
2.  **Vote:** everyone plays **approve** or **reject** onto the pile. A majority approves and the team departs; otherwise the lead passes on, and five rejects in a row hand the game to Evil.
3.  **Run:** each team member plays **success** or **sabotage**. Good can only play success; Evil may pick.
4.  The quest is turned over and the next round begins. Three successes send Good into the **assassination**; three failures win it for Evil.

### **Building the table:**

The table can be laid out 3×3, 4×3, 5×3, 4×4, 4×5 or 5×5; take a seat and right-click the surface to start. For ten players use 5×5 with the chairs around the edge — a deeper top keeps the far side from sitting on top of each other.

### **How to interact at the table:**

- **Propose** and **assassinate** are action turns: **click a seat's plate** to pick, then press **Confirm**.
- **Vote** and **run** are card turns: **drag the matching card onto the pile** in the middle.

### **The mission board:**

The board in the middle records all five quests: as each one resolves, a **success** or **sabotage** seal is pressed onto its slot. The five notches at the board's lower left are the run of rejects, and an iron nugget hops to the matching notch whenever a team is turned down.

### **Optional rule: designated quests**

Turn on **Designated quests** in the table options before the game starts. Then, on every proposal:

- The leader **picks the quest on the mission board first**, then builds the team; the team size comes from that quest.
- A completed quest cannot be picked again; quest 5 needs at least two successes first.
- On 7+ players, quest 4 still needs two fail cards to fail.
- Lit slots are the available quests; the solid highlight is the leader's current pick.

### **Win-rate records:**

The server records the result of every finished game for every real player in `myavalon-stats.json` in the world folder. **A game abandoned before a side wins is not counted, and neither is a game with even one bot seat.**

Put online player names or `@a` right after `rate` to show the whole answer only to them; omit it to show it to yourself. For example, `/myavalon rate Steve total` shows the overall ranking to Steve alone, and `/myavalon rate @a total` shows it to everyone.

- `/myavalon rate personal [player]`: one player's record, with overall, evil, good and per-role win rates. The player can be a UUID or a name; omit it for yourself.
- `/myavalon rate total [count]`: the overall ranking, showing the overall rate only.
- `/myavalon rate good [count]` / `/myavalon rate evil [count]`: the Good / Evil faction ranking.
- `/myavalon rate role <mob id> [count]`: one identity's ranking.
- Mob ids: `allay` (Allay), `librarian` (Librarian), `villager` (Villager), `creeper` (Creeper), `illusioner` (Illusioner), `ender_dragon` (Ender Dragon), `phantom` (Phantom), `zombie` (Zombie).
- The Librarian is a villager profession rather than a separate entity, so it uses `librarian` to stay distinct from the plain Villager's `villager`.

### **Notes:**

Rules marked with an asterisk are configured by whoever starts the game, from the table options. Check the current settings before you begin. Bot fill-in is off by default; turn it on in the options if you need it. The identity setup lists the roles and counts for the people actually seated.
