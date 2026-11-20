summon hush:yao_yongshou ~ ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:["hush_smoke"]}
summon hush:yao_spit ~ ~3 ~
say HUSH_SMOKE_SUMMONED
schedule function hush_smoke:check 5t
