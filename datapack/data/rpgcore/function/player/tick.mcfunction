function rpgcore:player/ensure_init

# --- trigger-based UI actions (fired via /trigger rpgcore.<name>) ---
execute if score @s rpgcore.menu matches 1.. run function rpgcore:ui/menu
execute if score @s rpgcore.menu matches 1.. run scoreboard players set @s rpgcore.menu 0

execute if score @s rpgcore.alloc_str matches 1.. run function rpgcore:stats/alloc_str
execute if score @s rpgcore.alloc_str matches 1.. run scoreboard players set @s rpgcore.alloc_str 0

execute if score @s rpgcore.alloc_dex matches 1.. run function rpgcore:stats/alloc_dex
execute if score @s rpgcore.alloc_dex matches 1.. run scoreboard players set @s rpgcore.alloc_dex 0

execute if score @s rpgcore.alloc_vit matches 1.. run function rpgcore:stats/alloc_vit
execute if score @s rpgcore.alloc_vit matches 1.. run scoreboard players set @s rpgcore.alloc_vit 0

execute if score @s rpgcore.alloc_agi matches 1.. run function rpgcore:stats/alloc_agi
execute if score @s rpgcore.alloc_agi matches 1.. run scoreboard players set @s rpgcore.alloc_agi 0

execute if score @s rpgcore.alloc_luck matches 1.. run function rpgcore:stats/alloc_luck
execute if score @s rpgcore.alloc_luck matches 1.. run scoreboard players set @s rpgcore.alloc_luck 0
