(() => {
  const $ = id => document.getElementById(id);
  const state = {csrf:null,source:null,table:null,tables:[],page:0,data:null,view:'table',filters:{},sort:'',descending:false,settings:{}};
  const base = location.pathname.replace(/\/$/, '') + '/api';
  const qs = data => '?' + new URLSearchParams(data).toString();
  async function api(path, options={}) {
    const headers = {'Content-Type':'application/json'};
    if (state.csrf && options.method && options.method !== 'GET') headers['X-DB-Web-View-CSRF'] = state.csrf;
    const response = await fetch(base + path, {credentials:'same-origin',headers,...options});
    let body;
    try { body = await response.json(); } catch (_) { body = {}; }
    if (!response.ok) {
      if (response.status === 401 && path !== '/login') showLogin();
      throw new Error(body.error || `请求失败 (${response.status})`);
    }
    return body;
  }
  function toast(message) {
    $('toast').textContent = message;
    $('toast').classList.remove('hidden');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => $('toast').classList.add('hidden'), 3500);
  }
  function showLogin() { state.csrf = null; $('app').classList.add('hidden'); $('login').classList.remove('hidden'); }
  function showApp() { $('login').classList.add('hidden'); $('app').classList.remove('hidden'); }
  function switchView(view) {
    state.view = view;
    $('table-panel').classList.toggle('hidden', view !== 'table');
    $('sql-panel').classList.toggle('hidden', view !== 'sql');
    for (const name of ['table', 'sql']) {
      $('tab-' + name).classList.toggle('active', name === view);
      $('tab-' + name).setAttribute('aria-selected', String(name === view));
    }
    if (view === 'sql') { $('sql-input').focus(); loadHistory(); }
  }
  async function start() {
    try {
      state.csrf = (await api('/session')).csrf;
    } catch (_) { showLogin(); return; }
    showApp();
    try { await loadSettings(); await loadSources(); }
    catch (error) { $('table-list').textContent = '数据源加载失败，点击刷新重试'; toast(error.message); }
  }
  async function loadSettings() {
    state.settings = await api('/settings');
    $('tab-sql').classList.toggle('hidden', !state.settings.sqlEnabled);
    $('sql-panel').classList.toggle('hidden', !state.settings.sqlEnabled);
    $('run-sql').disabled = !state.settings.sqlEnabled;
    if (state.settings.readOnly) $('sql-input').placeholder = '只读模式：仅允许 SELECT';
  }
  async function loadSources() {
    const list = (await api('/sources')).sources;
    const select = $('source'); select.replaceChildren();
    list.forEach(name => { const option = document.createElement('option'); option.value = name; option.textContent = name; select.append(option); });
    state.source = list[0] || null;
    $('sql-source').textContent = state.source || '—';
    if (state.source) await loadTables();
    else $('table-list').textContent = '未发现数据源';
  }
  async function loadTables() {
    state.table = null; state.data = null;
    clearGrid();
    state.tables = (await api('/tables' + qs({source:state.source}))).tables;
    renderTables();
  }
  function renderTables() {
    const nav = $('table-list'); nav.replaceChildren();
    const filter = $('table-search').value.trim().toLowerCase();
    let group = null;
    state.tables.filter(t => (`${t.namespace}.${t.name}`).toLowerCase().includes(filter)).forEach(t => {
      if (group !== t.namespace) { group = t.namespace; const heading = document.createElement('div'); heading.className = 'group-title'; heading.textContent = group; nav.append(heading); }
      const button = document.createElement('button'); button.className = 'table-link' + (state.table && state.table.namespace === t.namespace && state.table.name === t.name ? ' active' : '');
      button.textContent = t.name + (t.type === 'VIEW' ? ' · 视图' : '');
      button.onclick = () => { state.table = t; state.page = 0; state.filters = {}; state.sort = ''; state.descending = false; $('filter-value').value = ''; $('descending').checked = false; $('structure-panel').classList.add('hidden'); switchView('table'); renderTables(); loadRows().catch(e => toast(e.message)); };
      nav.append(button);
    });
    if (!nav.children.length) nav.textContent = '没有匹配的数据表';
  }
  function clearGrid() {
    $('data-grid').querySelector('thead').replaceChildren(); $('data-grid').querySelector('tbody').replaceChildren();
    $('empty').classList.remove('hidden'); $('refresh').disabled = true; $('add').disabled = true; $('structure').disabled = true; $('export').disabled = true;
    $('table-title').textContent = '选择左侧数据表'; $('breadcrumb').textContent = '请选择数据表'; $('table-meta').textContent = '';
    $('page-info').textContent = ''; $('prev').disabled = true; $('next').disabled = true;
  }
  async function loadRows() {
    const t = state.table;
    const data = await api('/rows' + qs({source:state.source,namespace:t.namespace,table:t.name,page:state.page,filters:JSON.stringify(state.filters),sort:state.sort,descending:state.descending}));
    state.data = data;
    $('table-title').textContent = t.name;
    $('breadcrumb').textContent = `${state.source} / ${t.namespace}`;
    $('table-meta').textContent = `${data.columns.length} 个字段 · ${data.primaryKeys.length ? '主键: ' + data.primaryKeys.join(', ') : '无主键'}${data.readOnly ? ' · 只读' : ''}`;
    $('refresh').disabled = false; $('add').disabled = !data.canInsert; $('structure').disabled = false; $('export').disabled = false;
    for (const id of ['filter-column','sort-column']) { const select = $(id), previous = select.value; select.replaceChildren(); if (id === 'sort-column') select.append(new Option('默认顺序','')); data.columns.filter(c => !c.binary).forEach(c => select.append(new Option(c.name,c.name))); select.value = id === 'sort-column' ? state.sort : (previous || data.columns[0]?.name || ''); }
    const head = $('data-grid').querySelector('thead'); head.replaceChildren();
    const hr = document.createElement('tr');
    data.columns.forEach(c => { const th = document.createElement('th'); th.textContent = c.name; th.title = c.type; hr.append(th); });
    if (!data.readOnly) { const th = document.createElement('th'); th.textContent = '操作'; hr.append(th); }
    head.append(hr);
    const body = $('data-grid').querySelector('tbody'); body.replaceChildren();
    data.rows.forEach(row => {
      const tr = document.createElement('tr');
      data.columns.forEach(c => { const td = document.createElement('td'); const value = row[c.name]; td.textContent = value === null ? 'NULL' : String(value); td.title = td.textContent; if (value === null) td.className = 'null'; tr.append(td); });
      if (!data.readOnly) {
        const td = document.createElement('td');
        const edit = document.createElement('button'); edit.textContent = '编辑'; edit.onclick = () => openEditor(row);
        const del = document.createElement('button'); del.textContent = '删除'; del.className = 'danger'; del.onclick = () => removeRow(row);
        td.append(edit, document.createTextNode(' '), del); tr.append(td);
      }
      body.append(tr);
    });
    $('empty').classList.toggle('hidden', data.rows.length > 0);
    if (!data.rows.length) $('empty').textContent = '当前页没有记录';
    $('page-info').textContent = `第 ${state.page + 1} 页 · 本页 ${data.rows.length} 条`;
    $('prev').disabled = state.page === 0; $('next').disabled = !data.hasNext;
  }
  function keysOf(row) { const keys = {}; state.data.primaryKeys.forEach(k => keys[k] = row[k]); return keys; }
  function openEditor(row=null) {
    $('editor-title').textContent = row ? '编辑记录' : '新增记录';
    const fields = $('editor-fields'); fields.replaceChildren();
    state.data.columns.forEach(c => {
      const disabled = c.generated || c.binary || (row ? state.data.primaryKeys.includes(c.name) : c.autoIncrement);
      if (!row && disabled) return;
      const wrapper = document.createElement('label'); wrapper.className = 'field';
      const title = document.createElement('span'); title.className = 'field-name'; title.textContent = c.name + ' ';
      const type = document.createElement('span'); type.className = 'field-type'; type.textContent = c.type; title.append(type);
      const input = document.createElement('input'); input.type = 'text'; input.name = c.name; input.disabled = disabled; input.value = row && row[c.name] !== null ? String(row[c.name]) : '';
      wrapper.append(title, input);
      if (c.nullable && !disabled) {
        const nullLabel = document.createElement('span'); nullLabel.className = 'null-toggle';
        const nullInput = document.createElement('input'); nullInput.type = 'checkbox'; nullInput.checked = !!row && row[c.name] === null;
        nullInput.onchange = () => { input.disabled = nullInput.checked; }; nullLabel.append(nullInput, document.createTextNode('设为 NULL'));
        wrapper.append(nullLabel); wrapper.nullInput = nullInput;
      }
      wrapper.fieldInput = input; wrapper.column = c; fields.append(wrapper);
    });
    $('editor').editRow = row; $('editor').showModal();
  }
  async function saveEditor(event) {
    event.preventDefault();
    const row = $('editor').editRow;
    const values = {};
    [...$('editor-fields').children].forEach(w => {
      const c = w.column; if (w.fieldInput.disabled && !w.nullInput?.checked) return;
      const value = w.nullInput?.checked ? null : w.fieldInput.value;
      if (!row && value === '') return;
      if (row && String(row[c.name] ?? '') === String(value ?? '')) return;
      values[c.name] = value;
    });
    if (!Object.keys(values).length) { toast('没有需要保存的字段'); return; }
    const request = {source:state.source,namespace:state.table.namespace,table:state.table.name,values};
    if (row) request.keys = keysOf(row);
    try {
      const result = await api('/rows', {method:row ? 'PUT' : 'POST',body:JSON.stringify(request)});
      $('editor').close(); toast(`已保存，影响 ${result.affected} 行`); await loadRows();
    } catch (error) { toast(error.message); }
  }
  async function removeRow(row) {
    if (!confirm('确定删除这条记录？此操作不可撤销。')) return;
    try {
      const result = await api('/rows', {method:'DELETE',body:JSON.stringify({source:state.source,namespace:state.table.namespace,table:state.table.name,keys:keysOf(row)})});
      toast(`已删除，影响 ${result.affected} 行`); await loadRows();
    } catch (error) { toast(error.message); }
  }
  async function loadHistory() {
    try {
      const list=(await api('/sql/history')).history, panel=$('sql-history'); panel.replaceChildren();
      list.forEach(item=>{ const row=document.createElement('div'); row.textContent=`${item.time} · ${item.source} · ${item.kind} · ${item.elapsedMs}ms${item.error ? ' · '+item.error : ''}${item.sql ? ' · '+item.sql : ''}`; panel.append(row); });
      if (!list.length) panel.textContent='暂无记录';
    } catch(e) { toast(e.message); }
  }
  async function runSql() {
    if ($('run-sql').disabled) return;
    if (!state.source) { toast('没有可用的数据源'); return; }
    const sql = $('sql-input').value.trim();
    if (!sql) { toast('请输入 SQL'); return; }
    if (!/^\s*(SELECT|SHOW|EXPLAIN|DESCRIBE|DESC)\b/i.test(sql)
        && !confirm('这条 SQL 可能修改数据或数据库结构。确定执行吗？')) return;
    const source = state.source;
    const button = $('run-sql'); button.disabled = true; button.textContent = '执行中…';
    $('sql-status').textContent = '正在执行…';
    try {
      const result = await api('/sql', {method:'POST',body:JSON.stringify({source,sql})});
      loadHistory();
      if (source !== state.source) return;
      const head = $('sql-grid').querySelector('thead'); const body = $('sql-grid').querySelector('tbody');
      head.replaceChildren(); body.replaceChildren();
      if (result.kind === 'resultSet') {
        const row = document.createElement('tr');
        result.columns.forEach(name => { const th = document.createElement('th'); th.textContent = name; row.append(th); });
        head.append(row);
        result.rows.forEach(values => {
          const tr = document.createElement('tr');
          values.forEach(value => { const td = document.createElement('td'); td.textContent = value === null ? 'NULL' : String(value); if (value === null) td.className = 'null'; td.title = td.textContent; tr.append(td); });
          body.append(tr);
        });
        $('sql-status').textContent = `查询完成 · ${result.rowCount} 行${result.truncated ? '（已达到显示上限）' : ''} · ${result.elapsedMs} 毫秒`;
        $('sql-empty').textContent = '查询完成，没有返回记录';
        $('sql-empty').classList.toggle('hidden', result.rows.length > 0);
      } else {
        $('sql-empty').textContent = '语句执行成功'; $('sql-empty').classList.remove('hidden');
        $('sql-status').textContent = `执行完成 · 影响 ${result.affected} 行 · ${result.elapsedMs} 毫秒`;
        if (state.source && state.table) loadRows().catch(e => toast(e.message));
      }
    } catch (error) { if (source === state.source) { $('sql-status').textContent = error.message; toast(error.message); } }
    finally { button.disabled = false; button.textContent = '执行 SQL'; }
  }
  $('login-form').onsubmit = async event => {
    event.preventDefault(); $('login-error').textContent = '';
    try {
      state.csrf = (await api('/login', {method:'POST',body:JSON.stringify({username:$('username').value,password:$('password').value})})).csrf;
      $('password').value = ''; showApp();
    } catch (error) { $('login-error').textContent = error.message; }
    if (state.csrf) {
      try { await loadSettings(); await loadSources(); }
      catch (error) { $('table-list').textContent = '数据源加载失败，点击刷新重试'; toast(error.message); }
    }
  };
  $('logout').onclick = async () => { try { await api('/logout',{method:'POST'}); } finally { showLogin(); } };
  $('source').onchange = () => {
    state.source = $('source').value; $('sql-source').textContent = state.source || '—';
    $('sql-grid').querySelector('thead').replaceChildren(); $('sql-grid').querySelector('tbody').replaceChildren();
    $('sql-empty').textContent = '执行结果将显示在这里'; $('sql-empty').classList.remove('hidden');
    $('sql-status').textContent = '已切换数据源，请重新执行 SQL';
    loadTables().catch(e => toast(e.message));
  };
  $('refresh-tables').onclick = () => {
    (state.source ? loadTables() : loadSources()).catch(e => toast(e.message));
  };
  $('table-search').oninput = renderTables;
  $('refresh').onclick = () => loadRows().catch(e => toast(e.message));
  $('apply-filter').onclick = () => { const column = $('filter-column').value; state.filters = column && $('filter-value').value !== '' ? {[column]:$('filter-value').value} : {}; state.page = 0; if (state.table) loadRows().catch(e => toast(e.message)); };
  $('clear-filter').onclick = () => { state.filters = {}; $('filter-value').value = ''; state.page = 0; if (state.table) loadRows().catch(e => toast(e.message)); };
  $('sort-column').onchange = () => { state.sort = $('sort-column').value; state.page = 0; if (state.table) loadRows().catch(e => toast(e.message)); };
  $('descending').onchange = () => { state.descending = $('descending').checked; state.page = 0; if (state.table) loadRows().catch(e => toast(e.message)); };
  $('structure').onclick = async () => {
    try {
      const t = state.table, data = await api('/structure' + qs({source:state.source,namespace:t.namespace,table:t.name}));
      const panel = $('structure-panel'); panel.replaceChildren();
      const add = (title, rows, fields) => { const h = document.createElement('h3'); h.textContent = title; panel.append(h); const table = document.createElement('table'); const head = document.createElement('tr'); fields.forEach(f => {const th=document.createElement('th'); th.textContent=f; head.append(th);}); table.append(head); rows.forEach(r=>{const tr=document.createElement('tr'); fields.forEach(f=>{const td=document.createElement('td'); td.textContent=String(r[f] ?? ''); tr.append(td);}); table.append(tr);}); panel.append(table); };
      add('字段', data.columns, ['name','type','nullable','autoIncrement','generated']);
      add('索引', data.indexes, ['name','column','unique']);
      add('外键', data.foreignKeys, ['name','column','reference']);
      panel.classList.toggle('hidden');
    } catch(e) { toast(e.message); }
  };
  $('export').onclick = async () => {
    try {
      const t=state.table, response=await fetch(base+'/export'+qs({source:state.source,namespace:t.namespace,table:t.name,filters:JSON.stringify(state.filters),sort:state.sort,descending:state.descending}),{credentials:'same-origin'});
      if (!response.ok) { const body=await response.json(); throw new Error(body.error || '导出失败'); }
      const url=URL.createObjectURL(await response.blob()), a=document.createElement('a'); a.href=url; a.download=t.name+'.csv'; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(e) { toast(e.message); }
  };
  $('refresh-history').onclick = loadHistory;
  $('add').onclick = () => openEditor();
  $('prev').onclick = () => { state.page--; loadRows().catch(e => toast(e.message)); };
  $('next').onclick = () => { state.page++; loadRows().catch(e => toast(e.message)); };
  $('editor-form').onsubmit = saveEditor;
  $('close-editor').onclick = $('cancel-editor').onclick = () => $('editor').close();
  $('tab-table').onclick = () => switchView('table');
  $('tab-sql').onclick = () => switchView('sql');
  $('run-sql').onclick = runSql;
  $('sql-input').onkeydown = event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); runSql(); } };
  start();
})();
