scoreboard players remove @s rpgcore.points 1
scoreboard players add @s rpgcore.luck 1
function rpgcore:stats/recalc
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"LUCK +1 -> ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.luck"},"color":"aqua"}]
