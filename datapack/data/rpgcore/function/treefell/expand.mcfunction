# Executed "positioned" at a candidate block. If it's still the same log type,
# break it and keep growing from there. Blocks that were already broken read as
# air and simply fail the check, which doubles as our "visited" guard - no
# separate visited-set bookkeeping needed.
$execute if block ~ ~ ~ $(id) run function rpgcore:treefell/break_and_expand {id:"$(id)"}
