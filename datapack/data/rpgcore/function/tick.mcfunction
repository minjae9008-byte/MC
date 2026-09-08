# RPGCore - runs every tick (added to #minecraft:tick)

execute as @a run function rpgcore:player/tick

# throttle weight recalculation to once every 10 ticks (0.5s) for all players at once
execute store result score $gt rpgcore.io run time query gametime
scoreboard players operation $gt rpgcore.io %= $ten rpgcore.const
execute if score $gt rpgcore.io matches 0 run execute as @a run function rpgcore:weight/tick

# HUD refresh, throttled to every 5 ticks (4x/sec). The action bar holds its
# text for ~3s, so this looks identical on Java while cutting the translated
# title packets Geyser has to send to Bedrock players by 5x.
execute store result score $gt5 rpgcore.io run time query gametime
scoreboard players operation $gt5 rpgcore.io %= $five rpgcore.const
execute if score $gt5 rpgcore.io matches 0 run execute as @a run function rpgcore:ui/hud_tick

# voice proximity hint, throttled to once a second (20 ticks)
execute store result score $gt20 rpgcore.io run time query gametime
scoreboard players operation $gt20 rpgcore.io %= $twenty rpgcore.const
execute if score $gt20 rpgcore.io matches 0 run execute as @a run function rpgcore:voice/tick

function rpgcore:treefell/tick
