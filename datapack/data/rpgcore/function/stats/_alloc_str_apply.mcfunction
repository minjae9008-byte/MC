scoreboard players remove @s rpgcore.points 1
scoreboard players add @s rpgcore.str 1
function rpgcore:stats/recalc
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"STR +1 -> ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.str"},"color":"aqua"}]
