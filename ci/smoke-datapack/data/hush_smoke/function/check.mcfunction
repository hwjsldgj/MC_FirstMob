execute if entity @e[type=hush:yao_yongshou] run say HUSH_SMOKE_MOB_OK
execute unless entity @e[type=hush:yao_yongshou] run say HUSH_SMOKE_MOB_MISSING
execute if entity @e[type=hush:yao_spit] run say HUSH_SMOKE_SPIT_OK
execute unless entity @e[type=hush:yao_spit] run say HUSH_SMOKE_SPIT_MISSING
say HUSH_SMOKE_DONE
