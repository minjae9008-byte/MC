scoreboard players remove @s rpgcore.points 1
scoreboard players add @s rpgcore.dex 1
function rpgcore:stats/recalc
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"DEX +1 -> ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.dex"},"color":"aqua"}]
