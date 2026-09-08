# Macro function. Executed "as/at" the marker item entity, with its custom_data
# (containing RpgLogId) supplied as the macro source.
$execute at @s run function rpgcore:treefell/expand_neighbors_base {id:"$(RpgLogId)"}
kill @s
