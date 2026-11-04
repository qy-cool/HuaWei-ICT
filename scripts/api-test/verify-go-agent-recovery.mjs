import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const baseUrl = process.env.CLOIDE_BASE_URL ?? 'http://127.0.0.1:18081'
const agentExecutable = process.env.CLOIDE_AGENT_EXECUTABLE
const adminUsername = process.env.CLOIDE_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.CLOIDE_VALIDATION_ADMIN_PASSWORD
const recoveryPort = 18082
const recoveryUrl = `http://127.0.0.1:${recoveryPort}`
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const serverDirectory = path.resolve(scriptDirectory, '../server')

if (!agentExecutable || !adminUsername || !adminPassword) {
  throw new Error('Set CLOIDE_AGENT_EXECUTABLE and validation admin environment variables.')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function api(pathname, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json() }
}

async function waitUntil(condition, message, timeoutMs = 12_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await condition()) return
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error(message)
}

async function waitForHealth() {
  await waitUntil(async () => {
    try {
      return (await fetch(`${recoveryUrl}/api/health`)).status === 200
    } catch {
      return false
    }
  }, 'Recovery service did not become healthy')
}

function startServer() {
  return spawn('java.exe', ['-jar', 'target/cloide-server-0.0.1-SNAPSHOT.jar'], {
    cwd: serverDirectory,
    env: { ...process.env, SERVER_PORT: String(recoveryPort), SERVER_ADDRESS: '127.0.0.1' },
    stdio: 'ignore'
  })
}

async function stopProcess(process) {
  if (process.exitCode !== null) return
  process.kill('SIGTERM')
  await Promise.race([
    once(process, 'exit'),
    new Promise((resolve) => setTimeout(resolve, 5_000))
  ])
  if (process.exitCode === null) process.kill('SIGKILL')
}

function startAgent(serverId, agentToken) {
  const agent = spawn(agentExecutable, [], {
    env: {
      ...process.env,
      CLOIDE_BACKEND_URL: recoveryUrl.replace(/^http/, 'ws'),
      CLOIDE_SERVER_ID: String(serverId),
      CLOIDE_AGENT_TOKEN: agentToken,
      CLOIDE_COLLECT_INTERVAL_SECONDS: '60',
      CLOIDE_HEARTBEAT_INTERVAL_SECONDS: '60',
      CLOIDE_RECONNECT_INITIAL_SECONDS: '1',
      CLOIDE_RECONNECT_MAX_SECONDS: '4',
      CLOIDE_LOG_LEVEL: 'info'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  const events = []
  let pendingLog = ''
  const collect = (data) => {
    pendingLog += data.toString()
    const lines = pendingLog.split('\n')
    pendingLog = lines.pop()
    for (const line of lines) {
      try {
        events.push(JSON.parse(line))
      } catch {
        // Only structured Agent logs participate in assertions.
      }
    }
  }
  agent.stdout.on('data', collect)
  agent.stderr.on('data', collect)
  return { agent, events }
}

function backoffSeconds(event) {
  if (typeof event.backoff === 'number') return event.backoff / 1_000_000_000
  return Number.parseFloat(String(event.backoff).replace(/s$/, ''))
}

async function stopAgent(agent) {
  if (agent.exitCode !== null) return
  agent.kill('SIGTERM')
  await Promise.race([once(agent, 'exit'), new Promise((resolve) => setTimeout(resolve, 5_000))])
  if (agent.exitCode === null) agent.kill('SIGKILL')
}

const login = await api('/api/auth/login', { method: 'POST', body: { username: adminUsername, password: adminPassword } })
assert(login.status === 200, 'Admin login failed')
const suffix = Date.now()
const created = await api('/api/servers', {
  method: 'POST', token: login.body.data.token,
  body: {
    name: `go-recovery-${suffix}`,
    host: `127.5.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
    description: 'Go Agent recovery validation',
    ssh_host: '127.0.0.1', ssh_port: 22, ssh_user: 'root', ssh_auth_type: 'password', ssh_password: 'validation-placeholder'
  }
})
assert(created.status === 200, `Server creation failed with ${created.status}`)
const serverId = created.body.data.id
const registered = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: login.body.data.token })
assert(registered.status === 200, 'Agent Token registration failed')

let server = startServer()
let oldAgent
let newAgent
try {
  await waitForHealth()
  oldAgent = startAgent(serverId, registered.body.data.agent_token)
  await waitUntil(() => oldAgent.events.filter((event) => event.msg === 'agent authenticated').length === 1,
    'Agent did not complete initial authentication')

  await stopProcess(server)
  await waitUntil(() => oldAgent.events.some((event) => event.msg === 'connection lost, reconnecting'),
    'Agent did not detect service interruption')
  await waitUntil(() => oldAgent.events.some((event) => event.msg === 'connect or authenticate failed, reconnecting'
    && backoffSeconds(event) === 2),
    'Agent did not retry with exponential backoff while service was unavailable')
  server = startServer()
  await waitForHealth()
  await waitUntil(() => oldAgent.events.filter((event) => event.msg === 'agent authenticated').length === 2,
    'Agent did not reauthenticate after service recovery')

  const rotated = await api(`/api/servers/${serverId}/agent/rotate`, { method: 'POST', token: login.body.data.token })
  assert(rotated.status === 200, 'Agent Token rotation failed')
  await stopProcess(server)
  await waitUntil(() => oldAgent.events.filter((event) => event.msg === 'connection lost, reconnecting').length >= 2,
    'Old Token Agent did not detect the second service interruption')
  server = startServer()
  await waitForHealth()
  await waitUntil(() => oldAgent.events.filter((event) => event.msg === 'connect or authenticate failed, reconnecting').length >= 2,
    'Old Token Agent was not rejected after Token rotation')
  assert(oldAgent.events.filter((event) => event.msg === 'agent authenticated').length === 2,
    'Old Token Agent unexpectedly authenticated after Token rotation')

  await stopAgent(oldAgent.agent)
  newAgent = startAgent(serverId, rotated.body.data.agent_token)
  await waitUntil(() => newAgent.events.some((event) => event.msg === 'agent authenticated'),
    'Rotated Token Agent did not authenticate')
} finally {
  if (oldAgent) await stopAgent(oldAgent.agent)
  if (newAgent) await stopAgent(newAgent.agent)
  if (server) await stopProcess(server)
}

console.log(JSON.stringify({ status: 'PASS', checks: 6, token_values_logged: false }))
