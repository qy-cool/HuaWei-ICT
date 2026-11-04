import crypto from 'node:crypto'
import net from 'node:net'
import WebSocket from 'ws'

const baseUrl = process.env.CLOIDE_BASE_URL ?? 'http://127.0.0.1:18081'
const wsUrl = baseUrl.replace(/^http/, 'ws')
const adminUsername = process.env.CLOIDE_VALIDATION_ADMIN_USERNAME
const adminPassword = process.env.CLOIDE_VALIDATION_ADMIN_PASSWORD

if (!adminUsername || !adminPassword) {
  throw new Error('Set CLOIDE_VALIDATION_ADMIN_USERNAME and CLOIDE_VALIDATION_ADMIN_PASSWORD.')
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function api(path, { method = 'GET', body, token } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  return { status: response.status, body: await response.json() }
}

function message(type, payload = {}) {
  return {
    type,
    message_id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    payload
  }
}

function openSocket() {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsUrl}/ws/agent`)
    socket.once('open', () => resolve(socket))
    socket.once('error', reject)
  })
}

function openSocketExpectingError(expectedCode, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsUrl}/ws/agent`)
    let errorCode
    const timeout = setTimeout(() => finish(reject, new Error('Timed out waiting for error and close')), timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'error') errorCode = value.payload?.code
    }
    const onClose = (code) => {
      if (errorCode !== expectedCode || code !== 1008) {
        finish(reject, new Error(`Expected error ${expectedCode} and close 1008, got ${errorCode} and ${code}`))
        return
      }
      finish(resolve)
    }
    const onError = (error) => finish(reject, error)
    const finish = (callback, value) => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
      socket.off('close', onClose)
      socket.off('error', onError)
      callback(value)
    }
    socket.on('message', onMessage)
    socket.on('close', onClose)
    socket.on('error', onError)
  })
}

function waitForMessage(socket, expectedType, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => finish(reject, new Error(`Timed out waiting for ${expectedType}`)), timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === expectedType) finish(resolve, value)
    }
    const onClose = (code) => finish(reject, new Error(`Connection closed with ${code} while waiting for ${expectedType}`))
    const finish = (callback, value) => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
      socket.off('close', onClose)
      callback(value)
    }
    socket.on('message', onMessage)
    socket.on('close', onClose)
  })
}

function waitForErrorAndClose(socket, expectedCode, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    let errorCode
    const timeout = setTimeout(() => finish(reject, new Error('Timed out waiting for error and close')), timeoutMs)
    const onMessage = (data) => {
      const value = JSON.parse(data.toString())
      if (value.type === 'error') errorCode = value.payload?.code
    }
    const onClose = (code) => {
      if (errorCode !== expectedCode || code !== 1008) {
        finish(reject, new Error(`Expected error ${expectedCode} and close 1008, got ${errorCode} and ${code}`))
        return
      }
      finish(resolve)
    }
    const finish = (callback, value) => {
      clearTimeout(timeout)
      socket.off('message', onMessage)
      socket.off('close', onClose)
      callback(value)
    }
    socket.on('message', onMessage)
    socket.on('close', onClose)
  })
}

async function authenticate(serverId, agentToken) {
  const socket = await openSocket()
  const authenticated = waitForMessage(socket, 'agent.authenticated')
  socket.send(JSON.stringify(message('agent.authenticate', { server_id: serverId, token: agentToken })))
  await authenticated
  return socket
}

async function waitForHandshake429() {
  for (let attempt = 0; attempt < 20; attempt++) {
    const result = await new Promise((resolve, reject) => {
      const socket = net.createConnection({ host: '127.0.0.1', port: new URL(baseUrl).port || 80 })
      let response = ''
      socket.setTimeout(5000)
      socket.once('connect', () => {
        socket.write('GET /ws/agent HTTP/1.1\r\n'
          + 'Host: 127.0.0.1\r\n'
          + 'Upgrade: websocket\r\n'
          + 'Connection: Upgrade\r\n'
          + 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n'
          + 'Sec-WebSocket-Version: 13\r\n\r\n')
      })
      socket.on('data', (chunk) => {
        response += chunk.toString()
        if (response.includes('\r\n\r\n')) {
          socket.destroy()
          resolve(/^HTTP\/1\.1 429\b/.test(response))
        }
      })
      socket.once('timeout', () => {
        socket.destroy()
        reject(new Error('Timed out waiting for handshake response'))
      })
      socket.once('error', reject)
    })
    if (result) return
  }
  throw new Error('Handshake rate limit did not return HTTP 429')
}

const login = await api('/api/auth/login', {
  method: 'POST',
  body: { username: adminUsername, password: adminPassword }
})
assert(login.status === 200, 'Admin login failed')
const adminToken = login.body.data.token
const suffix = Date.now()
const created = await api('/api/servers', {
  method: 'POST',
  token: adminToken,
  body: {
    name: `p2-agent-limit-${suffix}`,
    host: `127.3.${Math.floor(suffix / 256) % 254 + 1}.${suffix % 254 + 1}`,
    description: 'P2 Agent resource limit validation',
    ssh_host: '127.0.0.1',
    ssh_port: 22,
    ssh_user: 'root',
    ssh_auth_type: 'password',
    ssh_password: 'validation-placeholder'
  }
})
assert(created.status === 200, `Server creation failed with ${created.status}`)
const serverId = created.body.data.id
const registered = await api(`/api/servers/${serverId}/agent/register`, { method: 'POST', token: adminToken })
assert(registered.status === 200, 'Agent Token registration failed')
const agentToken = registered.body.data.agent_token

const heartbeatSocket = await authenticate(serverId, agentToken)
const heartbeatAck = waitForMessage(heartbeatSocket, 'heartbeat.ack')
heartbeatSocket.send(JSON.stringify(message('heartbeat')))
await heartbeatAck
const heartbeatLimit = waitForErrorAndClose(heartbeatSocket, 42902)
heartbeatSocket.send(JSON.stringify(message('heartbeat')))
await heartbeatLimit

const metricsSocket = await authenticate(serverId, agentToken)
metricsSocket.send(JSON.stringify(message('metrics.report', {
  server_id: serverId,
  collected_at: new Date().toISOString(),
  cpu_percent: 10
})))
await new Promise((resolve) => setTimeout(resolve, 200))
const metricsLimit = waitForErrorAndClose(metricsSocket, 42902)
metricsSocket.send(JSON.stringify(message('metrics.report', {
  server_id: serverId,
  collected_at: new Date().toISOString(),
  cpu_percent: 11
})))
await metricsLimit

const pendingSockets = await Promise.all([openSocket(), openSocket(), openSocket()])
await openSocketExpectingError(42901)
for (const socket of pendingSockets) socket.close()
await new Promise((resolve) => setTimeout(resolve, 200))

await waitForHandshake429()
console.log(JSON.stringify({ status: 'PASS', checks: 4, token_values_logged: false }))
