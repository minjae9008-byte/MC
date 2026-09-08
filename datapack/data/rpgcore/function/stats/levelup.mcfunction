scoreboard players operation @s rpgcore.xp -= @s rpgcore.xp_need
scoreboard players add @s rpgcore.level 1
scoreboard players add @s rpgcore.points 1

# xp_need = xp_base + (level - 1) * xp_growth
scoreboard players operation @s rpgcore.xp_need = @s rpgcore.level
scoreboard players remove @s rpgcore.xp_need 1
scoreboard players operation @s rpgcore.xp_need *= $xp_growth rpgcore.const
scoreboard players operation @s rpgcore.xp_need += $xp_base rpgcore.const

function rpgcore:stats/recalc

playsound minecraft:entity.player.levelup player @s ~ ~ ~ 1 1
title @s title {"text":"LEVEL UP!","color":"gold","bold":true}
title @s subtitle ["",{"text":"Lv.","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.level"},"color":"yellow"}]
tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"레벨업! 스탯 포인트 +1 (남은 포인트: ","color":"yellow"},{"score":{"name":"@s","objective":"rpgcore.points"},"color":"aqua"},{"text":")","color":"yellow"}]

# handle multi-level gains from a single large XP reward
function rpgcore:stats/levelup_check
