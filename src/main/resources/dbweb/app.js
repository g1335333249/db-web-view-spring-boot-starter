(() => {
  const $ = id => document.getElementById(id);
  const state = {csrf:null,source:null,table:null,tables:[],page:0,data:null};
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
  async function start() {
    try {
      state.csrf = (await api('/session')).csrf;
      showApp();
      await loadSources();
    } catch (_) { showLogin(); }
  }
  async function loadSources() {
    const list = (await api('/sources')).sources;
    const select = $('source'); select.replaceChildren();
    list.forEach(name => { const option = document.createElement('option'); option.value = name; option.textContent = name; select.append(option); });
    state.source = list[0] || null;
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
      button.onclick = () => { state.table = t; state.page = 0; renderTables(); loadRows().catch(e => toast(e.message)); };
      nav.append(button);
    });
    if (!nav.children.length) nav.textContent = '没有匹配的数据表';
  }
  function clearGrid() {
    $('data-grid').querySelector('thead').replaceChildren(); $('data-grid').querySelector('tbody').replaceChildren();
    $('empty').classList.remove('hidden'); $('refresh').disabled = true; $('add').disabled = true;
    $('table-title').textContent = '选择左侧数据表'; $('breadcrumb').textContent = '请选择数据表'; $('table-meta').textContent = '';
    $('page-info').textContent = ''; $('prev').disabled = true; $('next').disabled = true;
  }
  async function loadRows() {
    const t = state.table;
    const data = await api('/rows' + qs({source:state.source,namespace:t.namespace,table:t.name,page:state.page}));
    state.data = data;
    $('table-title').textContent = t.name;
    $('breadcrumb').textContent = `${state.source} / ${t.namespace}`;
    $('table-meta').textContent = `${data.columns.length} 个字段 · ${data.primaryKeys.length ? '主键: ' + data.primaryKeys.join(', ') : '无主键'}${data.readOnly ? ' · 只读' : ''}`;
    $('refresh').disabled = false; $('add').disabled = !data.canInsert;
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
  $('login-form').onsubmit = async event => {
    event.preventDefault(); $('login-error').textContent = '';
    try {
      state.csrf = (await api('/login', {method:'POST',body:JSON.stringify({username:$('username').value,password:$('password').value})})).csrf;
      $('password').value = ''; showApp(); await loadSources();
    } catch (error) { $('login-error').textContent = error.message; }
  };
  $('logout').onclick = async () => { try { await api('/logout',{method:'POST'}); } finally { showLogin(); } };
  $('source').onchange = () => { state.source = $('source').value; loadTables().catch(e => toast(e.message)); };
  $('refresh-tables').onclick = () => { if (state.source) loadTables().catch(e => toast(e.message)); };
  $('table-search').oninput = renderTables;
  $('refresh').onclick = () => loadRows().catch(e => toast(e.message));
  $('add').onclick = () => openEditor();
  $('prev').onclick = () => { state.page--; loadRows().catch(e => toast(e.message)); };
  $('next').onclick = () => { state.page++; loadRows().catch(e => toast(e.message)); };
  $('editor-form').onsubmit = saveEditor;
  $('close-editor').onclick = $('cancel-editor').onclick = () => $('editor').close();
  start();
})();
