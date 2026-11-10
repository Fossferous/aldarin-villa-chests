# Aldarin Villa Chests

Shows exactly when to click the thieving chests in the Aldarin villas (Varlamore, 36 Thieving).

Each chest is coloured by what the game is doing with it:

| Colour | Meaning |
| --- | --- |
| Green | Ready. Clicking now starts a new attempt. |
| Orange | Your current attempt is still running. The number is the game ticks left. |
| Red | The chest was just looted (by you or anyone else) and is open. The number is the game ticks until it closes. |

Everything comes from the game itself, one game tick (0.6 seconds) at a time:

- **Attempt:** starts when your character starts the picklock animation and ends 2 ticks later with
  "You fail to picklock the chest" or "You manage to unlock the chest". Clicks during an attempt are ignored by the game,
  so the chest stays orange until the result.
- **Success:** the chest opens 1 tick after the message and closes 2 ticks later. It is red from the message until it
  has closed, because clicks on it do nothing until then.
- **Trap:** after "You have activated a trap on the chest" you are teleported outside; the chest stays orange while it happens.
- **Other players:** their loots open the chest the same way and show red with a countdown. A click that lands while
  the chest is open is dropped by the game, and the plugin knows that.
- **Countdowns** use the times the plugin last measured, so they adjust themselves if Jagex changes the timing.

## Settings

- Ready, picking and open colours
- Tick countdown on or off
- Fill the clickbox, or outline only
- Outline width
- Ready notification: when the chest you are picking becomes ready to click again (off by default)
- Trap notification: when a trap teleports you out, so you head back up the stairs (off by default)

Both notifications use RuneLite's own notification settings, so they can play a sound, flash the screen or send a
desktop notification.
