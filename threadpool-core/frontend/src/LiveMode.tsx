import { useEffect, useState } from 'react';
import { ArrowLeft, Play, Square, X } from 'lucide-react';

type Task = { id: string; burstMs: number; priority: number; dependsOn: string[]; failAtMs?: number | null; blocking?: boolean };
type Preset = { name: string; description: string; policy: string; workerCount: number; quantum: number; tasks: Task[]; recoveryMode?: string; advisor?: string };
type PoolEvent = { seq: number; tsMs: number; type: string; taskId?: string; workerId?: string; message?: string; cause?: string; outcome?: string; advisorSource?: string; recoveryOptionId?: string; advisorFallback?: boolean };
type RunState = { runId: string; state: string; taskCount: number; readyCount: number; elapsedMs: number; recoveryMode?: string; advisor?: string };
type RecoveryPath = {
  failedId: string;
  decisions: PoolEvent[];
  recovered?: PoolEvent;
  cascadedVictims: string[];
  dependents: string[];
  terminal: 'released' | 'cascaded' | 'leaf' | 'waiting';
};
type DagNode = { id: string; x: number; y: number; status: string };

const shortLabel = (value: string, max = 22) => value.length > max ? `${value.slice(0, max - 1)}…` : value;

const eventNames = ['RUN_STARTED', 'TASK_SUBMITTED', 'TASK_READY', 'SLICE_STARTED', 'SLICE_ENDED', 'TASK_COMPLETED', 'TASK_FAILED', 'TASK_RECOVERING', 'AI_DECISION', 'TASK_RECOVERED', 'TASK_CASCADE_FAILED', 'MLFQ_DEMOTED', 'MLFQ_BOOSTED', 'POOL_RESIZED', 'RUN_FINISHED', 'RUN_ABORTED'];
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? undefined as T : response.json();
}

function replayStatus(events: PoolEvent[], id: string): string {
  let status = 'PENDING';
  for (const event of events) {
    if (event.taskId !== id) continue;
    if (event.type === 'TASK_READY' || (event.type === 'SLICE_ENDED' && event.outcome === 'YIELDED')) status = 'READY';
    if (event.type === 'SLICE_STARTED') status = 'RUNNING';
    if (event.type === 'TASK_RECOVERING') status = 'RECOVERING';
    if (event.type === 'TASK_COMPLETED') status = 'DONE';
    if (event.type === 'TASK_RECOVERED') status = 'RECOVERED';
    if (event.type === 'TASK_FAILED') status = 'FAILED';
    if (event.type === 'TASK_CASCADE_FAILED') status = 'CASCADE_FAILED';
  }
  return status;
}

function buildRecoveryPaths(tasks: Task[], events: PoolEvent[]): RecoveryPath[] {
  const cascadeVictimIds = new Set(
    events.filter(event => event.type === 'TASK_CASCADE_FAILED').map(event => event.taskId).filter((id): id is string => !!id),
  );
  const failedRoots = [...new Set(
    events
      .filter(event => event.type === 'TASK_FAILED' && event.taskId && !cascadeVictimIds.has(event.taskId))
      .map(event => event.taskId as string),
  )];
  return failedRoots.map(failedId => {
    const decisions = events.filter(event => event.type === 'AI_DECISION' && event.taskId === failedId);
    const recovered = [...events].reverse().find(event => event.type === 'TASK_RECOVERED' && event.taskId === failedId);
    const cascadedVictims = events
      .filter(event => event.type === 'TASK_CASCADE_FAILED' && event.cause === failedId)
      .map(event => event.taskId)
      .filter((id): id is string => !!id);
    const dependents = tasks.filter(task => task.dependsOn.includes(failedId)).map(task => task.id);
    let terminal: RecoveryPath['terminal'] = 'waiting';
    if (recovered) terminal = 'released';
    else if (cascadedVictims.length > 0) terminal = 'cascaded';
    else if (dependents.length === 0 && decisions.length === 0) terminal = 'leaf';
    return { failedId, decisions, recovered, cascadedVictims, dependents, terminal };
  });
}

function buildAffectedDag(tasks: Task[], events: PoolEvent[], paths: RecoveryPath[]): { nodes: DagNode[]; edges: { from: string; to: string }[]; width: number; height: number } {
  const affected = new Set<string>();
  for (const path of paths) {
    affected.add(path.failedId);
    path.dependents.forEach(id => affected.add(id));
    path.cascadedVictims.forEach(id => affected.add(id));
  }
  const subset = tasks.filter(task => affected.has(task.id));
  if (subset.length === 0) return { nodes: [], edges: [], width: 640, height: 120 };

  const depth = new Map<string, number>();
  const visiting = new Set<string>();
  const computeDepth = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!;
    if (visiting.has(id)) return 0;
    visiting.add(id);
    const task = subset.find(item => item.id === id);
    const parents = (task?.dependsOn ?? []).filter(dep => affected.has(dep));
    const value = parents.length === 0 ? 0 : 1 + Math.max(...parents.map(computeDepth));
    visiting.delete(id);
    depth.set(id, value);
    return value;
  };
  subset.forEach(task => computeDepth(task.id));

  const layers = new Map<number, string[]>();
  for (const task of subset) {
    const layer = depth.get(task.id) ?? 0;
    const bucket = layers.get(layer) ?? [];
    bucket.push(task.id);
    layers.set(layer, bucket);
  }
  const maxLayer = Math.max(...layers.keys(), 0);
  const maxPerLayer = Math.max(...[...layers.values()].map(layer => layer.length), 1);
  const colGap = 150;
  const rowGap = 56;
  const width = Math.max(640, (maxLayer + 1) * colGap + 80);
  const height = Math.max(140, maxPerLayer * rowGap + 40);
  const nodes: DagNode[] = [];
  for (const [layer, ids] of layers) {
    ids.forEach((id, index) => {
      const columnCount = ids.length;
      const yOffset = (height - columnCount * rowGap) / 2 + index * rowGap;
      nodes.push({ id, x: 40 + layer * colGap, y: Math.max(16, yOffset), status: replayStatus(events, id) });
    });
  }
  const edges = subset.flatMap(task =>
    task.dependsOn.filter(dep => affected.has(dep)).map(dep => ({ from: dep, to: task.id })),
  );
  return { nodes, edges, width, height };
}

export default function LiveMode({ onBack }: { onBack: () => void }) {
  const [presets, setPresets] = useState<Preset[]>([]); const [presetName, setPresetName] = useState('');
  const [recoveryMode, setRecoveryMode] = useState<'RECOVER' | 'STRICT'>('RECOVER');
  const [advisor, setAdvisor] = useState<'RULE_BASED' | 'LLM'>('RULE_BASED');
  const [run, setRun] = useState<RunState | null>(null); const [events, setEvents] = useState<PoolEvent[]>([]);
  const [metrics, setMetrics] = useState<any>(null); const [error, setError] = useState(''); const [starting, setStarting] = useState(false);
  const preset = presets.find(item => item.name === presetName); const terminal = !!run && ['FINISHED', 'FAILED', 'CANCELLED'].includes(run.state);

  useEffect(() => {
    request<Preset[]>('/presets').then(items => {
      const first = items.find(item => item.name.startsWith('Recovery · ')) ?? items[0];
      setPresets(items); setPresetName(first?.name ?? '');
      setRecoveryMode(first?.recoveryMode === 'STRICT' ? 'STRICT' : 'RECOVER');
      setAdvisor(first?.advisor === 'LLM' ? 'LLM' : 'RULE_BASED');
    }).catch(e => setError(e.message));
  }, []);
  useEffect(() => {
    if (!run?.runId) return;
    const stream = new EventSource(`/api/runs/${run.runId}/events`);
    for (const type of eventNames) stream.addEventListener(type, message => {
      const event = JSON.parse((message as MessageEvent).data) as PoolEvent;
      setEvents(current => current.some(item => item.seq === event.seq) ? current : [...current, event].sort((a, b) => a.seq - b.seq));
      if (type === 'RUN_FINISHED') stream.close();
    });
    const poll = window.setInterval(() => {
      request<RunState>(`/runs/${run.runId}`).then(state => {
        setRun(state);
        if (['FINISHED', 'FAILED', 'CANCELLED'].includes(state.state)) window.clearInterval(poll);
      }).catch(e => setError(e.message));
      request<any>(`/runs/${run.runId}/metrics`).then(setMetrics).catch(() => undefined);
    }, 500);
    return () => { stream.close(); window.clearInterval(poll); };
  }, [run?.runId]);

  const start = async () => {
    if (!preset) return;
    setError(''); setEvents([]); setMetrics(null); setStarting(true);
    let createdId: string | undefined;
    try {
      const definition = {
        policy: preset.policy,
        workerCount: preset.workerCount,
        quantum: preset.quantum,
        tasks: preset.tasks,
        recoveryMode,
        advisor,
      };
      const created = await request<{ runId: string }>('/runs', { method: 'POST', body: JSON.stringify(definition) });
      createdId = created.runId;
      setRun(await request<RunState>(`/runs/${createdId}/start`, { method: 'POST' }));
    } catch (failure) {
      if (createdId) await request(`/runs/${createdId}`, { method: 'DELETE' }).catch(() => undefined);
      setError(failure instanceof Error ? failure.message : 'Could not start the run');
    } finally { setStarting(false); }
  };
  const cancel = async () => { if (!run) return; try { setRun(await request<RunState>(`/runs/${run.runId}/cancel`, { method: 'POST' })); } catch (e) { setError(e instanceof Error ? e.message : 'Could not cancel run'); } };
  const selectPreset = (name: string) => {
    const selected = presets.find(item => item.name === name);
    setPresetName(name);
    if (selected) {
      setRecoveryMode(selected.recoveryMode === 'STRICT' ? 'STRICT' : 'RECOVER');
      setAdvisor(selected.advisor === 'LLM' ? 'LLM' : 'RULE_BASED');
    }
  };
  const failTask = async (id: string) => { if (!run) return; try { await request(`/runs/${run.runId}/tasks/${encodeURIComponent(id)}/fail`, { method: 'POST' }); } catch (e) { setError(e instanceof Error ? e.message : 'Could not fail task'); } };
  const taskStatus = (id: string) => replayStatus(events, id);
  const recoveryDecisions = events.filter(event => event.type === 'AI_DECISION');
  const recoveredTasks = events.filter(event => event.type === 'TASK_RECOVERED');
  const latestRecovery = [...events].reverse().find(event => event.type === 'TASK_RECOVERED' || event.type === 'AI_DECISION');
  const advisorLabel = advisor === 'LLM' ? 'Live AI' : 'Rule-based';

  return <main className="app">
    <header className="masthead"><button className="button-secondary" onClick={onBack}><ArrowLeft size={16} /> Simulator</button><div className="brand-copy"><p className="eyebrow">JAVA THREAD POOL · LIVE EVENTS</p><h1>Live Java run</h1><p className="subtitle">Real worker events streamed from the Spring Boot backend.</p></div><span className="live-chip"><span /> {run?.state ?? 'READY'}</span></header>
    <section className="panel paper-panel" style={{ padding: 24, margin: '24px 0' }}>
      <label className="field-label" htmlFor="live-preset">Workload preset</label>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}><select id="live-preset" value={presetName} disabled={!!run && !terminal} onChange={e => selectPreset(e.target.value)}>{presets.map(item => <option key={item.name}>{item.name}</option>)}</select><button className="button-primary" disabled={!preset || starting || (!!run && !terminal)} onClick={start}><Play size={16} /> Start live run</button>{run && !terminal && <button className="button-secondary" onClick={cancel}><Square size={15} /> Cancel</button>}<span>{preset?.policy} · {preset?.workerCount} workers · {preset?.quantum} ms quantum</span></div>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
        <label className="field-label" htmlFor="live-recovery-mode" style={{ flex: '1 1 220px' }}>Recovery mode<select id="live-recovery-mode" value={recoveryMode} disabled={!!run && !terminal} onChange={e => setRecoveryMode(e.target.value as 'RECOVER' | 'STRICT')}><option value="RECOVER">RECOVER · rescue failed tasks</option><option value="STRICT">STRICT · cascade failures</option></select></label>
        <label className="field-label" htmlFor="live-advisor" style={{ flex: '1 1 220px' }}>Recovery advisor<select id="live-advisor" value={advisor} disabled={!!run && !terminal} onChange={e => setAdvisor(e.target.value as 'RULE_BASED' | 'LLM')}><option value="RULE_BASED">Rule-based · offline</option><option value="LLM">Live AI · Gemini-compatible</option></select></label>
      </div>
      <p className="simulation-caption" style={{ color: 'var(--indigo)' }}>The advisor is used only in RECOVER mode. Live AI falls back to Rule-based when unavailable.</p>
      {preset && <p>{preset.description}</p>}{error && <p role="alert" style={{ color: '#b42318' }}>{error}</p>}
    </section>
    {run && <><section className="recovery-dashboard" aria-label="Recovery status"><div className="recovery-dashboard-copy"><p className="eyebrow">RECOVERY CONTROL</p><h2>{run.recoveryMode === 'RECOVER' ? 'Failure does not have to cascade.' : 'Strict cascade mode'}</h2><p>{run.recoveryMode === 'RECOVER' ? 'The advisor evaluates the failed task and releases its dependents only after a bounded recovery succeeds.' : 'Failed tasks remain terminal and their descendants are marked failed.'}</p><div className="recovery-badges"><span>{run.recoveryMode}</span><span>{advisorLabel}</span></div></div><div className="recovery-metrics"><div><span>Decisions</span><strong>{metrics?.recoveryDecisionCount ?? recoveryDecisions.length}</strong><small>advisor choices</small></div><div><span>Recovered</span><strong>{metrics?.recoveredCount ?? recoveredTasks.length}</strong><small>dependency releases</small></div><div><span>Latest action</span><strong>{latestRecovery?.recoveryOptionId ?? '—'}</strong><small>{latestRecovery?.taskId ?? 'waiting for failure'}</small></div></div></section><RecoveryGraph tasks={preset?.tasks ?? []} events={events} /><section className="panel paper-panel" style={{ padding: 24, margin: '24px 0' }}><div className="panel-heading"><div><p className="eyebrow">TASK TOPOLOGY</p><h2>{run.state} · {run.readyCount} ready</h2></div></div><div className="task-list-view live-task-list">{preset?.tasks.map(task => <div className={`list-task state-${taskStatus(task.id).toLowerCase().replace('_', '-')}`} key={task.id}><strong>{task.id}</strong><span>{taskStatus(task.id)}</span><small>{task.burstMs} ms · depends on {task.dependsOn.join(', ') || 'nothing'}</small>{!terminal && <button title={`Fail ${task.id} at its next slice boundary`} onClick={() => failTask(task.id)}><X size={14} /> Fail</button>}</div>)}</div></section>
      <section className="night-panel panel" style={{ padding: 24 }}><div className="panel-heading"><div><p className="eyebrow">SEQUENCED TRACE · SSE</p><h2>{events.length} events</h2></div></div><div className="log-entries live-event-log" style={{ maxHeight: 360, overflow: 'auto' }}>{events.map(event => <div className="log-line" key={event.seq}><time>#{event.seq} · {event.tsMs} ms</time><strong>{event.taskId ?? 'pool'}</strong><span>{event.type === 'AI_DECISION' && <b>{event.advisorSource ?? 'Rule-based'}</b>}{event.type}{event.recoveryOptionId ? ` · ${event.recoveryOptionId}` : ''}{event.advisorFallback ? ' · fallback' : ''}{event.outcome ? ` · ${event.outcome}` : ''}{event.cause ? ` · caused by ${event.cause}` : ''}{event.message ? ` · ${event.message}` : ''}</span></div>)}</div>{metrics && <div className="metrics-row">{[['Elapsed', `${metrics.elapsedMs} ms`], ['Throughput', Number(metrics.throughputTasksPerSecond).toFixed(2)], ['Recovered', `${metrics.recoveredCount ?? 0}`], ['Decisions', `${metrics.recoveryDecisionCount ?? 0}`]].map(([label, value]) => <div className="metric-card" key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>}<p className="simulation-caption">Live Java run timings include HTTP and event-stream overhead. Use /api/compare for headless measurements.</p></section></>}
  </main>;
}

function RecoveryGraph({ tasks, events }: { tasks: Task[]; events: PoolEvent[] }) {
  const paths = buildRecoveryPaths(tasks, events);
  const dag = buildAffectedDag(tasks, events, paths);
  const width = 980;
  const rowHeight = 72;
  const height = Math.max(190, paths.length * rowHeight + 48);
  const nodeById = new Map(dag.nodes.map(node => [node.id, node]));

  return <section className="recovery-graph-panel" aria-label="Recovery path graph">
    <div className="recovery-graph-heading">
      <div>
        <p className="eyebrow">RECOVERY PATH GRAPH</p>
        <h3>Failure → decision → release or cascade</h3>
        <p>Each row follows a failed task through advisor selection. Exhausted recoveries cascade; leaf failures have no dependents to rescue.</p>
      </div>
      <div className="recovery-graph-legend">
        <span><i className="recovery-legend-dot recovery-legend-failed" /> Failed</span>
        <span><i className="recovery-legend-dot recovery-legend-advisor" /> Advisor</span>
        <span><i className="recovery-legend-dot recovery-legend-released" /> Released</span>
        <span><i className="recovery-legend-dot recovery-legend-cascaded" /> Cascaded</span>
      </div>
    </div>
    {paths.length === 0 ? (
      <div className="recovery-graph-empty">The graph will populate when the first task failure is observed.</div>
    ) : (
      <>
        <div className="recovery-graph-scroll">
          <svg className="recovery-graph-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Recovery path rows for failed tasks">
            <defs>
              <marker id="recovery-arrow" markerWidth="9" markerHeight="9" refX="8" refY="3" orient="auto">
                <path d="M0,0 L0,6 L9,3 z" fill="currentColor" />
              </marker>
            </defs>
            {paths.map((path, index) => {
              const y = 28 + index * rowHeight;
              const action = path.decisions.length
                ? path.decisions.map(decision => decision.recoveryOptionId ?? '?').join(' → ')
                : path.terminal === 'leaf' ? 'no recovery' : 'waiting';
              const latest = path.decisions[path.decisions.length - 1];
              const source = latest?.advisorSource ?? (path.terminal === 'leaf' ? 'n/a' : 'pending');
              const reason = latest?.message ?? (path.terminal === 'leaf' ? 'no descendants' : 'waiting');
              const terminalClass = path.terminal === 'released'
                ? 'recovery-graph-released'
                : path.terminal === 'cascaded'
                  ? 'recovery-graph-cascaded'
                  : path.terminal === 'leaf'
                    ? 'recovery-graph-leaf'
                    : 'recovery-graph-waiting';
              const terminalKicker = path.terminal === 'released'
                ? 'DEPENDENTS RELEASED'
                : path.terminal === 'cascaded'
                  ? 'DEPENDENTS CASCADE FAILED'
                  : path.terminal === 'leaf'
                    ? 'LEAF FAILURE'
                    : 'DEPENDENTS';
              const terminalLabel = path.terminal === 'released'
                ? (path.dependents.length ? path.dependents : ['no dependents'])
                : path.terminal === 'cascaded'
                  ? path.cascadedVictims
                  : path.terminal === 'leaf'
                    ? ['no dependents']
                    : ['awaiting recovery'];
              return <g key={path.failedId} className="recovery-graph-row">
                <path className="recovery-graph-edge" d={`M 166 ${y + 25} C 190 ${y + 25}, 194 ${y + 25}, 216 ${y + 25}`} markerEnd="url(#recovery-arrow)" />
                <path className="recovery-graph-edge" d={`M 386 ${y + 25} C 410 ${y + 25}, 414 ${y + 25}, 436 ${y + 25}`} markerEnd="url(#recovery-arrow)" />
                <path className="recovery-graph-edge" d={`M 616 ${y + 25} C 640 ${y + 25}, 644 ${y + 25}, 666 ${y + 25}`} markerEnd="url(#recovery-arrow)" />
                <rect className="recovery-graph-node recovery-graph-failed" x="8" y={y} width="158" height="50" rx="7" />
                <text className="recovery-graph-kicker" x="20" y={y + 16}>FAILED TASK</text>
                <text className="recovery-graph-label" x="20" y={y + 36}>{shortLabel(path.failedId, 20)}</text>
                <rect className="recovery-graph-node recovery-graph-advisor" x="216" y={y} width="170" height="50" rx="7" />
                <text className="recovery-graph-kicker" x="228" y={y + 16}>ADVISOR · {shortLabel(source, 12)}</text>
                <text className="recovery-graph-label" x="228" y={y + 36}>{shortLabel(reason, 20)}</text>
                <rect className="recovery-graph-node recovery-graph-action" x="436" y={y} width="180" height="50" rx="7" />
                <text className="recovery-graph-kicker" x="448" y={y + 16}>RECOVERY ACTION</text>
                <text className="recovery-graph-label" x="448" y={y + 36}>{shortLabel(action, 22)}</text>
                <rect className={`recovery-graph-node ${terminalClass}`} x="666" y={y} width="306" height="50" rx="7" />
                <text className="recovery-graph-kicker" x="678" y={y + 16}>{terminalKicker}</text>
                <text className="recovery-graph-label" x="678" y={y + 36}>{shortLabel(terminalLabel.join(' · '), 35)}</text>
              </g>;
            })}
          </svg>
        </div>
        <div className="recovery-dag-block">
          <div className="recovery-dag-heading">
            <p className="eyebrow">AFFECTED SUBGRAPH</p>
            <h4>Failed roots, recovering dependents, and cascade victims</h4>
          </div>
          <div className="recovery-graph-legend recovery-dag-legend">
            <span><i className="recovery-legend-dot recovery-legend-failed" /> Failed</span>
            <span><i className="recovery-legend-dot recovery-legend-recovering" /> Recovering</span>
            <span><i className="recovery-legend-dot recovery-legend-released" /> Recovered / done</span>
            <span><i className="recovery-legend-dot recovery-legend-cascaded" /> Cascade failed</span>
          </div>
          <div className="recovery-graph-scroll">
            <svg className="recovery-dag-svg" viewBox={`0 0 ${dag.width} ${dag.height}`} role="img" aria-label="Affected dependency subgraph for recovery">
              <defs>
                <marker id="dag-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">
                  <path d="M0,0 L0,6 L8,3 z" fill="currentColor" />
                </marker>
              </defs>
              {dag.edges.map(edge => {
                const from = nodeById.get(edge.from);
                const to = nodeById.get(edge.to);
                if (!from || !to) return null;
                return <path
                  key={`${edge.from}->${edge.to}`}
                  className="recovery-graph-edge"
                  d={`M ${from.x + 108} ${from.y + 18} C ${from.x + 130} ${from.y + 18}, ${to.x - 20} ${to.y + 18}, ${to.x} ${to.y + 18}`}
                  markerEnd="url(#dag-arrow)"
                />;
              })}
              {dag.nodes.map(node => (
                <g key={node.id}>
                  <rect className={`recovery-graph-node recovery-dag-node status-${node.status.toLowerCase().replace('_', '-')}`} x={node.x} y={node.y} width="108" height="36" rx="6" />
                  <text className="recovery-graph-label" x={node.x + 8} y={node.y + 22}>{shortLabel(node.id, 14)}</text>
                </g>
              ))}
            </svg>
          </div>
        </div>
      </>
    )}
  </section>;
}
