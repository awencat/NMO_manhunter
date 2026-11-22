__config() -> {
    'scope' -> 'global',
    'command_permission' -> 'players',
    'commands' -> {
        '' -> '_help',
        '<teammate>' -> '_teleport'
    },
    'arguments' -> {
        'teammate' -> {'type' -> 'term', 'suggester' -> _(args) -> map(player('all'), query(_, 'name'))}
    }
};

_help() -> _teleport('');

_teleport(name) -> (
    actor = system_info('source_entity');
    if(actor == null || query(actor, 'type') != 'player', return(null));
    delivered = signal_event('sr_team_tp', null, {'sender_uuid' -> query(actor, 'uuid'), 'name' -> name});
    if(!delivered, print(actor, '活动脚本尚未加载，请联系 OP。'));
    null
);
