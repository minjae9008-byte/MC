# Runs once per player (guarded by rpgcore.init flag). Extend freely for new stats.
execute unless score @s rpgcore.init matches 1 run function rpgcore:player/first_join
