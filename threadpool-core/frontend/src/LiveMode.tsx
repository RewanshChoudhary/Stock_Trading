import { useEffect, useState } from 'react';
import { ArrowLeft, Play, Square, X } from 'lucide-react';

type Task = { id: string; burstMs: number; priority: number; dependsOn: string[]; failAtMs?: number | null; blocking?: boolean };
type Preset = { name: string; description: string; policy: string; workerCount: number; quantum: number; tasks: Task[]; recoveryMode?: string; advisor?: string };
type PoolEvent = { seq: number; tsMs: number; type: string; taskId?: string; workerId?: string; message?: string; cause?: string; outcome?: string; advisorSource?: string; recoveryOptionId?: string; advisorFallback?: boolean };
type RunState = { runId: string; state: string; taskCount: number; readyCount: number; elapsedMs: number; recoveryMode?: string; advisor?: string };

const eventNames = ['RUN_STARTED', 'TASK_SUBMITTED', 'TASK_READY', 'SLICE_STARTED', 'SLICE_ENDED', 'TASK_COMPLETED', 'TASK_FAILED', 'TASK_RECOVERING', 'AI_DECISION', 'TASK_RECOVERED', 'TASK_CASCADE_FAILED', 'MLFQ_DEMOTED', 'MLFQ_BOOSTED', 'POOL_RESIZED', 'RUN_FINISHED', 'RUN_ABORTED'];
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? undefined as T : response.json();
}

export default function LiveMode({ onBack }: { onBack: () => void }) {
  const [presets, setPresets] = useState<Preset[]>([]); const [presetName, setPresetName] = useState('');
  const [run, setRun] = useState<RunState | null>(null); const [events, setEvents] = useState<PoolEvent[]>([]);
  const [metrics, setMetrics] = useState<any>(null); const [error, setError] = useState(''); const [starting, setStarting] = useState(false);
  const preset = presets.find(item => item.name === presetName); const terminal = !!run && ['FINISHED', 'FAILED', 'CANCELLED'].includes(run.state);

  useEffect(() => { request<Preset[]>('/presets').then(items => { setPresets(items); setPresetName(items[0]?.name ?? ''); }).catch(e => setError(e.message)); }, []);
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
        recoveryMode: preset.recoveryMode ?? 'RECOVER',
        advisor: preset.advisor ?? 'RULE_BASED',
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
  const failTask = async (id: string) => { if (!run) return; try { await request(`/runs/${run.runId}/tasks/${encodeURIComponent(id)}/fail`, { method: 'POST' }); } catch (e) { setError(e instanceof Error ? e.message : 'Could not fail task'); } };
  const taskStatus = (id: string) => {
    let status = 'PENDING';
    for (const event of events) if (event.taskId === id) {
      if (event.type === 'TASK_READY' || (event.type === 'SLICE_ENDED' && event.outcome === 'YIELDED')) status = 'READY';
      if (event.type === 'SLICE_STARTED') status = 'RUNNING';
      if (event.type === 'TASK_RECOVERING') status = 'RECOVERING';
      if (event.type === 'TASK_COMPLETED') status = 'DONE';
      if (event.type === 'TASK_RECOVERED') status = 'DONE';
      if (event.type === 'TASK_FAILED' || event.type === 'TASK_CASCADE_FAILED') status = 'FAILED';
    }
    return status;
  };

  return <main className="app">
    <header className="masthead"><button className="button-secondary" onClick={onBack}><ArrowLeft size={16} /> Simulator</button><div className="brand-copy"><p className="eyebrow">JAVA THREAD POOL · LIVE EVENTS</p><h1>Live Java run</h1><p className="subtitle">Real worker events streamed from the Spring Boot backend.</p></div><span className="live-chip"><span /> {run?.state ?? 'READY'}</span></header>
    <section className="panel paper-panel" style={{ padding: 24, margin: '24px 0' }}>
      <label className="field-label" htmlFor="live-preset">Workload preset</label>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}><select id="live-preset" value={presetName} disabled={!!run && !terminal} onChange={e => setPresetName(e.target.value)}>{presets.map(item => <option key={item.name}>{item.name}</option>)}</select><button className="button-primary" disabled={!preset || starting || (!!run && !terminal)} onClick={start}><Play size={16} /> Start live run</button>{run && !terminal && <button className="button-secondary" onClick={cancel}><Square size={15} /> Cancel</button>}<span>{preset?.policy} · {preset?.workerCount} workers · {preset?.quantum} ms quantum · {preset?.advisor ?? 'RULE_BASED'}</span></div>
      {preset && <p>{preset.description}</p>}{error && <p role="alert" style={{ color: '#b42318' }}>{error}</p>}
    </section>
    {run && <><section className="panel paper-panel" style={{ padding: 24, margin: '24px 0' }}><div className="panel-heading"><div><p className="eyebrow">TASK TOPOLOGY</p><h2>{run.state} · {run.readyCount} ready</h2></div></div><div className="task-list-view">{preset?.tasks.map(task => <div className={`list-task state-${taskStatus(task.id).toLowerCase()}`} key={task.id}><strong>{task.id}</strong><span>{taskStatus(task.id)}</span><small>{task.burstMs} ms · depends on {task.dependsOn.join(', ') || 'nothing'}</small>{!terminal && <button title={`Fail ${task.id} at its next slice boundary`} onClick={() => failTask(task.id)}><X size={14} /> Fail</button>}</div>)}</div></section>
      <section className="night-panel panel" style={{ padding: 24 }}><div className="panel-heading"><div><p className="eyebrow">SEQUENCED TRACE · SSE</p><h2>{events.length} events</h2></div></div><div className="log-entries" style={{ maxHeight: 360, overflow: 'auto' }}>{events.map(event => <div className="log-line" key={event.seq}><time>#{event.seq} · {event.tsMs} ms</time><strong>{event.taskId ?? 'pool'}</strong><span>{event.type === 'AI_DECISION' && <b>{event.advisorSource ?? 'Rule-based'}</b>}{event.type}{event.recoveryOptionId ? ` · ${event.recoveryOptionId}` : ''}{event.advisorFallback ? ' · fallback' : ''}{event.outcome ? ` · ${event.outcome}` : ''}{event.cause ? ` · caused by ${event.cause}` : ''}{event.message ? ` · ${event.message}` : ''}</span></div>)}</div>{metrics && <div className="metrics-row">{[['Elapsed', `${metrics.elapsedMs} ms`], ['Throughput', Number(metrics.throughputTasksPerSecond).toFixed(2)], ['Recovered', `${metrics.recoveredCount ?? 0}`], ['Decisions', `${metrics.recoveryDecisionCount ?? 0}`]].map(([label, value]) => <div className="metric-card" key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>}<p className="simulation-caption">Live Java run timings include HTTP and event-stream overhead. Use /api/compare for headless measurements.</p></section></>}
  </main>;
}
