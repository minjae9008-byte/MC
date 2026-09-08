scoreboard players set @s rpgcore.init 1

scoreboard players set @s rpgcore.level 1
scoreboard players set @s rpgcore.xp 0
scoreboard players operation @s rpgcore.xp_need = $xp_base rpgcore.const

scoreboard players set @s rpgcore.points 5
scoreboard players set @s rpgcore.str 0
scoreboard players set @s rpgcore.dex 0
scoreboard players set @s rpgcore.vit 0
scoreboard players set @s rpgcore.agi 0
scoreboard players set @s rpgcore.luck 0

scoreboard players set @s rpgcore.menu 0
scoreboard players set @s rpgcore.alloc_str 0
scoreboard players set @s rpgcore.alloc_dex 0
scoreboard players set @s rpgcore.alloc_vit 0
scoreboard players set @s rpgcore.alloc_agi 0
scoreboard players set @s rpgcore.alloc_luck 0

function rpgcore:stats/recalc

tellraw @s [{"text":"[RPGCore] ","color":"gold"},{"text":"환영합니다! 레벨 1로 시작합니다. 스탯 포인트 5개를 보유 중입니다.\n","color":"yellow"},{"text":"/trigger rpgcore.menu","color":"aqua"},{"text":" 명령어로 스탯 창을 여세요.","color":"yellow"}]
