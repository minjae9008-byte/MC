# Pure-datapack proximity indicator, throttled to once a second by rpgcore:tick.
# A vanilla datapack cannot read/route chat or audio packets itself, so this
# only flags "someone is close enough to talk to" - the actual proximity text
# chat (and the real proximity VOICE chat, via Simple Voice Chat) is handled
# server-side by the Paper companion plugin in plugin/, which reads the same
# $voice_range constant (rpgcore.const) so both stay in sync from one place.
execute store result storage rpgcore:calc range int 1 run scoreboard players get $voice_range rpgcore.const
function rpgcore:voice/tick_scan with storage rpgcore:calc
