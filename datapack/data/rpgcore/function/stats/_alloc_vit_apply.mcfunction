scoreboard players remove @s rpgcore.points 1
scoreboard players add @s rpgcore.vit 1
function rpgcore:stats/recalc
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"VIT +1 -> ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.vit"},"color":"aqua"}]
