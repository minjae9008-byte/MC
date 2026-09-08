execute if score @s rpgcore.points matches 1.. run function rpgcore:stats/_alloc_dex_apply
execute unless score @s rpgcore.points matches 1.. run tellraw @s {"text":"[RPGCore] 사용 가능한 스탯 포인트가 없습니다.","color":"red"}
