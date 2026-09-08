scoreboard players remove @s rpgcore.points 1
scoreboard players add @s rpgcore.agi 1
function rpgcore:stats/recalc
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"AGI +1 -> ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.agi"},"color":"aqua"}]
