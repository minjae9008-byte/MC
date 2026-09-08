# Text is kept to plain ASCII markers + Korean: Bedrock clients (Geyser) do not
# render most emoji from the Java font, so decorative emoji would show up as
# missing-glyph boxes for them.
$execute store success score @s rpgcore.voice_near if entity @a[distance=1..$(range),gamemode=survival]

execute if score @s rpgcore.voice_near matches 1 unless score @s rpgcore.voice_was_near matches 1 run tellraw @s {"text":"[VOICE] 근처에 플레이어가 있습니다. (근접 채팅 범위 진입)","color":"aqua"}
execute unless score @s rpgcore.voice_near matches 1 if score @s rpgcore.voice_was_near matches 1 run tellraw @s {"text":"[VOICE] 근접 채팅 범위를 벗어났습니다.","color":"gray"}

scoreboard players operation @s rpgcore.voice_was_near = @s rpgcore.voice_near
