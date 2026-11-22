// Edit this file when using start-server.ps1.
__config() -> {
    'scope' -> 'global',
    'command_permission' -> 'ops',
    'commands' -> {
        '' -> '_status',
        'status' -> '_status',
        'setlobby' -> '_set_lobby',
        'teams' -> '_show_teams',
        'team <member> a' -> ['_set_team', 'A'],
        'team <member> b' -> ['_set_team', 'B'],
        'team <member> none' -> ['_set_team', 'none'],
        'buttonteam a' -> ['_button_team_auto', 'A'],
        'buttonteam a <button>' -> ['_button_team', 'A'],
        'buttonteam b' -> ['_button_team_auto', 'B'],
        'buttonteam b <button>' -> ['_button_team', 'B'],
        'teams clear' -> '_clear_teams',
        'random' -> '_random_teams_default',
        'random <a_count>' -> '_random_teams',
        'start' -> '_start_prepared',
        'start <runner> <hunter>' -> '_start',
        'reset' -> '_reset_world',
        'cancel' -> '_cancel'
    },
    'arguments' -> {
        'member' -> {
            'type' -> 'term',
            'suggester' -> _(args) -> (
                names = {};
                for(player('all'), put(names, query(_, 'name'), true));
                for(values(global_prepared), put(names, _:'name', true));
                sort(keys(names))
            )
        },
        'a_count' -> {'type' -> 'int', 'min' -> 1},
        'button' -> {'type' -> 'pos', 'loaded' -> true},
        'runner' -> {'type' -> 'entities', 'players' -> true, 'single' -> true},
        'hunter' -> {'type' -> 'entities', 'players' -> true, 'single' -> true}
    }
};

global_config = null;
global_config_error = null;
global_recovery_error = null;
global_duration_ms = null;
global_border_size = null;
global_deadline = null;
global_started_at = null;
global_started_unix_ms = null;
global_round_id = null;
global_roster = {};
global_players = [];
global_lobby = null;
global_return_at = null;
global_displayed_seconds = null;
global_last_result = null;
global_pending_result = null;
global_next_write = 0;
global_live_dragons = {};
global_prepared = {};
global_team_ids = {'A' -> 'sr_a', 'B' -> 'sr_b'};
global_explosions = [];
global_reset_pending = false;
global_reset_at = null;
global_starting = false;
global_start_token = null;
global_drop_radius = null;
global_drop_point = null;
global_hunter_delay_ms = null;
global_hunters_released = false;
global_deployed = {};
global_tpa_cooldown_ms = null;
global_tpa_ready = {};
global_button_candidates = {};
global_button_presses = {};

__on_start() -> (
    _load_game_config();
    prepared = read_file('teams', 'json');
    if(prepared != null, global_prepared = prepared);
    global_lobby = read_file('lobby', 'json');
    previous = read_file('result', 'json');
    if(previous != null, global_last_result = previous:'message');
    active = read_file('active', 'json');
    restore = active != null && (previous == null || active:'round_id' != previous:'round_id');
    if(restore,
        if(has(active, 'config'),
            global_recovery_error = _validate_game_config(active:'config');
            if(global_recovery_error == null, _apply_game_config(active:'config'))
        ,
            if(global_config == null, global_recovery_error = '旧比赛缺少配置快照，且当前配置无法读取。')
        );
        if(global_recovery_error != null, print('无法恢复比赛配置：'+global_recovery_error))
    );
    if(restore && global_recovery_error == null,
        global_round_id = active:'round_id';
        global_started_unix_ms = active:'started_at_unix_ms';
        elapsed = max(0, unix_time() - global_started_unix_ms);
        global_started_at = time() - elapsed;
        global_duration_ms = active:'duration_limit_ms';
        global_deadline = global_started_at + global_duration_ms;
        global_roster = active:'roster';
        global_players = active:'players';
        global_drop_point = if(has(active, 'drop_point'), active:'drop_point', system_info('world_spawn_point'));
        global_hunter_delay_ms = if(has(active, 'hunter_delay_ms'), active:'hunter_delay_ms', 0);
        global_hunters_released = if(has(active, 'hunters_released'), active:'hunters_released', true);
        if(has(active, 'deployed'),
            global_deployed = active:'deployed'
        ,
            for(global_players, global_deployed:(_:'uuid') = true)
        );
        if(has(active, 'tpa_ready'), global_tpa_ready = active:'tpa_ready');
        if(has(active, 'live_dragons'), global_live_dragons = active:'live_dragons');
        print('恢复上次未结束的比赛，离线期间计入比赛时间。')
    ,
        if(global_recovery_error == null && global_lobby != null, global_return_at = time())
    );
    _sync_teams();
    handle_event('sr_team_tp', '_tpa_teleport');
    control = read_file('control', 'json');
    reset = read_file('reset-request', 'json');
    if(control != null && reset != null && control:'mode' == 'manual-reset-v1'
        && reset:'action' == 'manual_reset' && reset:'run_id' == control:'run_id',
        global_reset_pending = true;
        if(global_config != null,
            global_reset_at = time() + global_config:'reset_shutdown_delay_seconds' * 1000
        )
    );
    schedule(0, '_apply_borders');
    entity_load_handler('ender_dragon', '_watch_dragon');
    _scan_dragons();
    null
);

_validate_item_id(id) -> (
    if(type(id) != 'string', return('物品 ID 必须是字符串。'));
    if(id == 'minecraft:air', return('空槽位请删除对应配置项，不能赠送空气。'));
    if((id ~ '^[a-z0-9_.-]+:[a-z0-9_./-]+$') == null,
        return('请填写完整物品 ID，例如 minecraft:copper_sword。')
    );
    if(try(stack_limit(id), 'unknown_item', null) == null, return('不存在的物品：'+id));
    null
);

_validate_equipment(kit, field) -> (
    if(type(kit) != 'map', return(field+' 必须是对象。'));
    if(length(filter(keys(kit), !has({'armor', 'items'}, _))) > 0,
        return(field+' 只允许 armor 和 items 字段。')
    );
    if(type(kit:'armor') != 'map', return(field+'.armor 必须是对象。'));
    for(pairs(kit:'armor'),
        slot = _:0;
        if(!has({'head', 'chest', 'legs', 'feet'}, slot), return(field+'.armor 中的槽位无效：'+slot));
        error = _validate_item_id(_:1);
        if(error != null, return(field+'.armor.'+slot+'：'+error))
    );
    if(type(kit:'items') != 'list', return(field+'.items 必须是列表。'));
    if(length(kit:'items') > 36, return(field+'.items 最多允许 36 项，避免背包溢出。'));
    occupied_slots = 0;
    for(kit:'items',
        entry = _;
        if(type(entry) != 'map', return(field+'.items 中的每一项必须是对象。'));
        if(length(filter(keys(entry), !has({'item', 'count'}, _))) > 0,
            return(field+'.items 中的每一项只允许 item 和 count 字段。')
        );
        error = _validate_item_id(entry:'item');
        if(error != null, return(field+'.items：'+error));
        count = entry:'count';
        if(type(count) != 'number', return(field+'.items.count 必须是整数。'));
        if(count != floor(count) || count < 1 || count > 2304,
            return(field+'.items 中 '+entry:'item'+' 的数量必须为 1 到 2304 之间的整数。')
        );
        occupied_slots += ceil(count / stack_limit(entry:'item'));
        if(occupied_slots > 36, return(field+'.items 需要超过 36 个背包槽位，请减少数量。'))
    );
    null
);

_validate_game_config(config) -> (
    if(type(config) != 'map', return('配置缺失或不是合法 JSON 对象。请检查根目录 game-config.json，并通过启动脚本同步。'));
    bounds = {
        'schema_version' -> [1, 1],
        'game_duration_seconds' -> [1, 604800],
        'hunter_release_delay_seconds' -> [0, 604799],
        'tpa_cooldown_seconds' -> [0, 86400],
        'world_border_size_blocks' -> [128, 59999968],
        'drop_radius_blocks' -> [16, 29999984],
        'respawn_radius_blocks' -> [0, 128],
        'deployment_batch_size' -> [1, 100],
        'result_return_delay_seconds' -> [0, 60],
        'reset_shutdown_delay_seconds' -> [0, 60]
    };
    known = {'tpa_require_full_health', 'runner_equipment', 'hunter_equipment'};
    for(pairs(bounds),
        field = _:0;
        limits = _:1;
        put(known, field, true);
        value = config:field;
        if(type(value) != 'number', return(field+' 必须填写整数。'));
        if(value != floor(value) || value < limits:0 || value > limits:1,
            return(str('%s 必须为 %d 到 %d 之间的整数。', field, limits:0, limits:1))
        )
    );
    unknown = filter(keys(config), !has(known, _));
    if(length(unknown) > 0, return('无法识别的配置字段：'+join('、', unknown)));
    if(type(config:'tpa_require_full_health') != 'bool', return('tpa_require_full_health 必须填写 true 或 false。'));
    if(config:'hunter_release_delay_seconds' >= config:'game_duration_seconds',
        return('猎人放行时间必须小于比赛总时长。')
    );
    if(config:'drop_radius_blocks' > config:'world_border_size_blocks' / 2,
        return('投送半径不能超过世界边界边长的一半。')
    );
    if(config:'world_border_size_blocks' / 2 <= max(32, config:'respawn_radius_blocks' + 8) + 16,
        return('世界边界太小，无法同时留出重生范围和安全投送空间。')
    );
    for(['runner_equipment', 'hunter_equipment'],
        field = _;
        error = _validate_equipment(config:field, field);
        if(error != null, return(error))
    );
    null
);

_apply_game_config(config) -> (
    global_config = copy(config);
    global_duration_ms = config:'game_duration_seconds' * 1000;
    global_hunter_delay_ms = config:'hunter_release_delay_seconds' * 1000;
    global_tpa_cooldown_ms = config:'tpa_cooldown_seconds' * 1000;
    global_border_size = config:'world_border_size_blocks';
    global_drop_radius = config:'drop_radius_blocks';
    global_config_error = null;
    null
);

_load_game_config() -> (
    candidate = try(read_file('config', 'json'), 'exception', null);
    error = _validate_game_config(candidate);
    if(error != null,
        global_config_error = error;
        print('活动配置错误：'+error);
        return(false)
    );
    _apply_game_config(candidate);
    true
);

_apply_borders() -> (
    if(global_config == null || global_recovery_error != null, return(null));
    // Borders are independent in each dimension. Size is the square's side length.
    for(['minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'],
        run('execute in '+_+' run worldborder center 0 0');
        run('execute in '+_+' run worldborder set '+global_border_size)
    );
    null
);

_can_prepare() -> (
    if(global_recovery_error != null,
        print('请先修复比赛配置并重新加载脚本：'+global_recovery_error);
        return(false)
    );
    if(global_starting,
        print('正在选择安全投送点，请稍候；可以用 /sr cancel 取消。');
        return(false)
    );
    if(global_reset_pending,
        print('正在准备重置世界，请等待服务器重新启动。');
        return(false)
    );
    if(global_deadline != null,
        print('比赛进行中，队伍已锁定。');
        return(false)
    );
    if(global_pending_result != null || global_return_at != null,
        print('正在结算并返回大厅，请稍后操作。');
        return(false)
    );
    true
);

_sync_teams() -> (
    for(pairs(global_team_ids),
        side = _:0;
        id = _:1;
        team_add(id);
        team_property(id, 'friendlyFire', false);
        team_property(id, 'color', if(side == 'A', 'blue', 'red'));
        team_property(id, 'prefix', format(if(side == 'A', 'v', 'r')+' ['+side+'] '));
        for(team_list(id), team_leave(_))
    );
    members = if(global_deadline != null, global_players, values(global_prepared));
    online = player('all');
    for(members,
        member = _;
        target = first(online, query(_, 'uuid') == member:'uuid');
        name = if(target == null, member:'name', query(target, 'name'));
        team_add(global_team_ids:(member:'team'), name)
    );
    null
);

_store_prepared(prepared) -> (
    if(!try(write_file('teams', 'json', prepared), false),
        print('分队名单保存失败，请检查目录权限和剩余空间。');
        return(false)
    );
    global_prepared = prepared;
    _sync_teams();
    true
);

_set_team(name, side) -> (
    if(!_can_prepare(), return(null));
    target = player(name);
    member = if(target != null,
        {'uuid' -> query(target, 'uuid'), 'name' -> query(target, 'name')}
    ,
        first(values(global_prepared), lower(_:'name') == lower(name))
    );
    if(member == null, return(print('请使用在线玩家，或已在分队名单中的玩家名。')));
    prepared = copy(global_prepared);
    if(side == 'none',
        delete(prepared:(member:'uuid'))
    ,
        prepared:(member:'uuid') = {
            'uuid' -> member:'uuid', 'name' -> member:'name', 'team' -> side
        }
    );
    if(_store_prepared(prepared), _show_teams());
    null
);

_button_key(dimension, position) -> (
    str('%s/%d/%d/%d', replace(dimension, '^minecraft:', ''),
        floor(position:0), floor(position:1), floor(position:2))
);

__on_player_right_clicks_block(target, item_tuple, hand, clicked, face, hitvec) -> (
    if(!block_tags(clicked, 'minecraft:buttons') || bool(block_state(clicked, 'powered')), return(null));
    uuid = query(target, 'uuid');
    dimension = query(target, 'dimension');
    position = pos(clicked);
    key = _button_key(dimension, position);
    global_button_candidates:key = {
        'uuid' -> uuid,
        'tick' -> tick_time(),
        'dimension' -> dimension,
        'position' -> position,
        'button' -> str(clicked)
    };
    // Ordinary buttons use vanilla's empty-hand interaction path, which does not
    // emit Carpet's player_interacts_with_block event in this server version.
    schedule(0, '_confirm_button_press', key, tick_time(), uuid);
    null
);

_confirm_button_press(key, pressed_tick, uuid) -> (
    candidate = global_button_candidates:key;
    if(candidate == null || candidate:'tick' != pressed_tick || candidate:'uuid' != uuid, return(null));
    delete(global_button_candidates:key);
    if(tick_time() - pressed_tick > 20, return(null));
    clicked = in_dimension(candidate:'dimension', block(candidate:'position'));
    if(str(clicked) != candidate:'button' || !block_tags(clicked, 'minecraft:buttons')
        || !bool(block_state(clicked, 'powered')), return(null));
    global_button_presses:key = {'uuid' -> uuid, 'tick' -> pressed_tick};
    null
);

_button_press(dimension, position) -> (
    key = _button_key(dimension, position);
    candidate = global_button_candidates:key;
    // Command blocks can execute before the scheduled confirmation callback.
    if(candidate != null, _confirm_button_press(key, candidate:'tick', candidate:'uuid'));
    global_button_presses:key
);

_expire_button_presses() -> (
    // Short-lived interaction correlation, in server ticks so lag cannot choose another player.
    now = tick_time();
    for(pairs(global_button_candidates),
        if(now - (_:1):'tick' > 2, delete(global_button_candidates:(_:0)))
    );
    for(pairs(global_button_presses),
        if(now - (_:1):'tick' > 20, delete(global_button_presses:(_:0)))
    );
    null
);

_button_is_attached(clicked, support) -> (
    position = pos(clicked);
    face = block_state(clicked, 'face');
    offset = if(face == 'floor', [0, -1, 0], face == 'ceiling', [0, 1, 0],
        face == 'wall', {
            'north' -> [0, 0, 1], 'south' -> [0, 0, -1],
            'west' -> [1, 0, 0], 'east' -> [-1, 0, 0]
        }:(block_state(clicked, 'facing')), null);
    offset != null && position:0 + offset:0 == support:0
        && position:1 + offset:1 == support:1 && position:2 + offset:2 == support:2
);

_button_team_auto(side) -> (
    source = block(system_info('source_position'));
    if(source != 'command_block' && source != 'chain_command_block' && source != 'repeating_command_block',
        return(print('省略按钮坐标的形式需要由命令方块执行。请把按钮直接安装在该命令方块上。'))
    );
    dimension = system_info('source_dimension');
    candidates = [];
    attached_count = 0;
    powered_count = 0;
    for(neighbours(source),
        clicked = _;
        if(block_tags(clicked, 'minecraft:buttons') && _button_is_attached(clicked, pos(source)),
            attached_count += 1;
            if(bool(block_state(clicked, 'powered')),
                powered_count += 1;
                position = pos(clicked);
                press = _button_press(dimension, position);
                if(press != null && tick_time() - press:'tick' <= 20, candidates += position)
            )
        )
    );
    if(length(candidates) == 0,
        return(print(if(attached_count == 0,
            '本命令方块上没有直接安装的按钮；远处按钮需要指定坐标。',
            powered_count == 0, '按钮未处于按下状态，请检查是否由其他红石信号触发。',
            '按钮已按下，但没有有效玩家点击记录。请确认已重载最新 sr 脚本后重新点击。'
        )))
    );
    if(length(candidates) > 1,
        return(print('多个按钮同时存在有效点击，无法唯一识别。请只按一个按钮，或在命令中指定按钮坐标。'))
    );
    _button_team(candidates:0, side)
);

_button_team(position, side) -> (
    key = _button_key(system_info('source_dimension'), position);
    press = _button_press(system_info('source_dimension'), position);
    if(press == null,
        return(print('该坐标没有最近的有效按钮点击记录。请检查按钮坐标，并让玩家重新按下。'))
    );
    // A press can be consumed only once, including by chains of command blocks.
    delete(global_button_presses:key);
    if(tick_time() - press:'tick' > 20,
        return(print('按钮点击记录已过期，请缩短红石延时并重新按下。'))
    );
    clicked = block(position);
    if(!block_tags(clicked, 'minecraft:buttons') || !bool(block_state(clicked, 'powered')),
        return(print('指定位置不是已按下的按钮，无法分队。'))
    );
    target = first(player('all'), query(_, 'uuid') == press:'uuid');
    if(target == null, return(print('点击按钮的玩家已经离线，未修改队伍。')));
    if(!_can_prepare(),
        print(target, '当前阶段不能更换队伍，请等待比赛结束并返回大厅。');
        return(null)
    );
    prepared = copy(global_prepared);
    uuid = query(target, 'uuid');
    prepared:uuid = {'uuid' -> uuid, 'name' -> query(target, 'name'), 'team' -> side};
    if(!_store_prepared(prepared),
        return(print(target, '分队保存失败，请联系 OP。'))
    );
    print(target, '已加入 '+side+' 队。');
    print('已将实际点击按钮的玩家 '+query(target, 'name')+' 分配至 '+side+' 队。');
    null
);

_clear_teams() -> (
    if(!_can_prepare(), return(null));
    if(_store_prepared({}), print('已清空预分队名单。'));
    null
);

_random_teams_default() -> _random_teams(floor(length(player('all')) / 2));

_random_teams(a_count) -> (
    if(!_can_prepare(), return(null));
    online = player('all');
    count = length(online);
    if(count < 2, return(print('至少需要两名在线玩家才能随机分队。')));
    if(a_count < 1 || a_count >= count,
        return(print('A 队人数应至少为 1，并给 B 队留至少一人。'))
    );
    shuffled = sort_key(online, rand(1));
    prepared = {};
    for(shuffled,
        uuid = query(_, 'uuid');
        prepared:uuid = {
            'uuid' -> uuid,
            'name' -> query(_, 'name'),
            'team' -> if(_i < a_count, 'A', 'B')
        }
    );
    if(_store_prepared(prepared), _show_teams());
    null
);

_show_teams() -> (
    members = if(global_deadline != null, global_players, values(global_prepared));
    online = player('all');
    for(['A', 'B'],
        side = _;
        selected = filter(members, _:'team' == side);
        names = map(selected,
            member = _;
            target = first(online, query(_, 'uuid') == member:'uuid');
            if(target == null, member:'name'+'（离线）', query(target, 'name'))
        );
        print(str('%s 队（%d 人） %s', side, length(selected),
            if(length(names) == 0, '暂无玩家', join('、', names))))
    );
    null
);

_scan_dragons() -> (
    for(system_info('world_dimensions'),
        in_dimension(_,
            for(entity_list('*'),
                if(query(_, 'type') == 'ender_dragon', _watch_dragon(_, false))
            )
        )
    );
    null
);

_watch_dragon(dragon, newly_created) -> (
    entity_event(dragon, 'on_tick', '_check_dragon');
    entity_event(dragon, 'on_removed', '_dragon_removed');
    _check_dragon(dragon);
    null
);

_check_dragon(dragon) -> (
    if(global_deadline == null, return(null));
    uuid = query(dragon, 'uuid');
    dying = query(dragon, 'health') <= 0
        || query(dragon, 'nbt', 'DragonPhase') == 9
        || query(dragon, 'nbt', 'DragonDeathTime') > 0;
    if(dying,
        if(has(global_live_dragons, uuid), _dragon_victory())
    ,
        if(!has(global_live_dragons, uuid),
            global_live_dragons:uuid = true;
            _save_active()
        )
    );
    null
);

_dragon_removed(dragon) -> (
    // Direct kills remove a dragon immediately, before its death animation.
    if(global_deadline != null && has(global_live_dragons, query(dragon, 'uuid')),
        _dragon_victory()
    );
    null
);

_dragon_victory() -> (
    if(global_deadline == null, return(null));
    if(time() >= global_deadline,
        _finish('B', 'timeout', '时间到，B 队获胜。')
    ,
        _finish('A', 'dragon', '末影龙已被击杀，A 队获胜。')
    );
    null
);

_set_lobby() -> (
    if(global_starting || global_deadline != null || global_return_at != null || global_reset_pending,
        return(print('请在等待开局时设置大厅点。'))
    );
    host = system_info('source_entity');
    if(host == null || query(host, 'type') != 'player',
        return(print('请由 OP 在游戏内站到大厅位置，执行 /sr setlobby。'))
    );
    lobby = {'dimension' -> query(host, 'dimension'), 'location' -> query(host, 'location')};
    if(!try(write_file('lobby', 'json', lobby), false),
        return(print('大厅点保存失败，请检查目录权限和剩余空间。'))
    );
    global_lobby = lobby;
    print('已保存当前维度、位置和朝向作为大厅点。');
    null
);

_return_player(uuid) -> (
    target = first(player('all'), query(_, 'uuid') == uuid);
    if(target != null, _clear_inventory(target));
    if(target == null || query(target, 'health') <= 0, return(null));
    if(global_lobby == null,
        modify(target, 'gamemode', 'adventure');
        return(null)
    );
    location = global_lobby:'location';
    modify(target, 'dismount');
    run(str('execute in %s run tp %s %.6f %.6f %.6f %.6f %.6f',
        global_lobby:'dimension', query(target, 'command_name'),
        location:0, location:1, location:2, location:3, location:4));
    modify(target, 'gamemode', 'adventure');
    modify(target, 'motion', 0, 0, 0);
    modify(target, 'fire', 0);
    _restore_health(target);
    null
);

_return_all() -> (
    global_return_at = null;
    for(player('all'), _return_player(query(_, 'uuid')));
    null
);

_start(runner, hunter) -> (
    if(runner == null || hunter == null, return(print('请选择两名在线玩家。')));
    runner_uuid = query(runner, 'uuid');
    hunter_uuid = query(hunter, 'uuid');
    if(runner_uuid == hunter_uuid, return(print('请选择两名不同的在线玩家。')));
    _begin({
        runner_uuid -> {'uuid' -> runner_uuid, 'name' -> query(runner, 'name'), 'team' -> 'A'},
        hunter_uuid -> {'uuid' -> hunter_uuid, 'name' -> query(hunter, 'name'), 'team' -> 'B'}
    })
);

_start_prepared() -> _begin(global_prepared);

_begin(prepared) -> (
    if(!_can_prepare(), return(null));
    if(!_load_game_config(), return(null));
    if(global_lobby == null,
        return(print('请先由 OP 站在大厅点执行 /sr setlobby。'))
    );
    members = sort_key(values(prepared), _:'name');
    if(length(filter(members, _:'team' == 'A')) == 0 || length(filter(members, _:'team' == 'B')) == 0,
        return(print('A、B 两队都需要至少一名玩家。请先用 /sr team 或 /sr random 分队。'))
    );
    online = player('all');
    checked = {};
    for(members,
        member = _;
        target = first(online, query(_, 'uuid') == member:'uuid');
        if(target == null, return(print(member:'name'+' 尚未上线，请等人数到齐。')));
        if(query(target, 'health') <= 0, return(print(query(target, 'name')+' 需要先完成重生。')));
        uuid = query(target, 'uuid');
        checked:uuid = {'uuid' -> uuid, 'name' -> query(target, 'name'), 'team' -> member:'team'}
    );
    global_starting = true;
    global_start_token = str('%.0f_%06d', unix_time(), floor(rand(1000000)));
    print(str('正在主世界中心 %d 格范围内选择安全地面，找到后开始投送。', global_drop_radius));
    schedule(0, '_select_drop', checked, global_start_token, 0);
    null
);

_select_drop(prepared, token, attempt) -> (
    if(!global_starting || token != global_start_token, return(null));
    if(attempt >= 80, return(_abort_start('未找到安全投送点，本次未开局，请重新执行 /sr start。')));
    radius = min(global_drop_radius, global_border_size / 2 - max(32, global_config:'respawn_radius_blocks' + 8));
    x = floor(rand(2 * radius)) - radius;
    z = floor(rand(2 * radius)) - radius;
    drop = null;
    if(x*x + z*z <= radius*radius,
        drop = in_dimension('overworld', _safe_drop(x, z))
    );
    if(drop == null,
        schedule(1, '_select_drop', prepared, token, attempt + 1)
    ,
        _launch_round(prepared, drop, token)
    );
    null
);

_safe_drop(x, z) -> (
    // top() returns the first free Y above the heightmap block.
    y = top('motion', x, 0, z);
    if(y >= system_info('world_top') - 2, return(null));
    if(!solid(x, y-1, z) || liquid(x, y-1, z), return(null));
    if(!air(x, y, z) || !air(x, y+1, z), return(null));
    if((str(block(x, y-1, z)) ~ 'leaves|cactus|magma|powder_snow|campfire|dripstone') != null,
        return(null)
    );
    // Leave room for players to arrive without touching nearby hazards.
    for([-1, 0, 1],
        dx = _;
        for([-1, 0, 1],
            dz = _;
            if(!solid(x+dx, y-1, z+dz) || liquid(x+dx, y-1, z+dz)
                || blocks_movement(x+dx, y, z+dz) || blocks_movement(x+dx, y+1, z+dz)
                || liquid(x+dx, y, z+dz)
                || (str(block(x+dx, y-1, z+dz)) ~ 'leaves|cactus|magma|powder_snow|campfire|dripstone') != null
                || (str(block(x+dx, y, z+dz)) ~ 'fire|cactus|sweet_berry|wither_rose|powder_snow|dripstone') != null,
                return(null)
            )
        )
    );
    if(global_lobby:'dimension' == 'overworld' || global_lobby:'dimension' == 'minecraft:overworld',
        location = global_lobby:'location';
        if((x-location:0)^2 + (z-location:2)^2 < min(128, global_drop_radius/4)^2, return(null))
    );
    [x + 0.5, y, z + 0.5]
);

_abort_start(message) -> (
    global_starting = false;
    global_start_token = null;
    print(player('all'), message);
    null
);

_command_ok(result) -> result != null && result:2 == null;

_launch_round(prepared, drop, token) -> (
    if(!global_starting || token != global_start_token, return(null));
    online = player('all');
    for(values(prepared),
        member = _;
        target = first(online, query(_, 'uuid') == member:'uuid');
        if(target == null || query(target, 'health') <= 0,
            return(_abort_start(member:'name'+' 已离线或尚未重生，本次未开局。'))
        )
    );
    _apply_borders();
    if(!_command_ok(run(str('execute in minecraft:overworld run setworldspawn %d %d %d',
        floor(drop:0), drop:1, floor(drop:2)))),
        return(_abort_start('世界出生点设置失败，本次未开局，请检查控制台。'))
    );
    if(!_command_ok(run('execute in minecraft:overworld run gamerule minecraft:respawn_radius '+global_config:'respawn_radius_blocks')),
        return(_abort_start('重生范围设置失败，本次未开局，请检查控制台。'))
    );
    if(!_store_prepared(prepared), return(_abort_start('名单保存失败，本次未开局。')));
    global_roster = {};
    global_players = values(prepared);
    for(global_players, global_roster:(_:'uuid') = _:'team');
    global_drop_point = drop;
    global_hunters_released = false;
    global_deployed = {};
    global_tpa_ready = {};
    global_started_unix_ms = unix_time();
    global_round_id = str('%.0f_%06d', global_started_unix_ms, floor(rand(1000000)));
    global_live_dragons = {};
    if(!_save_active(),
        global_roster = {};
        return(_abort_start('无法写入比赛状态，请检查目录权限和剩余空间。本次未开局。'))
    );
    for(global_players,
        member = _;
        target = first(online, query(_, 'uuid') == member:'uuid');
        _return_player(member:'uuid');
        modify(target, 'spawn_point', null)
    );
    global_started_at = time();
    global_deadline = global_started_at + global_duration_ms;
    global_starting = false;
    global_start_token = null;
    _scan_dragons();
    global_displayed_seconds = null;
    global_explosions = [];
    run(str('say [速通] %d 秒对局开始。A 队 %d 人，B 队 %d 人。',
        global_duration_ms / 1000,
        length(filter(global_players, _:'team' == 'A')),
        length(filter(global_players, _:'team' == 'B'))));
    run(str('say [速通] A 队开始投送；B 队在大厅等待 %d 秒，随后投送并领取初始装备。', global_hunter_delay_ms/1000));
    _deployment_tick();
    null
);

_clear_inventory(target) -> (run('clear '+query(target, 'command_name')); null);

_restore_health(target) -> (
    modify(target, 'effect');
    modify(target, 'health', query(target, 'attribute', 'minecraft:max_health'));
    modify(target, 'hunger', 20);
    modify(target, 'saturation', 5);
    modify(target, 'exhaustion', 0);
    null
);

_give_initial_kit(target, side) -> (
    field = if(side == 'A', 'runner_equipment', 'hunter_equipment');
    kit = global_config:field;
    name = query(target, 'command_name');
    success = true;
    for(pairs(kit:'armor'),
        slot = _:0;
        item = _:1;
        if(!_command_ok(run('item replace entity '+name+' armor.'+slot+' with '+item)), success = false)
    );
    for(kit:'items',
        if(!_command_ok(run('give '+name+' '+_:'item'+' '+_:'count')), success = false)
    );
    success
);

_deploy_player(uuid) -> (
    if(global_deadline == null || time() >= global_deadline || global_drop_point == null, return(false));
    if(!has(global_roster, uuid) || has(global_deployed, uuid), return(false));
    if(global_roster:uuid == 'B' && time() < global_started_at + global_hunter_delay_ms, return(false));
    target = first(player('all'), query(_, 'uuid') == uuid);
    if(target == null || query(target, 'health') <= 0, return(false));
    drop = global_drop_point;
    modify(target, 'dismount');
    if(!_command_ok(run(str('execute in minecraft:overworld run tp %s %.1f %d %.1f',
        query(target, 'command_name'), drop:0, drop:1, drop:2))), return(false));
    _clear_inventory(target);
    _restore_health(target);
    modify(target, 'motion', 0, 0, 0);
    modify(target, 'fire', 0);
    if(!_give_initial_kit(target, global_roster:uuid), return(false));
    modify(target, 'gamemode', 'survival');
    global_deployed:uuid = true;
    print(target, '已投送到本局出生点。死亡后若没有可用的床或重生锚，将在此附近重生。');
    true
);

_deployment_tick() -> (
    if(global_deadline == null || time() >= global_deadline, return(null));
    changed = false;
    if(!global_hunters_released && time() >= global_started_at + global_hunter_delay_ms,
        global_hunters_released = true;
        changed = true;
        run(str('say [速通] %d 秒已到，B 队开始投送。', global_hunter_delay_ms/1000))
    );
    count = 0;
    for(global_players,
        if(_deploy_player(_:'uuid'),
            changed = true;
            count += 1;
            if(count >= global_config:'deployment_batch_size', break())
        )
    );
    if(changed, _save_active());
    null
);

_is_deployed(target) -> (
    target != null && query(target, 'type') == 'player' && global_deadline != null
        && has(global_roster, query(target, 'uuid')) && has(global_deployed, query(target, 'uuid'))
);

_save_active() -> (
    active = {
        'config' -> global_config,
        'round_id' -> global_round_id,
        'started_at_unix_ms' -> global_started_unix_ms,
        'duration_limit_ms' -> global_duration_ms,
        'roster' -> global_roster,
        'players' -> global_players,
        'live_dragons' -> global_live_dragons,
        'drop_point' -> global_drop_point,
        'hunter_delay_ms' -> global_hunter_delay_ms,
        'hunters_released' -> global_hunters_released,
        'deployed' -> global_deployed,
        'tpa_ready' -> global_tpa_ready
    };
    try(write_file('active', 'json', active), 'io_exception', false)
);

_status() -> (
    if(global_config_error != null, print('活动配置错误：'+global_config_error));
    if(global_recovery_error != null, return(print('无法恢复比赛配置：'+global_recovery_error)));
    if(global_starting, return(print('正在选择本局安全投送点。')));
    if(global_deadline != null,
        print(str('比赛进行中，剩余 %d 秒。', max(0, ceil((global_deadline - time()) / 1000)))),
        print(if(global_last_result == null, '等待开局。先用 /sr team 或 /sr random 分队，再执行 /sr start。', global_last_result))
    );
    if(global_deadline != null && global_drop_point != null,
        print(str('本局投送点：主世界 %d %d %d。',
            floor(global_drop_point:0), global_drop_point:1, floor(global_drop_point:2)))
    );
    null
);

_write_result() -> (
    global_next_write = time() + 1000;
    // Keep each round's result when several rounds share the same world.
    result_path = 'results/'+(global_pending_result:'round_id');
    if(!try(write_file(result_path, 'json', global_pending_result), false), return(null));
    if(!try(write_file('result', 'json', global_pending_result), false), return(null));
    try(delete_file('active', 'json'), null);
    global_pending_result = null;
    null
);

_finish(winner, reason, message) -> (
    if(global_deadline == null, return(null));
    elapsed = max(0, time() - global_started_at);
    global_deadline = null;
    global_live_dragons = {};
    global_explosions = [];
    global_deployed = {};
    global_tpa_ready = {};
    global_last_result = message;
    global_return_at = time() + global_config:'result_return_delay_seconds' * 1000;
    global_pending_result = {
        'schema_version' -> 1,
        'config' -> global_config,
        'round_id' -> global_round_id,
        'winner' -> winner,
        'reason' -> reason,
        'message' -> message,
        'started_at_unix_ms' -> global_started_unix_ms,
        'finished_at_unix_ms' -> unix_time(),
        'elapsed_ms' -> round(elapsed),
        'duration_limit_ms' -> global_duration_ms,
        'players' -> global_players,
        'reset_requested' -> false
    };
    viewers = player('all');
    for(viewers, _clear_inventory(_));
    display_title(viewers, 'actionbar', '');
    display_title(viewers, 'subtitle', '');
    display_title(viewers, 'title', format('y '+if(winner == null, '本局已取消', winner+' 队获胜！')), 10, 100, 20);
    global_roster = {};
    run('say [速通] '+message);
    _write_result();
    null
);

_cancel() -> (
    if(global_starting, return(_abort_start('已取消点位选择，本次未开局。')));
    if(global_deadline == null, return(print('当前没有进行中的比赛。')));
    _finish(null, 'cancelled', '本局比赛已取消。')
);

_reset_world() -> (
    if(!_can_prepare(), return(null));
    if(!_load_game_config(), return(null));
    control = read_file('control', 'json');
    if(control == null || control:'mode' != 'manual-reset-v1' || control:'run_id' == null,
        return(print('请先使用配套启动脚本启动服务器，再执行 /sr reset。'))
    );
    if((system_info('world_carpet_rules'):'commandScriptACE') != '4',
        return(print('当前脚本没有正常关服所需的权限，请使用配套启动脚本重启服务器。'))
    );
    host = system_info('source_entity');
    request = {
        'schema_version' -> 1,
        'action' -> 'manual_reset',
        'run_id' -> control:'run_id',
        'requested_at_unix_ms' -> unix_time(),
        'requested_by' -> if(host == null, 'server', query(host, 'name')),
        'round_id' -> global_round_id
    };
    if(!try(write_file('reset-request', 'json', request), 'io_exception', false),
        return(print('重置请求保存失败，服务器继续运行。请检查目录权限和剩余空间。'))
    );
    global_reset_pending = true;
    global_reset_at = time() + global_config:'reset_shutdown_delay_seconds' * 1000;
    display_title(player('all'), 'title', format('y 世界重置中'), 5, 60, 10);
    run(str('say [速通] OP 已手动触发世界重置，%d 秒后正常关服。备份旧世界并生成新档后将重新启动，请稍后重连。',
        global_config:'reset_shutdown_delay_seconds'));
    null
);

_stop_for_reset() -> (
    global_reset_at = null;
    result = run('stop');
    if(result == null || result:2 != null,
        global_reset_pending = false;
        delete_file('reset-request', 'json');
        print('无法正常关服，重置请求已撤销，请检查控制台。')
    );
    null
);

_waiting_player(uuid) -> (
    if(global_recovery_error != null, return(null));
    _sync_teams();
    if(global_deadline == null || !has(global_roster, uuid),
        _return_player(uuid)
    ,
        if(!has(global_deployed, uuid),
            _return_player(uuid);
            if(_deploy_player(uuid), _save_active())
        ,
            target = first(player('all'), query(_, 'uuid') == uuid);
            if(target != null, modify(target, 'gamemode', 'survival'))
        )
    );
    null
);

__on_player_connects(target) -> schedule(1, '_waiting_player', query(target, 'uuid'));

__on_player_respawns(target) -> schedule(1, '_waiting_player', query(target, 'uuid'));

__on_player_changes_dimension(target, from_pos, from_dimension, to_pos, to_dimension) -> (
    if(!_is_deployed(target), return(null));
    if(!has({'the_nether', 'minecraft:the_nether'}, from_dimension)
        || !has({'overworld', 'minecraft:overworld'}, to_dimension), return(null));
    // The event runs before relocation. Check the actual position after it completes.
    schedule(0, '_check_nether_return', query(target, 'uuid'), global_round_id);
    null
);

_check_nether_return(uuid, round_id) -> (
    if(global_deadline == null || time() >= global_deadline || round_id != global_round_id, return(null));
    target = first(player('all'), query(_, 'uuid') == uuid);
    if(!_is_deployed(target) || query(target, 'health') <= 0, return(null));
    if(!has({'overworld', 'minecraft:overworld'}, query(target, 'dimension')), return(null));
    position = query(target, 'pos');
    half_size = global_border_size / 2;
    if(abs(position:0) >= half_size || abs(position:2) >= half_size,
        print(target, '从下界返回后的落点位于世界边界外，按规则直接死亡。');
        modify(target, 'kill')
    );
    null
);

_tpa_teleport(payload) -> (
    // Run after the command returns so the teleport result is available immediately.
    schedule(0, '_tpa_execute', payload:'sender_uuid', payload:'name', global_round_id);
    null
);

_tpa_execute(sender_uuid, target_name, round_id) -> (
    online = player('all');
    sender = first(online, query(_, 'uuid') == sender_uuid);
    if(sender == null, return(null));
    if(target_name == '',
        if(global_config == null, return(print(sender, '活动配置尚未正确加载，请联系 OP。')));
        print(sender, str('使用 /tpa 队友名 直接传送。需双方已完成投送，%s成功后冷却 %d 秒。',
            if(global_config:'tpa_require_full_health', '发起者需满血，', ''), global_tpa_cooldown_ms/1000));
        return(null)
    );
    if(global_deadline == null || time() >= global_deadline || round_id != global_round_id,
        return(print(sender, '队内 TPA 仅在比赛进行期间可用。'))
    );
    if(!has(global_roster, sender_uuid),
        return(print(sender, '只有本局参赛玩家可以使用队内 TPA。'))
    );
    target = first(online, lower(query(_, 'name')) == lower(target_name));
    if(target == null, return(print(sender, '目标玩家不在线。')));
    if(query(target, 'uuid') == sender_uuid, return(print(sender, '请选择另一名队友。')));
    if(!_same_team(sender, target), return(print(sender, '只能传送到本局同队队员。')));
    if(!_is_deployed(sender) || !_is_deployed(target),
        return(print(sender, '双方完成投送后才能使用队内 TPA。'))
    );
    if(query(sender, 'health') <= 0 || query(target, 'health') <= 0,
        return(print(sender, '双方玩家都需要完成重生后才能传送。'))
    );
    if(global_config:'tpa_require_full_health'
        && query(sender, 'health') < query(sender, 'attribute', 'minecraft:max_health') - 0.001,
        return(print(sender, '只有满血时才能使用队内 TPA。'))
    );
    ready_at = global_tpa_ready:sender_uuid;
    if(ready_at != null && unix_time() < ready_at,
        return(print(sender, str('队内 TPA 冷却中，还需 %d 秒。', ceil((ready_at-unix_time()) / 1000))))
    );
    result = run('execute in '+query(target, 'dimension')+' run tp '
        +query(sender, 'command_name')+' '+query(target, 'command_name'));
    if(result == null || result:2 != null,
        return(print(sender, '传送失败，请重试。'))
    );
    global_tpa_ready:sender_uuid = unix_time() + global_tpa_cooldown_ms;
    _save_active();
    print(sender, '已传送到队友 '+query(target, 'name')+' 的当前位置。');
    print(target, '队友 '+query(sender, 'name')+' 已传送到你的位置。');
    null
);

_same_team_ids(attacker_uuid, target_uuid) -> (
    if(global_deadline == null || attacker_uuid == null || attacker_uuid == target_uuid,
        return(false)
    );
    side = global_roster:attacker_uuid;
    side != null && side == global_roster:target_uuid
);

_same_team(attacker, target) -> (
    if(attacker == null || target == null,
        return(false)
    );
    if(query(attacker, 'type') != 'player' || query(target, 'type') != 'player',
        return(false)
    );
    _same_team_ids(query(attacker, 'uuid'), query(target, 'uuid'))
);

__on_player_attacks_entity(attacker, target) -> (
    if(!_is_deployed(attacker) || _same_team(attacker, target), 'cancel')
);

__on_player_deals_damage(attacker, amount, target) -> (
    if(!_is_deployed(attacker) || _same_team(attacker, target), 'cancel')
);

__on_explosion(position, power, source, causer, mode, fire) -> (
    if(global_deadline == null, return(null));
    dimension = system_info('source_dimension');
    owner_uuid = if(causer != null && query(causer, 'type') == 'player', query(causer, 'uuid'), null);
    if(owner_uuid == null && length(global_explosions) > 0,
        owner_uuid = (global_explosions:(length(global_explosions)-1)):'owner_uuid'
    );
    global_explosions += {
        'owner_uuid' -> owner_uuid,
        'dimension' -> dimension,
        'position' -> position,
        'power' -> power
    };
    null
);

__on_explosion_outcome(position, power, source, causer, mode, fire, blocks, entities) -> (
    count = length(global_explosions);
    if(count == 0, return(null));
    current = global_explosions:(count-1);
    if(current:'position' == position && current:'power' == power
        && current:'dimension' == system_info('source_dimension'),
        global_explosions = slice(global_explosions, 0, count-1)
    );
    null
);

__on_player_takes_damage(target, amount, source, source_entity) -> (
    if(!_is_deployed(target),
        return('cancel')
    );
    if(_same_team(source_entity, target), return('cancel'));
    if(length(global_explosions) > 0 && has({'explosion', 'explosion.player'}, source),
        blast = global_explosions:(length(global_explosions)-1);
        if(blast:'dimension' == query(target, 'dimension')
            && _same_team_ids(blast:'owner_uuid', query(target, 'uuid')),
            return('cancel')
        )
    );
    null
);

__on_tick() -> (
    _expire_button_presses();
    // Explosion callbacks are synchronous. Clear any context left by an interrupted callback.
    global_explosions = [];
    if(global_pending_result != null && time() >= global_next_write, _write_result());
    if(global_return_at != null && time() >= global_return_at, _return_all());
    if(global_reset_at != null && time() >= global_reset_at, return(_stop_for_reset()));
    if(global_deadline == null, return(null));
    remaining_ms = global_deadline - time();
    if(remaining_ms <= 0, return(_finish('B', 'timeout', '时间到，B 队获胜。')));
    _deployment_tick();

    seconds = ceil(remaining_ms / 1000);
    if(seconds == global_displayed_seconds, return(null));
    global_displayed_seconds = seconds;
    for(player('all'),
        team = global_roster:query(_, 'uuid');
        if(team != null,
            if(!has(global_deployed, query(_, 'uuid')) && team == 'B',
                display_title(_, 'actionbar', str('猎人等待投送 · %d 秒',
                    max(0, ceil((global_started_at + global_hunter_delay_ms - time()) / 1000))))
            ,
                display_title(_, 'actionbar', str('%s 队 · 剩余 %d 秒', team, seconds))
            )
        )
    );
    null
);
